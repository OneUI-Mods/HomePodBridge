#pragma once
#include "fmq_control.h"
#include <algorithm>
#include <array>

struct hp_zero_result {
    bool passed=false;
    int frames=0,cycles=0,error=0,start_flags=0;
    int64_t sent=0,captured=0,elapsed_us=0,max_output_us=0,max_input_us=0,max_pending=0;
    int64_t observed=-1,expected=0;
};

inline bool hp_realtime_frames(int64_t frames,int64_t elapsed_us) {
    if(frames<=0 || frames>10000000 || elapsed_us<500000 || elapsed_us>15000000) return false;
    const int64_t expected=elapsed_us*44100;
    return frames*100000000>=expected*95 && frames*100000000<=expected*105;
}

inline bool hp_empty(const hp_mapped_queue& q) {
    return q.view.valid() && q.view.read->load(std::memory_order_acquire)==q.view.write->load(std::memory_order_acquire);
}

// Exactly one command globally outstanding. Only start and bounded zero-PCM bursts are permitted.
inline bool hp_stream_dispatch(hp_mapped_queue& command,hp_mapped_queue& reply,uint8_t tag,int bytes) {
    if((tag!=2 && tag!=3) || (tag==2 && bytes!=0)
        || (tag==3 && (bytes<=0 || bytes>3712 || bytes%4))
        || command.view.quantum!=8 || command.view.capacity!=8
        || reply.view.quantum!=56 || reply.view.capacity!=56
        || !command.can_notify() || !reply.can_notify() || !hp_empty(command) || !hp_empty(reply)) return false;
    hp_status_command value{};value.tag=tag;value.unused=bytes;
    return command.view.transfer(&value,8,true) && command.notify(2);
}

inline bool hp_stream_once(hp_mapped_queue& command,hp_mapped_queue& reply,uint8_t tag,int bytes,
                            int expected_state,hp_status_reply* result,int64_t* elapsed_us) {
    auto begin=std::chrono::steady_clock::now();
    if(!hp_stream_dispatch(command,reply,tag,bytes)) return false;
    while(std::chrono::steady_clock::now()-begin<std::chrono::milliseconds(100)) {
        if(reply.view.transfer(result,56,false)) {
            reply.notify(1);
            *elapsed_us=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
            return result->status==0 && result->bytes==bytes && result->state==expected_state && result->xrun_frames<=0;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
    return false;
}

// Query without consuming PCM. Only an input with a sole, owned reader may use this as a gate.
inline bool hp_input_position(hp_mapped_queue& command,hp_mapped_queue& reply,hp_status_reply* result) {
    if(!hp_dispatch_status(command,reply)) return false;
    auto begin=std::chrono::steady_clock::now();
    while(std::chrono::steady_clock::now()-begin<std::chrono::milliseconds(100)) {
        if(reply.view.transfer(result,56,false)) {
            reply.notify(1);
            return result->status==0 && result->bytes==0 && (result->state==2 || result->state==3)
                && result->xrun_frames<=0 && result->observable.frames>=0;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
    return false;
}

inline hp_zero_result hp_zero_transfer(std::array<hp_mapped_queue,6>& q,int frames,bool exit_after_input,bool output_only=false) {
    hp_zero_result report{};report.frames=frames;
    if(frames!=22 && frames!=44 && frames!=88 && frames!=928) {report.error=1;return report;}
    int bytes=frames*4;
    for(auto& queue:q) if(!hp_empty(queue)) {report.error=1;return report;}
    if(q[2].view.quantum!=1 || q[5].view.quantum!=1
        || q[2].view.capacity<size_t(bytes) || q[5].view.capacity<size_t(bytes)) {report.error=1;return report;}
    hp_status_reply response{};int64_t elapsed=0;
    if(!hp_stream_once(q[0],q[1],2,0,2,&response,&elapsed)) {report.error=2;return report;}
    report.start_flags=1;
    if(!hp_stream_once(q[3],q[4],2,0,2,&response,&elapsed) || response.observable.frames<0) {report.error=3;return report;}
    report.start_flags=3;
    int64_t input_origin=response.observable.frames;
    std::array<uint8_t,3712> zeros{},input{};
    auto begin=std::chrono::steady_clock::now();
    while(report.cycles<4096) {
        if(!hp_empty(q[2]) || !q[2].view.transfer(zeros.data(),size_t(bytes),true)) {report.error=4;break;}
        if(!hp_stream_once(q[0],q[1],3,bytes,3,&response,&elapsed)) {report.error=5;break;}
        report.max_output_us=std::max(report.max_output_us,elapsed);report.sent+=frames;
        if(output_only) {
            ++report.cycles;
            if(report.cycles>=8) break;
            continue;
        }
        if(!hp_empty(q[2]) || !hp_empty(q[5])) {report.error=6;break;}
        if(exit_after_input) {
            if(!hp_stream_dispatch(q[3],q[4],3,bytes)) {report.error=7;break;}
            _exit(75);
        }
        if(!hp_stream_once(q[3],q[4],3,bytes,3,&response,&elapsed)) {report.error=7;break;}
        report.max_input_us=std::max(report.max_input_us,elapsed);
        if(!q[5].view.transfer(input.data(),size_t(bytes),false)
            || std::any_of(input.begin(),input.begin()+bytes,[](uint8_t value){return value!=0;})) {report.error=8;break;}
        report.captured+=frames;++report.cycles;
        report.observed=response.observable.frames;report.expected=input_origin+report.captured;
        report.max_pending=std::max(report.max_pending,report.observed-report.expected);
        if(report.observed!=report.expected) {report.error=9;break;}
        if(std::chrono::steady_clock::now()-begin>=std::chrono::milliseconds(500)) break;
    }
    report.elapsed_us=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
    report.passed=output_only ? (report.error==5 && report.cycles>=4 && report.sent>=3712)
        : (report.error==0 && report.cycles>=4 && report.sent==report.captured);
    for(auto& queue:q) if(!hp_empty(queue)) report.passed=false;
    return report;
}
