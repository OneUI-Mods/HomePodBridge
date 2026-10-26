#include "../../main/cpp/fmq_mapped_queue.h"
#include <array>
#include <cassert>
#include <cstdio>
#include <limits>
#include <thread>

int main() {
    static_assert(std::atomic<uint64_t>::is_always_lock_free);
    std::atomic<uint64_t> read{16}, write{16};
    std::array<uint8_t, 64> memory{};
    hp_fmq_view queue{&read, &write, memory.data(), memory.size(), 8};
    uint64_t value=123, result=0;
    assert(queue.valid()); assert(!queue.transfer(&result, 8, false));
    assert(read == 16 && write == 16); // Attach and failed reads preserve shared state.
    for (int i=0;i<100;++i) {
        assert(queue.transfer(&value,8,true)); assert(queue.transfer(&result,8,false)); assert(result==value);
    }
    // A terminal counter overflow must fail without rebasing or altering shared state.
    read=write=std::numeric_limits<uint64_t>::max()-7;
    assert(!queue.transfer(&value,8,true)); assert(write==UINT64_MAX-7);
    read=write=0;
    for(int i=0;i<8;++i) assert(queue.transfer(&value,8,true));
    assert(!queue.transfer(&value,8,true));
    assert(!queue.transfer(&value,7,true));
    read=0;write=72;
    assert(!queue.transfer(&result,8,false)); assert(read==0 && write==72);
    read=0;write=0;
    // Independent producer/consumer scheduling must preserve every ordered value.
    constexpr uint64_t count=100000;
    std::thread producer([&] {
        for(uint64_t n=1;n<=count;++n) while(!queue.transfer(&n,8,true)) std::this_thread::yield();
    });
    for(uint64_t n=1;n<=count;++n) {
        uint64_t observed=0;
        while(!queue.transfer(&observed,8,false)) std::this_thread::yield();
        assert(observed==n);
    }
    producer.join();
    assert(read==write && read==count*8);
    int fd=memfd_create("homepod-fmq-test",MFD_CLOEXEC);assert(fd>=0);assert(!ftruncate(fd,4096));
    uint64_t initial=16;assert(pwrite(fd,&initial,8,0)==8);assert(pwrite(fd,&initial,8,8)==8);
    hp_grantor grants[]={{0,0,8},{0,8,8},{0,16,64},{0,80,4}};
    {
        hp_mapped_queue mapped;
        assert(mapped.attach(&fd,1,grants,4,8,1));
        assert(mapped.view.read->load()==16 && mapped.view.write->load()==16);
        assert(!mapped.attach(&fd,1,grants,4,8,1));
        assert(mapped.view.transfer(&value,8,true));
        assert(mapped.notify(2));uint32_t event=0;assert(pread(fd,&event,4,80)==4 && event==2);
        assert(!mapped.notify(4));
        assert(mapped.view.transfer(&result,8,false) && result==value);
        assert(!::close(fd)); // Mappings and duplicated FD ownership outlive the caller's handle.
        assert(mapped.view.transfer(&value,8,true));
        assert(mapped.view.transfer(&result,8,false) && result==value);
        mapped.close();assert(!mapped.view.valid());
    }
    fd=memfd_create("homepod-fmq-invalid",MFD_CLOEXEC);assert(fd>=0);assert(!ftruncate(fd,4096));
    {
        hp_mapped_queue bad;
        grants[1].offset=0;assert(!bad.attach(&fd,1,grants,4,8,1));grants[1].offset=8;
        grants[2].extent=4096;assert(!bad.attach(&fd,1,grants,4,8,1));grants[2].extent=64;
        grants[0].fd_index=1;assert(!bad.attach(&fd,1,grants,4,8,1));grants[0].fd_index=0;
        assert(!bad.attach(&fd,1,grants,4,7,1));
        assert(!bad.attach(&fd,1,grants,4,8,2));
        int alias=dup(fd);assert(alias>=0);int duplicates[]={fd,alias};
        grants[1]={1,0,8};assert(!bad.attach(duplicates,2,grants,4,8,1));
        assert(!::close(alias));grants[1]={0,8,8};
        assert(fcntl(fd,F_GETFD)>=0); // Rejecting a descriptor never consumes a caller FD.
    }
    assert(!::close(fd));
    std::puts("FMQ mapping, ownership and 100000 ordered messages passed");
}
