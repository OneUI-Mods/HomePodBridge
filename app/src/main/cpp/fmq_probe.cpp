#include <jni.h>
#include "fmq_mapped_queue.h"
#include <array>
#include <cerrno>
#include "fmq_control.h"
#include "fmq_zero_transfer.h"
#include "zero_pcm_pipe.h"
#include <fcntl.h>
#include <signal.h>
#include "live_capture.h"

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
    jsize fields_count=env->GetArrayLength(grants_array);
    if(count<1 || count>8 || (fields_count!=12 && !(quantum==1 && fields_count==9))) return false;
    std::array<jint,8> raw{};std::array<jlong,12> fields{};
    env->GetIntArrayRegion(fd_array,0,count,raw.data());
    env->GetLongArrayRegion(grants_array,0,fields_count,fields.data());
    if(env->ExceptionCheck()) return false;
    std::array<hp_grantor,4> grants{};
    for(size_t i=0;i<size_t(fields_count/3);i++) {
        if(fields[i*3]<0 || fields[i*3]>=count || fields[i*3+1]<0 || fields[i*3+2]<=0) return false;
        grants[i]={int32_t(fields[i*3]),uint64_t(fields[i*3+1]),uint64_t(fields[i*3+2])};
    }
    return queue.attach(raw.data(),size_t(count),grants.data(),size_t(fields_count/3),quantum,1);
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

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_HalQueueMapping_zeroTransfer(
    JNIEnv* env,jclass,jobjectArray fds,jobjectArray grants,jint frames,jboolean exit_after_input,jboolean output_only) {
    if(env->GetArrayLength(fds)!=6 || env->GetArrayLength(grants)!=6) return nullptr;
    std::array<hp_mapped_queue,6> queues{};
    constexpr size_t quantum[]={8,56,1,8,56,1};
    for(int i=0;i<6;i++) {
        auto queue_fds=static_cast<jintArray>(env->GetObjectArrayElement(fds,i));
        auto queue_grants=static_cast<jlongArray>(env->GetObjectArrayElement(grants,i));
        bool attached=queue_fds && queue_grants && hp_attach_status_queue(env,queue_fds,queue_grants,quantum[i],queues[size_t(i)]);
        if(queue_fds) env->DeleteLocalRef(queue_fds);
        if(queue_grants) env->DeleteLocalRef(queue_grants);
        if(!attached || env->ExceptionCheck()) return nullptr;
    }
    auto report=hp_zero_transfer(queues,frames,exit_after_input==JNI_TRUE,output_only==JNI_TRUE);
    jlong values[]={report.passed ? 1:0,report.frames,report.cycles,report.sent,report.captured,
        report.elapsed_us,report.max_output_us,report.max_input_us,report.max_pending,
        report.error,report.start_flags,report.observed,report.expected};
    auto result=env->NewLongArray(13);
    if(result) env->SetLongArrayRegion(result,0,13,values);
    return result;
}

