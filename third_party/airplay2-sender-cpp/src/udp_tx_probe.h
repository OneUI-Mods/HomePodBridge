// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "tx_timing.h"
#if defined(__linux__)
#include <time.h>
#include <sys/socket.h>
#include <linux/errqueue.h>
#include <linux/net_tstamp.h>
#include <netinet/in.h>
#include <cerrno>
#include <cstring>

namespace fxchain {
// No probes on the network: sample existing outgoing audio, one datagram in 16.
class UdpTxProbe {
public:
    bool active=false;
    TxTiming timing;
    void open(int fd) {
        reset();
        int flags=SOF_TIMESTAMPING_SOFTWARE|SOF_TIMESTAMPING_OPT_TSONLY|SOF_TIMESTAMPING_OPT_ID;
        active=setsockopt(fd,SOL_SOCKET,SO_TIMESTAMPING,&flags,sizeof(flags))==0;
    }
    void reset() {active=false;nextId_=packetCounter_=0;timing.reset();}
    ssize_t send(int fd,const void* data,size_t size,int flags,const sockaddr_in& to) {
        if(!active || packetCounter_++%16!=0) {
            const auto n=sendto(fd,data,size,flags,reinterpret_cast<const sockaddr*>(&to),sizeof(to));
            if(n<0)invalidate(fd);
            return n;
        }
        iovec iov{const_cast<void*>(data),size};
        alignas(cmsghdr) unsigned char control[CMSG_SPACE(sizeof(int))]{};
        msghdr msg{};msg.msg_name=const_cast<sockaddr_in*>(&to);msg.msg_namelen=sizeof(to);
        msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
        auto* c=CMSG_FIRSTHDR(&msg);c->cmsg_level=SOL_SOCKET;c->cmsg_type=SO_TIMESTAMPING;c->cmsg_len=CMSG_LEN(sizeof(int));
        int requested=SOF_TIMESTAMPING_TX_SCHED|SOF_TIMESTAMPING_TX_SOFTWARE;
        std::memcpy(CMSG_DATA(c),&requested,sizeof(requested));
        const int64_t real=clockNs(CLOCK_REALTIME),mono=clockNs(CLOCK_MONOTONIC);
        auto n=sendmsg(fd,&msg,flags);
        if(n>=0)timing.sent(nextId_++,real,mono);
        else {
            const int error=errno;invalidate(fd);
            // Ancillary feature failure must never lose an audio datagram.
            if(error==EINVAL || error==ENOPROTOOPT || error==EOPNOTSUPP)
                n=sendto(fd,data,size,flags,reinterpret_cast<const sockaddr*>(&to),sizeof(to));
            else errno=error;
        }
        return n;
    }
    void drain(int fd) {
        if(!active)return;
        for(int i=0;i<64;i++) {
            unsigned char payload[1];iovec iov{payload,sizeof(payload)};
            alignas(cmsghdr) unsigned char control[512]{};
            msghdr msg{};msg.msg_iov=&iov;msg.msg_iovlen=1;msg.msg_control=control;msg.msg_controllen=sizeof(control);
            if(recvmsg(fd,&msg,MSG_ERRQUEUE|MSG_DONTWAIT)<0)break;
            if(msg.msg_flags&MSG_CTRUNC)continue;
            bool haveError=false,haveTime=false;sock_extended_err error{};scm_timestamping stamp{};
            for(auto* c=CMSG_FIRSTHDR(&msg);c;c=CMSG_NXTHDR(&msg,c)) {
                if(c->cmsg_level==SOL_SOCKET && c->cmsg_type==SCM_TIMESTAMPING && c->cmsg_len>=CMSG_LEN(sizeof(stamp))) {
                    std::memcpy(&stamp,CMSG_DATA(c),sizeof(stamp));haveTime=true;
                } else if(c->cmsg_level==SOL_IP && c->cmsg_type==IP_RECVERR && c->cmsg_len>=CMSG_LEN(sizeof(error))) {
                    std::memcpy(&error,CMSG_DATA(c),sizeof(error));haveError=true;
                }
            }
            if(haveTime && haveError && error.ee_origin==SO_EE_ORIGIN_TIMESTAMPING && error.ee_errno==ENOMSG
               && (error.ee_info==SCM_TSTAMP_SCHED || error.ee_info==SCM_TSTAMP_SND)
               && stamp.ts[0].tv_sec>0 && stamp.ts[0].tv_sec<=INT64_MAX/1000000000LL
               && stamp.ts[0].tv_nsec>=0 && stamp.ts[0].tv_nsec<1000000000) {
                const int64_t at=int64_t(stamp.ts[0].tv_sec)*1000000000LL+stamp.ts[0].tv_nsec;
                timing.stamp(error.ee_data,error.ee_info==SCM_TSTAMP_SCHED,at,clockNs(CLOCK_REALTIME),clockNs(CLOCK_MONOTONIC));
            }
        }
    }
private:
    uint32_t nextId_=0;
    uint32_t packetCounter_=0; // Kernel OPT_ID advances for TX_RECORD requests, not unmeasured sends.
    static int64_t clockNs(clockid_t clock) {
        timespec t{};return clock_gettime(clock,&t)==0?int64_t(t.tv_sec)*1000000000LL+t.tv_nsec:0;
    }
    void invalidate(int fd) {active=false;timing.reset();int flags=0;setsockopt(fd,SOL_SOCKET,SO_TIMESTAMPING,&flags,sizeof(flags));}
};
}
#endif
