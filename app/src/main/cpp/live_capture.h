#pragma once
#include "fmq_zero_transfer.h"
#include "zero_pcm_pipe.h"

struct hp_live_result {
    int error=0;int64_t captured=0,sent=0,idle=0,cycles=0,elapsed=0,maximum=0;
    int64_t status_queries=0,total_input_us=0;
    int64_t max_pending=0,min_pending=INT64_MAX,sum_pending=0,observations=0;
};

// One output clock, including silence while no playback exists. Idle never consumes/pads HAL input.
inline hp_live_result hp_live_capture(std::array<hp_mapped_queue,3>& q,bool zero_only,
                                     int output=STDOUT_FILENO,int owner_control=-1) {
    hp_live_result r{};hp_status_reply reply{};int64_t duration=0;
    if(!hp_stream_once(q[0],q[1],2,0,2,&reply,&duration)) {r.error=1;return r;}
    constexpr int frames=22;
    std::array<uint8_t,frames*4> pcm{};
    auto begin=std::chrono::steady_clock::now();
    // Bounded owned producer periods use two-quantum tolerance. Legacy 928 keeps its clock.
    const int producer_frames=(int)(q[2].view.capacity/4);
    const bool short_period=producer_frames>=48 && producer_frames<928;
    const auto producer_quantum=std::chrono::microseconds((producer_frames*1000000LL+44099)/44100);
    // A smaller mixer quantum must not shrink the legacy short-path scheduling tolerance.
    // This is a wait for recent production only, never an extra PCM reserve.
    const auto recent_production=std::max(producer_quantum*3,std::chrono::microseconds(6750));
    const auto production_grace=std::max(producer_quantum*2,std::chrono::microseconds(4500));
    int64_t last_produced=reply.observable.frames;
    auto last_production=begin;
    auto note_production=[&] {
        if(reply.observable.frames>last_produced) {
            last_produced=reply.observable.frames;last_production=std::chrono::steady_clock::now();
        }
    };
    while(true) {
        if(!hp_capture_owner_alive(owner_control)) {r.error=11;break;}
        auto offset=std::chrono::seconds(r.sent/44100)
            +std::chrono::nanoseconds((r.sent%44100)*1000000000/44100);
        std::this_thread::sleep_until(begin+offset);
        if(reply.observable.frames-r.captured<frames) {
            ++r.status_queries;
            if(!hp_input_position(q[0],q[1],&reply)) {r.error=7;break;}
            note_production();
        }
        if(short_period && last_produced>0 && reply.observable.frames-r.captured<frames
            && std::chrono::steady_clock::now()-last_production<recent_production) {
            auto grace=std::chrono::steady_clock::now()+production_grace;
            while(reply.observable.frames-r.captured<frames && std::chrono::steady_clock::now()<grace) {
                if(!hp_capture_owner_alive(owner_control)){r.error=11;break;}
                std::this_thread::sleep_for(std::chrono::microseconds(100));
                ++r.status_queries;
                if(!hp_input_position(q[0],q[1],&reply)){r.error=7;break;}
                note_production();
            }
            if(r.error)break;
        }
        int64_t pending=-1;
        if(reply.observable.frames-r.captured>=frames) {
            if(!hp_empty(q[2]) || !hp_stream_once(q[0],q[1],3,frames*4,3,&reply,&duration)) {r.error=2;break;}
            note_production();
            r.maximum=std::max(r.maximum,duration);r.total_input_us+=duration;
            if(!q[2].view.transfer(pcm.data(),pcm.size(),false)) {r.error=3;break;}
            r.captured+=frames;pending=reply.observable.frames-r.captured;
            if(pending<0 || pending>4096) {r.error=5;break;}
            if(r.cycles>100) {++r.observations;r.sum_pending+=pending;
                r.max_pending=std::max(r.max_pending,pending);r.min_pending=std::min(r.min_pending,pending);}
        } else {
            pcm.fill(0);r.idle+=frames;
        }
        timespec now{};
        if(clock_gettime(CLOCK_MONOTONIC,&now)!=0 || !hp_write_pcm_packet(output,
            int64_t(now.tv_sec)*1000000000+now.tv_nsec,pending,pcm.data(),true,zero_only,frames,owner_control>=0)) {r.error=10;break;}
        r.sent+=frames;++r.cycles;
    }
    r.elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
    return r;
}
