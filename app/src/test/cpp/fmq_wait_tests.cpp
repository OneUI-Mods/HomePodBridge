#include "../../main/cpp/fmq_zero_transfer.h"
#include <cassert>
#include <cstdio>
#include <sys/wait.h>

int main() {
    int cf=memfd_create("hpb-wait-command",MFD_CLOEXEC),rf=memfd_create("hpb-wait-reply",MFD_CLOEXEC);
    assert(cf>=0 && rf>=0 && !ftruncate(cf,4096) && !ftruncate(rf,4096));
    hp_grantor cg[]={{0,0,8},{0,8,8},{0,16,8},{0,24,4}},rg[]={{0,0,8},{0,8,8},{0,16,56},{0,72,4}};
    hp_mapped_queue command,reply,no_event;
    assert(command.attach(&cf,1,cg,4,8,1) && reply.attach(&rf,1,rg,4,56,1));
    assert(no_event.attach(&rf,1,rg,3,56,1));
    assert(!no_event.wait_readable(std::chrono::steady_clock::now()+std::chrono::milliseconds(1)));
    uint32_t stale=0x103;assert(pwrite(rf,&stale,4,72)==4);
    auto start=std::chrono::steady_clock::now();
    assert(!reply.wait_readable(start+std::chrono::milliseconds(2)));
    assert(std::chrono::steady_clock::now()-start<std::chrono::milliseconds(50));
    uint32_t bits=0;assert(pread(rf,&bits,4,72)==4 && bits==0x101); // Preserve NOT_FULL and unrelated bits.
    pid_t child=fork();assert(child>=0);
    if(child==0) {
        for(int i=0;i<400;i++) {
            assert(command.wait_readable(std::chrono::steady_clock::now()+std::chrono::seconds(1)));
            hp_status_command query{};assert(command.view.transfer(&query,8,false) && query.tag==1);
            assert(command.notify(1));
            hp_status_reply response{};response.state=3;response.observable.frames=i;
            assert(reply.view.transfer(&response,56,true) && reply.notify(2));
        }
        _exit(0);
    }
    int64_t means[2]{};
    for(int mode=0;mode<2;mode++) {
        std::array<int64_t,200> latency{};
        for(int i=0;i<200;i++) {
            auto begin=std::chrono::steady_clock::now();hp_status_reply response{};
            assert(hp_input_position(command,reply,&response,mode==1));
            assert(response.observable.frames==mode*200+i);
            latency[size_t(i)]=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
            means[mode]+=latency[size_t(i)];
        }
        std::sort(latency.begin(),latency.end());
        std::printf("%s medianUs=%lld p95Us=%lld meanUs=%lld\n",mode?"event":"poll",(long long)latency[100],(long long)latency[190],(long long)(means[mode]/200));
    }
    int status;assert(waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==0);
    assert(hp_empty(command) && hp_empty(reply));
    // Data already present must be readable without a new wake, even after the notification bit was consumed.
    hp_status_reply ready{};assert(reply.view.transfer(&ready,56,true));
    assert(reply.wait_readable(std::chrono::steady_clock::now()));
    assert(reply.view.transfer(&ready,56,false));
    assert(!close(cf) && !close(rf));
    puts("PASS shared futex wake, stale flags, lost-wake race, timeout, bit preservation and ordered replies");
}
