// SPDX-License-Identifier: Apache-2.0
#define _GNU_SOURCE
#include <jni.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <signal.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "hal_owned_layout.h"

typedef struct { pid_t tid; int stopped, deliver_signal; } task;
typedef struct { uint64_t begin,end; int writable; } region;
typedef struct { int fd; region maps[4096]; size_t count; unsigned writes; } memory;
static uint64_t monotonic_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);
    return (uint64_t)ts.tv_sec*1000000000u+(uint64_t)ts.tv_nsec;
}
static int read_memory(void* opaque, uint64_t address, void* output, size_t length) {
    memory* mem=opaque; address=hp_pointer(address);
    if (address<65536 || length>4096 || address+length<address) return 0;
    for (size_t i=0;i<mem->count;++i) if (address>=mem->maps[i].begin && address+length<=mem->maps[i].end)
        return pread(mem->fd,output,length,(off_t)address)==(ssize_t)length;
    return 0;
}
static int write_memory(void* opaque,uint64_t address,const void* input,size_t length) {
    memory* mem=opaque;
    if (length!=8 || address%8) return 0;
    for (size_t i=0;i<mem->count;++i) if (mem->maps[i].writable && address>=mem->maps[i].begin
        && address+length<=mem->maps[i].end) {
            ++mem->writes;
            return pwrite(mem->fd,input,length,(off_t)address)==(ssize_t)length;
        }
    return 0;
}
static int load_maps(memory* mem,pid_t pid) {
    char path[64],line[1024]; snprintf(path,sizeof(path),"/proc/%d/maps",pid);
    FILE* file=fopen(path,"re"); if (!file) return 0;
    while (fgets(line,sizeof(line),file)) {
        unsigned long long begin,end; char permissions[5];
        if (sscanf(line,"%llx-%llx %4s",&begin,&end,permissions)!=3) continue;
        if (permissions[0]!='r') continue;
        if (mem->count==4096) { fclose(file); return 0; }
        mem->maps[mem->count++]=(region){begin,end,permissions[1]=='w'};
    }
    fclose(file); return 1;
}
static const char* freeze(pid_t pid,task* tasks,size_t* count,uint64_t deadline) {
    char path[64];snprintf(path,sizeof(path),"/proc/%d/task",pid);
    for (int round=0;round<4;++round) {
        size_t old=*count;
        DIR* directory=opendir(path); if (!directory) return "task inventory";
        struct dirent* entry;
        while ((entry=readdir(directory))) {
            char* end; long value=strtol(entry->d_name,&end,10); if (*end || value<=0) continue;
            int known=0;for (size_t i=0;i<*count;++i) if (tasks[i].tid==value) known=1;
            if (known) continue;
            if (*count==256 || monotonic_ns()>deadline) { closedir(directory); return "freeze bound"; }
            if (ptrace(PTRACE_SEIZE,(pid_t)value,NULL,NULL)!=0) { closedir(directory); return "seize refused"; }
            tasks[(*count)++]=(task){(pid_t)value,0,0};
            if (ptrace(PTRACE_INTERRUPT,(pid_t)value,NULL,NULL)!=0) { closedir(directory); return "interrupt refused"; }
        }
        closedir(directory);
        for (size_t i=0;i<*count;++i) if (!tasks[i].stopped) {
            for (;;) {
                int status=0;pid_t got=waitpid(tasks[i].tid,&status,__WALL|WNOHANG);
                if (got==tasks[i].tid) {
                    if (!WIFSTOPPED(status)) return "task exited during freeze";
                    tasks[i].stopped=1;
                    // SEIZE + INTERRUPT creates a ptrace stop, not an enduring SIGSTOP group stop.
                    if (((unsigned)status>>16)!=PTRACE_EVENT_STOP || WSTOPSIG(status)!=SIGTRAP) {
                        tasks[i].deliver_signal=WSTOPSIG(status);
                        return "unexpected pending signal";
                    }
                    break;
                }
                if (got<0 || monotonic_ns()>deadline) return "freeze timeout";
                struct timespec pause={0,100000};nanosleep(&pause,NULL);
            }
        }
        if (round>0 && *count==old) return NULL;
    }
    return "task inventory unstable";
}

static int thaw(task* tasks,size_t count) {
    int detached=1;
    for (size_t i=count;i>0;--i) {
        task* t=&tasks[i-1];
        if (!t->stopped) {
            // Resolve a pending interrupt before detaching; the hard timeout still applies.
            (void)ptrace(PTRACE_INTERRUPT,t->tid,NULL,NULL);
            int status;
            if (waitpid(t->tid,&status,__WALL)>0 && WIFSTOPPED(status)) {
                t->stopped=1;
                if (((unsigned)status>>16)!=PTRACE_EVENT_STOP || WSTOPSIG(status)!=SIGTRAP)
                    t->deliver_signal=WSTOPSIG(status);
            }
        }
        if (t->stopped && ptrace(PTRACE_DETACH,t->tid,NULL,(void*)(intptr_t)t->deliver_signal)!=0 && errno!=ESRCH) detached=0;
    }
    return detached;
}

