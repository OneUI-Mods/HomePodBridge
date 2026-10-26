#pragma once
#include "fmq_mapped_queue.h"
#include <chrono>
#include <thread>

struct hp_status_command { uint8_t tag=1, padding[3]={}; int32_t unused=0; };
struct hp_status_position { int64_t frames, time_ns; };
struct hp_status_reply {
    int32_t status, bytes;
    hp_status_position observable, hardware;
    int32_t latency_ms, xrun_frames, state;
};
static_assert(sizeof(hp_status_command)==8);
static_assert(sizeof(hp_status_reply)==56);

// One outstanding getStatus only. No start, burst, reserved-exit, audio writes or pointer resets.
inline bool hp_query_standby(hp_mapped_queue& command, hp_mapped_queue& reply,
                             hp_status_reply* result, int64_t* duration_us,
                             std::chrono::milliseconds timeout=std::chrono::milliseconds(50)) {
    if(!result || !duration_us || timeout.count()<=0 || timeout.count()>50
        || !command.view.valid() || !reply.view.valid()
        || command.view.capacity!=8 || command.view.quantum!=8
        || reply.view.capacity!=56 || reply.view.quantum!=56) return false;
    auto empty=[](const hp_fmq_view& q) {
        return q.read->load(std::memory_order_acquire)==q.write->load(std::memory_order_acquire);
    };
    if(!empty(command.view) || !empty(reply.view)) return false;
    hp_status_command query{};
    auto begin=std::chrono::steady_clock::now();
    if(!command.view.transfer(&query,sizeof(query),true) || !command.notify(2)) return false;
    hp_status_reply response{};
    while(std::chrono::steady_clock::now()-begin<timeout) {
        if(reply.view.transfer(&response,sizeof(response),false)) {
            reply.notify(1);
            *duration_us=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
            *result=response;
            return response.status==0 && response.bytes==0 && response.state==1;
        }
        std::this_thread::sleep_for(std::chrono::microseconds(100));
    }
    return false;
}
