#pragma once
#include <array>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <poll.h>
#include <unistd.h>

// A fixed-size atomic pipe record. Its payload is always newly constructed zeros, never caller PCM.
inline bool hp_write_pcm_packet(int fd,int64_t captured_ns,int64_t pending_frames,
                                const uint8_t* pcm,bool live,bool zero_only) {
    if(fd<0 || captured_ns<=0 || pending_frames<(live ? -1:0) || pending_frames>4096
        || (!zero_only && !pcm)) return false;
    std::array<uint8_t,200> packet{};
    auto be=[&](size_t at,uint64_t value,size_t bytes) {
        for(size_t i=0;i<bytes;i++) packet[at+i]=uint8_t(value>>((bytes-i-1)*8));
    };
    be(0,live ? 0x48425033:0x48425032,4);be(4,176,4);be(8,uint64_t(captured_ns),8);be(16,uint64_t(pending_frames),8);
    if(!zero_only) std::copy(pcm,pcm+176,packet.begin()+24);
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(50);
    while(true) {
        ssize_t count=::write(fd,packet.data(),packet.size());
        if(count==ssize_t(packet.size())) return true;
        if(count>=0 || (errno!=EAGAIN && errno!=EWOULDBLOCK && errno!=EINTR)) return false;
        if(std::chrono::steady_clock::now()>=deadline) return false;
        pollfd wait{fd,POLLOUT,0};
        if(::poll(&wait,1,1)<0 && errno!=EINTR) return false;
        if(wait.revents&(POLLERR|POLLHUP|POLLNVAL)) return false;
    }
}

inline bool hp_write_zero_packet(int fd,int64_t captured_ns,int64_t pending_frames) {
    return hp_write_pcm_packet(fd,captured_ns,pending_frames,nullptr,false,true);
}
