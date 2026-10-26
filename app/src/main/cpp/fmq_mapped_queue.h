// Prototype only; attaches to caller-provided FDs. It never opens an audio device.
#pragma once
#include "fmq_queue_view.h"
#include <array>
#include <climits>
#include <fcntl.h>
#include <linux/futex.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/sharedmem.h>
#endif

struct hp_grantor { int32_t fd_index; uint64_t offset, extent; };
class hp_mapped_queue {
    std::array<int, 8> fds_{};
    struct Region { void* mapping=nullptr; size_t bytes=0; void* start=nullptr; };
    std::array<Region,4> regions_{};
    std::atomic<uint32_t>* event_=nullptr;
    bool attached_=false;
public:
    hp_fmq_view view{};
    hp_mapped_queue() { fds_.fill(-1); }
    hp_mapped_queue(const hp_mapped_queue&)=delete;
    hp_mapped_queue& operator=(const hp_mapped_queue&)=delete;
    ~hp_mapped_queue() { close(); }
    void close() {
        view={};event_=nullptr;attached_=false;
        for (auto& r:regions_) { if(r.mapping) munmap(r.mapping,r.bytes);r={}; }
        for (int& fd:fds_) { if(fd>=0) ::close(fd);fd=-1; }
    }
    bool attach(const int* fds,size_t fd_count,const hp_grantor* grants,size_t count,
                size_t quantum,uint32_t flags) {
        if(attached_ || !fds || !fd_count || fd_count>fds_.size() || !grants
            || (count!=3 && count!=4) || flags!=1 || !quantum || quantum>4096) return false;
        long page=sysconf(_SC_PAGESIZE);if(page<=0) return false;
        for(size_t i=0;i<count;++i) {
            const auto& g=grants[i];
            if(g.fd_index<0 || size_t(g.fd_index)>=fd_count || !g.extent || g.extent>4*1024*1024
                || g.offset>UINT64_MAX-g.extent) return false;
            if((i<2 && (g.extent!=8 || g.offset%8)) || (i==3 && (g.extent!=4 || g.offset%4))) return false;
            if(i==2 && (g.extent%quantum || g.extent<quantum)) return false;
            for(size_t j=0;j<i;++j) if(g.fd_index==grants[j].fd_index
                && g.offset<grants[j].offset+grants[j].extent
                && grants[j].offset<g.offset+g.extent) return false;
        }
        for(size_t i=0;i<fd_count;++i) {
            fds_[i]=fcntl(fds[i],F_DUPFD_CLOEXEC,3);
            if(fds_[i]<0) {close();return false;}
        }
        std::array<struct stat,8> info{};
        for(size_t i=0;i<fd_count;++i) if(fstat(fds_[i],&info[i])) {close();return false;}
        for(size_t i=0;i<count;++i) for(size_t j=0;j<i;++j) {
            const auto& a=grants[i];const auto& b=grants[j];
            const auto& ia=info[a.fd_index];const auto& ib=info[b.fd_index];
            if(ia.st_dev==ib.st_dev && ia.st_ino==ib.st_ino
                && a.offset<b.offset+b.extent && b.offset<a.offset+a.extent) {close();return false;}
        }
        for(size_t i=0;i<count;++i) {
            const auto& g=grants[i];struct stat st{};int fd=fds_[g.fd_index];
            if(fstat(fd,&st) || st.st_size<0) {close();return false;}
            uint64_t region_size=uint64_t(st.st_size);
#ifdef __ANDROID__
            // Legacy ashmem FDs can report st_size=0. Query the NDK's validated region size.
            if(!region_size) region_size=ASharedMemory_getSize(fd);
#endif
            if(!region_size || g.offset+g.extent>region_size) {close();return false;}
            uint64_t base=g.offset-g.offset%uint64_t(page);size_t delta=g.offset-base;
            size_t bytes=delta+size_t(g.extent);
            void* memory=mmap(nullptr,bytes,PROT_READ|PROT_WRITE,MAP_SHARED,fd,off_t(base));
            if(memory==MAP_FAILED) {close();return false;}
            regions_[i]={memory,bytes,static_cast<uint8_t*>(memory)+delta};
        }
        view={static_cast<std::atomic<uint64_t>*>(regions_[0].start),
            static_cast<std::atomic<uint64_t>*>(regions_[1].start),
            static_cast<uint8_t*>(regions_[2].start),size_t(grants[2].extent),quantum};
        if(!view.valid() || view.write->load(std::memory_order_acquire)-
            view.read->load(std::memory_order_acquire)>view.capacity) {close();return false;}
        if(count==4) event_=static_cast<std::atomic<uint32_t>*>(regions_[3].start);
        attached_=true;return true;
    }
    bool notify(uint32_t bits) {
        if(!attached_ || !event_ || (bits!=1 && bits!=2)) return false;
        event_->fetch_or(bits,std::memory_order_release);
        syscall(SYS_futex,event_,FUTEX_WAKE,INT_MAX,nullptr,nullptr,0);
        return true;
    }
};
