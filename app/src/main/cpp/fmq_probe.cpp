#include <jni.h>
#include "fmq_mapped_queue.h"
#include <array>

// Only map and inspect queue counters. No command/data writes, index resets or notifications.
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_HalQueueMapping_inspect(
    JNIEnv* env, jclass, jintArray fd_array, jlongArray grants_array, jint quantum, jint flags) {
    static_assert(std::atomic<uint64_t>::is_always_lock_free);
    jsize fds=env->GetArrayLength(fd_array), fields=env->GetArrayLength(grants_array);
    if(fds<1 || fds>8 || (fields!=9 && fields!=12) || quantum<=0 || flags!=1) return nullptr;
    std::array<jint,8> raw{};std::array<jlong,12> values{};
    env->GetIntArrayRegion(fd_array,0,fds,raw.data());
    env->GetLongArrayRegion(grants_array,0,fields,values.data());
    if(env->ExceptionCheck()) return nullptr;
    std::array<hp_grantor,4> grants{};
    for(int i=0;i<fields/3;++i) {
        if(values[i*3]<0 || values[i*3]>=fds || values[i*3+1]<0 || values[i*3+2]<=0) return nullptr;
        grants[i]={int32_t(values[i*3]),uint64_t(values[i*3+1]),uint64_t(values[i*3+2])};
    }
    hp_mapped_queue queue;
    if(!queue.attach(raw.data(),size_t(fds),grants.data(),size_t(fields/3),size_t(quantum),uint32_t(flags))) return nullptr;
    uint64_t read=queue.view.read->load(std::memory_order_acquire);
    uint64_t write=queue.view.write->load(std::memory_order_acquire);
    if(read!=0 || write!=0) return nullptr; // This probe supports unstarted queues only.
    jlong result[]={jlong(queue.view.capacity),jlong(read),jlong(write)};
    jlongArray output=env->NewLongArray(3);
    if(output) env->SetLongArrayRegion(output,0,3,result);
    return output;
}
