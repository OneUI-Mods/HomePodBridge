#pragma once
#include "fmq_zero_transfer.h"
#include "zero_pcm_pipe.h"

struct hp_live_result {
    int error=0;int64_t captured=0,sent=0,idle=0,cycles=0,elapsed=0,maximum=0;
    int64_t max_pending=0,min_pending=INT64_MAX,sum_pending=0,observations=0;
};

// One output clock, including silence while no playback exists. Idle never consumes/pads HAL input.
inline hp_live_result hp_live_capture(std::array<hp_mapped_queue,3>& q,bool zero_only) {
    hp_live_result r{};hp_status_reply reply{};int64_t duration=0;
    if(!hp_stream_once(q[0],q[1],2,0,2,&reply,&duration)) {r.error=1;return r;}
    std::array<uint8_t,176> pcm{};
    auto begin=std::chrono::steady_clock::now();
    while(true) {
        auto offset=std::chrono::seconds(r.sent/44100)
            +std::chrono::nanoseconds((r.sent%44100)*1000000000/44100);
        std::this_thread::sleep_until(begin+offset);
        if(!hp_input_position(q[0],q[1],&reply)) {r.error=7;break;}
        int64_t pending=-1;
        if(reply.observable.frames-r.captured>=44) {
            if(!hp_empty(q[2]) || !hp_stream_once(q[0],q[1],3,176,3,&reply,&duration)) {r.error=2;break;}
            r.maximum=std::max(r.maximum,duration);
            if(!q[2].view.transfer(pcm.data(),pcm.size(),false)) {r.error=3;break;}
            r.captured+=44;pending=reply.observable.frames-r.captured;
            if(pending<0 || pending>4096) {r.error=5;break;}
            if(r.cycles>100) {++r.observations;r.sum_pending+=pending;
                r.max_pending=std::max(r.max_pending,pending);r.min_pending=std::min(r.min_pending,pending);}
        } else {
            pcm.fill(0);r.idle+=44;
        }
        timespec now{};
        if(clock_gettime(CLOCK_MONOTONIC,&now)!=0 || !hp_write_pcm_packet(STDOUT_FILENO,
            int64_t(now.tv_sec)*1000000000+now.tv_nsec,pending,pcm.data(),true,zero_only)) {r.error=10;break;}
        r.sent+=44;++r.cycles;
    }
    r.elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
    return r;
}
