// SPDX-License-Identifier: Apache-2.0
#include "tx_timing.h"
#include "udp_tx_probe.h"
#include <cstdio>
#if defined(__linux__)
#include <unistd.h>
#endif

static int failures=0;
#define CHECK(x) do{if(!(x)){std::printf("FAIL line %d: %s\n",__LINE__,#x);++failures;}}while(0)
int main() {
    fxchain::TxTiming timing;
    CHECK(timing.percentile(false,95)==-1 && timing.count()==0);
    constexpr int64_t real=1000000000000LL,mono=5000000000LL;
    timing.sent(4,real,mono);
    timing.stamp(4,true,real+100000,real+1000000,mono+1000000);
    CHECK(timing.count()==0);
    timing.stamp(4,false,real+500000,real+2000000,mono+2000000);
    CHECK(timing.count()==1 && timing.percentile(false,95)==500 && timing.percentile(true,95)==400);
    CHECK(timing.recentPercentile(false,95,mono+1000000000LL)==500);
    CHECK(timing.recentPercentile(false,95,mono+4000000000LL)==-1);
    timing.stamp(4,false,real+500000,real+2000000,mono+2000000);CHECK(timing.count()==1);
    timing.sent(5,real,mono);
    timing.stamp(5,false,real+200000,real+1000000,mono+1000000);
    timing.stamp(5,true,real+100000,real+1000000,mono+1000000);CHECK(timing.count()==2);
    timing.sent(6,real,mono);
    timing.stamp(6,true,real+100000,real+20000000,mono+1000000);
    timing.stamp(6,false,real+300000,real+20000000,mono+1000000);
    CHECK(timing.count()==2 && timing.rejected()==1);
    timing.sent(7,real,mono);timing.sent(263,real,mono);
    timing.stamp(7,true,real+100000,real+1000000,mono+1000000);
    timing.stamp(7,false,real+200000,real+1000000,mono+1000000);CHECK(timing.count()==2);
    timing.sent(8,real,mono);
    timing.stamp(8,true,real+400000,real+1000000,mono+1000000);
    timing.stamp(8,false,real+200000,real+1000000,mono+1000000);CHECK(timing.rejected()==2);
    timing.reset();CHECK(timing.count()==0 && timing.percentile(false,50)==-1);
#if defined(__linux__)
    int fd=socket(AF_INET,SOCK_DGRAM,0),receiver=socket(AF_INET,SOCK_DGRAM,0);
    CHECK(fd>=0 && receiver>=0);
    sockaddr_in to{};to.sin_family=AF_INET;to.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    CHECK(bind(receiver,reinterpret_cast<sockaddr*>(&to),sizeof(to))==0);
    socklen_t len=sizeof(to);CHECK(getsockname(receiver,reinterpret_cast<sockaddr*>(&to),&len)==0);
    timeval timeout{1,0};CHECK(setsockopt(receiver,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout))==0);
    fxchain::UdpTxProbe probe;probe.open(fd);CHECK(probe.active);
    for(int n=0;n<64;n++) {
        unsigned char value=static_cast<unsigned char>(n),received=0;
        CHECK(probe.send(fd,&value,1,0,to)==1);
        CHECK(recv(receiver,&received,1,0)==1 && received==value);
        probe.drain(fd);
    }
    CHECK(probe.timing.count()==4 && probe.timing.percentile(false,95)>=0);
    CHECK(probe.timing.percentile(true,95)>=0);
    std::printf("Kernel samples=%llu callToDriverP95Us=%lld schedulerToDriverP95Us=%lld\n",
        (unsigned long long)probe.timing.count(),(long long)probe.timing.percentile(false,95),(long long)probe.timing.percentile(true,95));
    close(fd);close(receiver);probe.reset();CHECK(!probe.active && probe.timing.count()==0);
#endif
    std::printf("Tx timing checks: %s\n",failures?"FAILED":"PASS");return failures?1:0;
}