// Read only the self-UID policy's dedicated virtual input. No output commands or caller PCM.
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_HalQueueMapping_policyInputRead(
    JNIEnv* env,jclass,jobjectArray fds,jobjectArray grants,jboolean forward_zero,jboolean live_capture,jboolean sanitize,jint burst_frames,jboolean event_wait) {
    const bool live=live_capture==JNI_TRUE;
    const int frames=int(burst_frames);
    const bool events=event_wait==JNI_TRUE;
    if((frames!=22 && frames!=44) || (live && frames!=22)) return nullptr;
    const bool forward=forward_zero==JNI_TRUE || live;
    if(forward) {
        int flags=fcntl(STDOUT_FILENO,F_GETFL);
        if(flags<0 || fcntl(STDOUT_FILENO,F_SETFL,flags|O_NONBLOCK)<0) return nullptr;
        signal(SIGPIPE,SIG_IGN); // Isolated worker only; never changes the audio service's signals.
    }
    if(env->GetArrayLength(fds)!=3 || env->GetArrayLength(grants)!=3) return nullptr;
    std::array<hp_mapped_queue,3> queues{};
    constexpr size_t quantum[]={8,56,1};
    for(int i=0;i<3;i++) {
        auto queue_fds=static_cast<jintArray>(env->GetObjectArrayElement(fds,i));
        auto queue_grants=static_cast<jlongArray>(env->GetObjectArrayElement(grants,i));
        bool attached=queue_fds && queue_grants && hp_attach_status_queue(env,queue_fds,queue_grants,quantum[i],queues[size_t(i)]);
        if(queue_fds) env->DeleteLocalRef(queue_fds);
        if(queue_grants) env->DeleteLocalRef(queue_grants);
        if(!attached || env->ExceptionCheck() || !hp_empty(queues[size_t(i)])) return nullptr;
    }
    if(live) {
        auto r=hp_live_capture(queues,sanitize==JNI_TRUE);
        jlong values[]={r.error==0?1:0,r.captured,r.cycles,r.elapsed,r.maximum,r.max_pending,
            r.min_pending==INT64_MAX?-1:r.min_pending,r.error,r.observations?r.sum_pending/r.observations:-1,r.sent,r.idle,r.status_queries,r.captured>0?r.total_input_us/(r.captured/22):0};
        auto result=env->NewLongArray(13);if(result) env->SetLongArrayRegion(result,0,13,values);return result;
    }
    hp_status_reply response{};int64_t duration=0;
    int error=0,cycles=0;int64_t captured=0,maximum=0,max_pending=0,min_pending=INT64_MAX,sum_pending=0;
    int64_t queries=0,total_input_us=0;
    if(!hp_stream_once(queues[0],queues[1],2,0,2,&response,&duration,events)) error=1;
    std::array<uint8_t,176> input{};
    auto begin=std::chrono::steady_clock::now();
    while(error==0 && cycles<(forward ? 30000:4096)) {
        // r_submix reports the requested byte count even after zero-padding a short read.
        // Wait for a complete burst to exist before asking the sole consumer to read it.
        auto available_begin=std::chrono::steady_clock::now();
        while(true) {
            if(events && response.observable.frames-captured>=frames) break;
            ++queries;
            if(!hp_input_position(queues[0],queues[1],&response,events)) {error=7;break;}
            if(response.observable.frames-captured>=frames) break;
            if(std::chrono::steady_clock::now()-available_begin>=std::chrono::milliseconds(50)) {error=8;break;}
            std::this_thread::sleep_for(std::chrono::microseconds(100));
        }
        if(error) break;
        if(!hp_empty(queues[2]) || !hp_stream_once(queues[0],queues[1],3,frames*4,3,&response,&duration,events)) {error=2;break;}
        maximum=std::max(maximum,duration);total_input_us+=duration;
        if(!queues[2].view.transfer(input.data(),size_t(frames*4),false)) {error=3;break;}
        if(std::any_of(input.begin(),input.begin()+frames*4,[](uint8_t value){return value!=0;})) {error=4;break;}
        captured+=frames;++cycles;
        int64_t pending=response.observable.frames-captured;
        if(pending<0) {error=5;break;}
        if(forward) {
            timespec now{};
            if(clock_gettime(CLOCK_MONOTONIC,&now)!=0 || !hp_write_zero_packet(STDOUT_FILENO,
                int64_t(now.tv_sec)*1000000000+now.tv_nsec,pending)) {error=10;break;}
        }
        if(cycles>100) {max_pending=std::max(max_pending,pending);min_pending=std::min(min_pending,pending);sum_pending+=pending;}
        if(std::chrono::steady_clock::now()-begin>=std::chrono::milliseconds(forward ? 10000:1500)) break;
    }
    for(auto& queue:queues) if(!hp_empty(queue)) error=6;
    auto elapsed=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
    if(error==0 && !hp_realtime_frames(captured,elapsed)) error=9;
    jlong values[]={error==0 && cycles>100 ? 1:0,captured,cycles,elapsed,maximum,max_pending,
        min_pending==INT64_MAX ? -1:min_pending,error,cycles>100 ? sum_pending/(cycles-100):-1,queries,cycles?total_input_us/cycles:0};
    auto result=env->NewLongArray(11);
    if(result) env->SetLongArrayRegion(result,0,11,values);
    return result;
}
