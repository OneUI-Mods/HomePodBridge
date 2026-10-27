// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>

namespace fxchain {
// Paired local software timestamps only. No receiver/airtime/one-way inference.
class TxTiming {
public:
    struct Sample { int64_t callToDriverUs, schedulerToDriverUs; };
    void reset() { pending_={}; samples_={}; count_=rejected_=next_=0; }
    void sent(uint32_t id,int64_t realtime,int64_t monotonic) {
        pending_[id % pending_.size()]={id,realtime,monotonic,0,0,realtime>0 && monotonic>0};
    }
    void stamp(uint32_t id,bool scheduler,int64_t time,int64_t realtime,int64_t monotonic) {
        auto& p=pending_[id % pending_.size()];
        if(!p.live || p.id!=id)return;
        const int64_t age=monotonic-p.monotonic;
        const int64_t realAge=realtime-p.realtime;
        if(time<=0 || realtime<=0 || monotonic<=0 || age<0 || age>2000000000LL
           || realAge<age-2000000LL || realAge>age+2000000LL) {
            p.live=false;++rejected_;return;
        }
        (scheduler ? p.scheduler : p.driver)=time;
        if(!p.scheduler || !p.driver)return;
        p.live=false;
        const int64_t total=p.driver-p.realtime,queued=p.driver-p.scheduler;
        if(total<0 || total>2000000000LL || queued<0 || queued>total) {++rejected_;return;}
        samples_[next_++ % samples_.size()]={total/1000,queued/1000};++count_;
    }
    uint64_t count() const {return count_;}
    uint64_t rejected() const {return rejected_;}
    int64_t percentile(bool scheduler,int percent) const {
        const size_t n=std::min<uint64_t>(count_,samples_.size());
        if(!n)return -1;
        std::array<int64_t,128> values{};
        for(size_t i=0;i<n;i++)values[i]=scheduler?samples_[i].schedulerToDriverUs:samples_[i].callToDriverUs;
        std::sort(values.begin(),values.begin()+n);
        return values[(n-1)*std::clamp(percent,0,100)/100];
    }
private:
    struct Pending {uint32_t id=0;int64_t realtime=0,monotonic=0,scheduler=0,driver=0;bool live=false;};
    std::array<Pending,256> pending_{};
    std::array<Sample,128> samples_{};
    uint64_t count_=0,rejected_=0,next_=0;
};
}
