// SPDX-License-Identifier: Apache-2.0
// Runs on an emulator against its own synthetic child, never an audio/system process.
#include "../../main/cpp/hal_probe.c"
#include <sys/mman.h>
#include <sys/prctl.h>
#include <pthread.h>
#include <stdatomic.h>
#include <assert.h>
typedef struct { atomic_int ready; atomic_ulong ticks; volatile sig_atomic_t signals; } shared_state;
static shared_state* shared;
static void received(int signal_number) { (void)signal_number; ++shared->signals; }
static void* spin(void* ignored) {
    (void)ignored;atomic_fetch_add(&shared->ready,1);
    uint64_t deadline=monotonic_ns()+10000000000u;
    while (monotonic_ns()<deadline) { atomic_fetch_add(&shared->ticks,1);usleep(100); }
    return NULL;
}
static pid_t target(void) {
    pid_t child=fork();assert(child>=0);
    if (!child) {
        prctl(PR_SET_DUMPABLE,1);prctl(PR_SET_PTRACER,PR_SET_PTRACER_ANY);
        signal(SIGUSR1,received);pthread_t threads[3];
        for (int i=0;i<3;++i) if (pthread_create(&threads[i],NULL,spin,NULL)) _exit(8);
        for (int i=0;i<3;++i) pthread_join(threads[i],NULL);
        _exit(0);
    }
    uint64_t deadline=monotonic_ns()+1000000000u;
    while (atomic_load(&shared->ready)<3 && monotonic_ns()<deadline) usleep(1000);
    assert(atomic_load(&shared->ready)==3);return child;
}
static int advances(void) {
    unsigned long before=atomic_load(&shared->ticks);uint64_t deadline=monotonic_ns()+500000000u;
    while (monotonic_ns()<deadline) { if (atomic_load(&shared->ticks)>before) return 1;usleep(1000); }
    return 0;
}
int main(void) {
    shared=mmap(NULL,sizeof(*shared),PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);
    assert(shared!=MAP_FAILED);memset(shared,0,sizeof(*shared));pid_t child=target();
    for (int round=0;round<3;++round) {
        task tasks[256];size_t count=0;
        const char* error=freeze(child,tasks,&count,monotonic_ns()+25000000u);
        if (error) { thaw(tasks,count);kill(child,SIGTERM);waitpid(child,NULL,0);fprintf(stderr,"freeze test refused: %s\n",error);return 2; }
        assert(count==4);unsigned long before=atomic_load(&shared->ticks);usleep(1000);
        assert(atomic_load(&shared->ticks)==before);
        if (!round) assert(kill(child,SIGUSR1)==0);
        assert(thaw(tasks,count));assert(advances());
    }
    assert(shared->signals==1);
    int channel[2];assert(pipe(channel)==0);pid_t tracer=fork();assert(tracer>=0);
    if (!tracer) {
        close(channel[0]);task tasks[256];size_t count=0;
        const char* error=freeze(child,tasks,&count,monotonic_ns()+25000000u);
        char value=error?'E':'Y';assert(write(channel[1],&value,1)==1);
        if (error) { thaw(tasks,count);_exit(3); }
        for (;;) pause();
    }
    close(channel[1]);char value=0;assert(read(channel[0],&value,1)==1);close(channel[0]);
    assert(value=='Y');assert(kill(tracer,SIGKILL)==0);assert(waitpid(tracer,NULL,0)==tracer);
    assert(advances()); // No EXITKILL, no surviving group stop after tracer death.
    assert(kill(child,SIGTERM)==0);assert(waitpid(child,NULL,0)==child);
    puts("PASS: all threads freeze/resume, queued signal preserved, tracer death resumes target");
    return 0;
}