JNIEXPORT jstring JNICALL Java_dev_ryuya_homepodbridge_HalRootProbe_freezeCheck(
    JNIEnv* env,jclass type,jint pid,jlong module,jlong mono,jstring registration,jint operation) {
    (void)type;
    int from=0,to=0,window=0,valid=operation>=0 && operation<=16;
    const int before[]={0,2587,1856,2587,928,2587,464,2587,800,2587,752,2587,768,2587,96,4096,192};
    const int after[]={0,1856,2587,928,2587,464,2587,800,2587,752,2587,768,2587,96,2587,192,4096};
    if(valid){from=before[operation];to=after[operation];window=operation>=15;}
    else if(operation>=10000 && operation<=10800 && hp_manual_reserve(operation-10000)){from=2587;to=operation-10000;valid=1;}
    else if(operation>=20000 && operation<=20800 && hp_manual_reserve(operation-20000)){from=operation-20000;to=2587;valid=1;}
    else if(operation>=30000 && operation<=31856 && hp_latency_window(operation-30000)){from=4096;to=operation-30000;window=1;valid=1;}
    else if(operation>=40000 && operation<=41856 && hp_latency_window(operation-40000)){from=operation-40000;to=4096;window=1;valid=1;}
    if (getuid()!=0 || pid<=1 || !valid) return (*env)->NewStringUTF(env,"HAL operation unavailable: invalid context");
    const char* address=(*env)->GetStringUTFChars(env,registration,NULL);
    if (!address) return NULL;
    memory mem={.fd=-1}; task tasks[256];size_t count=0;uint64_t begin=monotonic_ns();
    const char* error=NULL;hp_owned_pipe owned={0};char path[64],report[256];
    snprintf(path,sizeof(path),"/proc/%d/mem",pid);mem.fd=open(path,(operation?O_RDWR:O_RDONLY)|O_CLOEXEC);
    if (mem.fd<0) error="read-only memory open refused";
    else if (!load_maps(&mem,pid)) error="bounded mapping inventory unavailable";
    // No EXITKILL: if this short-lived helper dies, the kernel releases its ptrace stops.
    // A hard timeout also prevents a stalled probe from retaining traced service threads.
    if (!error) {
        signal(SIGALRM,SIG_DFL);
        sigset_t alarm_set;sigemptyset(&alarm_set);sigaddset(&alarm_set,SIGALRM);
        sigprocmask(SIG_UNBLOCK,&alarm_set,NULL);alarm(1);
        error=freeze(pid,tasks,&count,begin+25000000u);
        if (!error) error=hp_find_owned(read_memory,&mem,(uint64_t)module,(uint64_t)mono,address,&owned);
        if (!error && monotonic_ns()>begin+25000000u) error="exclusive interval exceeded";
        if (!error && window) error=hp_change_reported_frames(read_memory,write_memory,&mem,&owned,1,from,to);
        if (!error && operation>0 && !window) error=hp_change_reserve(read_memory,write_memory,&mem,&owned,1,
            from,to);
    }
    int detached=thaw(tasks,count);
    if (!detached) raise(SIGALRM);
    alarm(0);if (mem.fd>=0) close(mem.fd);
    uint64_t elapsed=(monotonic_ns()-begin)/1000;
    if (!detached) error="detach incomplete";
    if (error) snprintf(report,sizeof(report),"Owned HAL operation refused: %s; maps=%zu tasks=%zu elapsedUs=%llu writeAttempts=%u",error,mem.count,count,(unsigned long long)elapsed,mem.writes);
    else if (window) snprintf(report,sizeof(report),"Owned latency window changed: %d -> %d; exact field verified",from,to);
    else if (operation) snprintf(report,sizeof(report),"Owned reserve changed: %d -> %d; tasks=%zu elapsedUs=%llu writeAttempts=%u; exact field verified",from,to,count,(unsigned long long)elapsed,mem.writes);
    else snprintf(report,sizeof(report),"Read-only freeze check passed: tasks=%zu elapsedUs=%llu targetFillFrames=%llu; no memory writes",count,(unsigned long long)elapsed,(unsigned long long)owned.target);
    (*env)->ReleaseStringUTFChars(env,registration,address);
    return (*env)->NewStringUTF(env,report);
}
