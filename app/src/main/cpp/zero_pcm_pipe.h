#pragma once
#include <array>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <poll.h>
#include <unistd.h>
#include <sys/socket.h>

// No additional messages are expected after queue setup; readable/HUP means stop or owner death.
inline bool hp_capture_owner_alive(int fd) {
    if(fd<0) return true;
    pollfd owner{fd,POLLIN,0};
    int status=::poll(&owner,1,0);
    return !((status<0 && errno!=EINTR) || owner.revents);
}

// Bounded atomic records: 44-frame legacy zero packets, or 22/44-frame live PCM packets.
inline bool hp_write_pcm_packet(int fd,int64_t captured_ns,int64_t pending_frames,
                                const uint8_t* pcm,bool live,bool zero_only,int frames=44,bool socket=false) {
    if(fd<0 || captured_ns<=0 || pending_frames<(live ? -1:0) || pending_frames>4096
        || (frames!=44 && (!live || frames!=22)) || (!zero_only && !pcm)) return false;
    const size_t payload_bytes=size_t(frames)*4, packet_bytes=24+payload_bytes;
    std::array<uint8_t,200> packet{};
    auto be=[&](size_t at,uint64_t value,size_t bytes) {
        for(size_t i=0;i<bytes;i++) packet[at+i]=uint8_t(value>>((bytes-i-1)*8));
    };
    be(0,live ? 0x48425033:0x48425032,4);be(4,payload_bytes,4);be(8,uint64_t(captured_ns),8);be(16,uint64_t(pending_frames),8);
    if(!zero_only) std::copy(pcm,pcm+payload_bytes,packet.begin()+24);
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(50);
    while(true) {
        // Per-send flags keep the shared socket's read side blocking for the owner death watcher.
        ssize_t count=socket ? ::send(fd,packet.data(),packet_bytes,MSG_DONTWAIT|MSG_NOSIGNAL)
                            : ::write(fd,packet.data(),packet_bytes);
        if(count==ssize_t(packet_bytes)) return true;
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
