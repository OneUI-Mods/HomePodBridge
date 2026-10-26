#include <jni.h>
#include "fmq_mapped_queue.h"
#include <array>
#include <cerrno>
#include "fmq_control.h"

// This entry point only maps/inspects counters. It never changes shared memory.
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
    bool attached=queue.attach(raw.data(),size_t(fds),grants.data(),size_t(fields/3),size_t(quantum),uint32_t(flags));
    int attach_errno=errno;
    uint64_t read=attached ? queue.view.read->load(std::memory_order_acquire):0;
    uint64_t write=attached ? queue.view.write->load(std::memory_order_acquire):0;
    // Empty queues need not have an origin of zero. Never rebase shared counters.
    if(attached && (read!=write || read%uint64_t(quantum))) return nullptr;
    jlong result[]={attached ? jlong(queue.view.capacity):-1,
        attached ? jlong(read):jlong(ASharedMemory_getSize(raw[0])),
        attached ? jlong(write):jlong(attach_errno)};
    jlongArray output=env->NewLongArray(3);
    if(output) env->SetLongArrayRegion(output,0,3,result);
    return output;
}

static bool hp_attach_status_queue(JNIEnv* env,jintArray fd_array,jlongArray grants_array,
                                   size_t quantum,hp_mapped_queue& queue) {
    jsize count=env->GetArrayLength(fd_array);
    if(count<1 || count>8 || env->GetArrayLength(grants_array)!=12) return false;
    std::array<jint,8> raw{};std::array<jlong,12> fields{};
    env->GetIntArrayRegion(fd_array,0,count,raw.data());
    env->GetLongArrayRegion(grants_array,0,12,fields.data());
    if(env->ExceptionCheck()) return false;
    std::array<hp_grantor,4> grants{};
    for(size_t i=0;i<4;i++) {
        if(fields[i*3]<0 || fields[i*3]>=count || fields[i*3+1]<0 || fields[i*3+2]<=0) return false;
        grants[i]={int32_t(fields[i*3]),uint64_t(fields[i*3+1]),uint64_t(fields[i*3+2])};
    }
    return queue.attach(raw.data(),size_t(count),grants.data(),4,quantum,1);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_HalQueueMapping_queryStandby(
    JNIEnv* env,jclass,jintArray command_fds,jlongArray command_grants,
    jintArray reply_fds,jlongArray reply_grants) {
    hp_mapped_queue command,reply;
    if(!hp_attach_status_queue(env,command_fds,command_grants,8,command)
        || !hp_attach_status_queue(env,reply_fds,reply_grants,56,reply)) return nullptr;
    hp_status_reply status{};int64_t duration=0;
    if(!hp_query_standby(command,reply,&status,&duration)) return nullptr;
    jlong values[]={duration,status.state,status.bytes,status.xrun_frames};
    jlongArray result=env->NewLongArray(4);
    if(result) env->SetLongArrayRegion(result,0,4,values);
    return result;
}

// Deliberately end only this isolated worker after one non-audio status dispatch.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_ryuya_homepodbridge_halprobe_HalQueueMapping_exitAfterStatus(
    JNIEnv* env,jclass,jintArray command_fds,jlongArray command_grants,
    jintArray reply_fds,jlongArray reply_grants) {
    hp_mapped_queue command,reply;
    if(!hp_attach_status_queue(env,command_fds,command_grants,8,command)
        || !hp_attach_status_queue(env,reply_fds,reply_grants,56,reply)
        || !hp_dispatch_status(command,reply)) return JNI_FALSE;
    _exit(74);
}
