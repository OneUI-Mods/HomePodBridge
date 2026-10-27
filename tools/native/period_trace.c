// SPDX-License-Identifier: Apache-2.0
// Closed, temporary arm64 getter experiment. No instruction/data memory is written.
#define _GNU_SOURCE
#include <asm/ptrace.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

typedef struct { pid_t tid; int stopped, saved, signal; struct user_hwdebug_state original; } task;
static task tasks[128];static size_t task_count;static uint64_t getter_address;
static uint64_t now_us(void) { struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000+t.tv_nsec/1000; }
static void pause_briefly(void) { struct timespec t={0,100000};nanosleep(&t,NULL); }
static int regset(pid_t tid,int set,unsigned note,void* data,size_t size) {
    struct iovec io={data,size};return ptrace(set?PTRACE_SETREGSET:PTRACE_GETREGSET,tid,(void*)(uintptr_t)note,&io)==0;
}
static task* lookup(pid_t tid) { for(size_t i=0;i<task_count;i++)if(tasks[i].tid==tid)return &tasks[i];return NULL; }
static int stopped(task* t,uint64_t deadline) {
    while(now_us()<deadline) {
        int status;pid_t got=waitpid(t->tid,&status,__WALL|WNOHANG);
        if(got<0)return 0;
        if(got==t->tid) {
            if(!WIFSTOPPED(status)) {t->tid=0;return 0;}
            t->stopped=1;
            if(((unsigned)status>>16)!=PTRACE_EVENT_STOP || WSTOPSIG(status)!=SIGTRAP) {
                t->signal=WSTOPSIG(status);return 0;
            }
            return 1;
        }
        pause_briefly();
    }
    return 0;
}
static int install(task* t,uint64_t address) {
    if(!regset(t->tid,0,NT_ARM_HW_BREAK,&t->original,sizeof(t->original)))return 0;
    t->saved=1;
    unsigned slots=t->original.dbg_info&255;
    if(slots<1 || slots>16)return 0;
    for(unsigned i=0;i<slots;i++)if(t->original.dbg_regs[i].ctrl&1)return 0;
    struct user_hwdebug_state next=t->original;
    next.dbg_regs[0].addr=address;next.dbg_regs[0].ctrl=0x1e5;
    // Only one slot: writing every ABI slot can allocate unsupported hardware breakpoints.
    return regset(t->tid,1,NT_ARM_HW_BREAK,&next,24);
}
static int resume(task* t) {
    if(ptrace(PTRACE_CONT,t->tid,NULL,(void*)(intptr_t)t->signal)!=0)return 0;
    t->stopped=0;t->signal=0;return 1;
}
static int detach_all(void) {
    int clean=1;
    for(size_t i=0;i<task_count;i++) {
        task* t=&tasks[i];if(!t->tid)continue;
        if(!t->stopped) {
            if(ptrace(PTRACE_INTERRUPT,t->tid,NULL,NULL)!=0 && errno==ESRCH){t->tid=0;continue;}
            if(!stopped(t,now_us()+200000) && !t->stopped){if(t->tid)clean=0;continue;}
        }
        if(t->signal==SIGTRAP) {
            siginfo_t info;struct user_pt_regs regs;
            if(ptrace(PTRACE_GETSIGINFO,t->tid,NULL,&info)==0 && info.si_code==TRAP_HWBKPT
                && regset(t->tid,0,NT_PRSTATUS,&regs,sizeof(regs)) && regs.pc==getter_address) {
                regs.pc=getter_address+4;
                if(!regset(t->tid,1,NT_PRSTATUS,&regs,sizeof(regs)))clean=0;
                else t->signal=0;
            }
        }
        if(t->saved && !regset(t->tid,1,NT_ARM_HW_BREAK,&t->original,24))clean=0;
        if(ptrace(PTRACE_DETACH,t->tid,NULL,(void*)(intptr_t)t->signal)!=0 && errno!=ESRCH)clean=0;
        t->tid=0;
    }
    return clean;
}
static int attach(pid_t pid,uint64_t address) {
    char path[64];snprintf(path,sizeof(path),"/proc/%d/task",pid);
    DIR* dir=opendir(path);if(!dir)return 0;
    struct dirent* e;int ok=1;
    while((e=readdir(dir))) {
        char* end;long tid=strtol(e->d_name,&end,10);if(*end || tid<=0)continue;
        if(task_count==128){ok=0;break;}
        if(ptrace(PTRACE_SEIZE,(pid_t)tid,NULL,(void*)PTRACE_O_TRACECLONE)!=0){ok=0;break;}
        task* t=&tasks[task_count++];t->tid=(pid_t)tid;
        if(ptrace(PTRACE_INTERRUPT,t->tid,NULL,NULL)!=0){ok=0;break;}
    }
    closedir(dir);
    uint64_t deadline=now_us()+250000;
    for(size_t i=0;ok && i<task_count;i++)if(!stopped(&tasks[i],deadline) || !install(&tasks[i],address))ok=0;
    for(size_t i=0;ok && i<task_count;i++)if(!resume(&tasks[i]))ok=0;
    return ok;
}
static int decision(unsigned sequence) {
    uint64_t deadline=now_us()+200000;char line[40];size_t used=0;
    while(now_us()<deadline) {
        struct pollfd fd={STDIN_FILENO,POLLIN,0};
        if(poll(&fd,1,1)<0 && errno!=EINTR)return 0;
        if(fd.revents&(POLLHUP|POLLERR))return 0;
        if(!(fd.revents&POLLIN))continue;
        char c;if(read(STDIN_FILENO,&c,1)!=1)return 0;
        if(c=='\n') {
            line[used]=0;unsigned got=0;char action=0,extra=0;
            if(sscanf(line,"%c %u%c",&action,&got,&extra)==2 && got==sequence)return action=='A';
            used=0;
        } else if(used+1<sizeof(line))line[used++]=c;
        else return 0;
    }
    return 0;
}
static int run(pid_t pid,uint64_t address,int selftest) {
    static const uint32_t expected[]={0xd503245f,0x528002a0,0xd65f03c0};
    char path[64];snprintf(path,sizeof(path),"/proc/%d/mem",pid);
    int memory=open(path,O_RDONLY|O_CLOEXEC);uint32_t code[3];
    if(memory<0 || pread(memory,code,sizeof(code),(off_t)address)!=sizeof(code) || memcmp(code,expected,sizeof(code))) {
        if(memory>=0)close(memory);puts("REFUSED getter code");return 1;
    }
    getter_address=address;
    alarm(15); // No EXITKILL. Kernel detaches and clears hardware debug state on tracer death.
    if(!attach(pid,address)) {int clean=detach_all();close(memory);printf("REFUSED attach errno=%d clean=%d\n",errno,clean);return 1;}
    printf("READY %zu\n",task_count);fflush(stdout);
    unsigned hits=0,changed=0;uint64_t deadline=now_us()+10000000;int ok=1;
    while(now_us()<deadline && hits<(selftest?3u:64u)) {
        struct pollfd control={STDIN_FILENO,POLLIN,0};poll(&control,1,0);
        if(!selftest && control.revents)break;
        int status=0;pid_t tid=waitpid(-1,&status,__WALL|WNOHANG);
        if(tid==0){pause_briefly();continue;}
        if(tid<0){if(errno==ECHILD)break;ok=0;break;}
        task* t=lookup(tid);
        if(!t) {if(task_count==128){ok=0;break;}t=&tasks[task_count++];memset(t,0,sizeof(*t));t->tid=tid;}
        if(!WIFSTOPPED(status)){t->tid=0;continue;}
        t->stopped=1;unsigned event=(unsigned)status>>16;int sig=WSTOPSIG(status);
        if(event==PTRACE_EVENT_CLONE) {
            unsigned long child=0;if(ptrace(PTRACE_GETEVENTMSG,tid,NULL,&child)!=0 || !child){ok=0;break;}
            if(!lookup((pid_t)child)){if(task_count==128){ok=0;break;}tasks[task_count++]=(task){.tid=(pid_t)child};}
        } else if((event==PTRACE_EVENT_STOP && (sig==SIGTRAP || sig==SIGSTOP)) || (!t->saved && sig==SIGSTOP)) {
            if(!t->saved && !install(t,address)){ok=0;break;}
        } else if(sig==SIGTRAP && event==0) {
            siginfo_t info;struct user_pt_regs regs;
            if(ptrace(PTRACE_GETSIGINFO,tid,NULL,&info)!=0 || !regset(tid,0,NT_PRSTATUS,&regs,sizeof(regs))){ok=0;break;}
            if(info.si_code==TRAP_HWBKPT && regs.pc==address) {
                int32_t config[2]={0};uint64_t pointer=regs.regs[1]&UINT64_C(0x00ffffffffffffff);
                int valid=pread(memory,config,sizeof(config),(off_t)pointer)==sizeof(config);
                ++hits;printf("HIT %u %d %d\n",hits,valid?config[0]:0,valid?config[1]:0);fflush(stdout);
                int accept=selftest ? (valid && config[0]==123 && config[1]==3) : decision(hits);
                if(accept && valid && config[0]>0 && (config[1]==3 || config[1]==4)) {
                    regs.regs[0]=2;regs.pc=address+8;++changed;
                } else regs.pc=address+4; // Original BTI is a no-op; execute the original mov/ret.
                if(!regset(tid,1,NT_PRSTATUS,&regs,sizeof(regs))){ok=0;break;}
            } else t->signal=sig;
        } else t->signal=sig;
        if(!resume(t)){ok=0;break;}
    }
    int clean=detach_all();alarm(0);close(memory);
    printf("DONE hits=%u changed=%u clean=%d ok=%d\n",hits,changed,clean,ok);fflush(stdout);
    return ok && clean ? 0:1;
}
__attribute__((naked,noinline)) static int dummy(void* module __attribute__((unused)),const void* config __attribute__((unused))) {
    __asm__("bti c\nmov w0,#21\nret");
}
static void* dummy_worker(void* unused) {
    (void)unused;int config[2]={123,3};return (void*)(intptr_t)dummy(NULL,config);
}
static volatile sig_atomic_t got_signal;
static void usr1(int signal) { (void)signal;++got_signal; }
static int selftest(void) {
    int ready[2],go[2];if(pipe(ready) || pipe(go))return 1;
    pid_t child=fork();if(child<0)return 1;
    if(child==0) {
        close(ready[0]);close(go[1]);char x='r';write(ready[1],&x,1);read(go[0],&x,1);
        int a[2]={123,3},b[2]={124,3};
        signal(SIGUSR1,usr1);raise(SIGUSR1);
        int first=dummy(NULL,a),second=dummy(NULL,b);
        pthread_t worker;void* worker_result=NULL;
        if(pthread_create(&worker,NULL,dummy_worker,NULL)!=0)_exit(4);
        pthread_join(worker,&worker_result);
        read(go[0],&x,1);int restored=dummy(NULL,a);
        printf("CHILD first=%d foreign=%d clone=%ld restored=%d signal=%d\n",first,second,(long)worker_result,restored,got_signal);fflush(stdout);
        _exit(first==2 && second==21 && (intptr_t)worker_result==2 && restored==21 && got_signal==1?0:3);
    }
    close(ready[1]);close(go[0]);char x;read(ready[0],&x,1);
    // Let the child run only after all hardware state is installed.
    pid_t notifier=fork();if(notifier==0){usleep(100000);write(go[1],"g",1);_exit(0);}
    int result=run(child,(uint64_t)(uintptr_t)dummy,1);write(go[1],"r",1);int status=0;pid_t waited=waitpid(child,&status,0);waitpid(notifier,NULL,0);
    return result || waited!=child || !WIFEXITED(status) || WEXITSTATUS(status)!=0;
}
int main(int argc,char** argv) {
    setvbuf(stdout,NULL,_IOLBF,0);
    if(argc==2 && !strcmp(argv[1],"selftest"))return selftest();
    if(getuid()!=0 || argc!=3)return 2;
    char* end;long pid=strtol(argv[1],&end,10);if(*end || pid<=1 || pid>9999999)return 2;
    uint64_t base=strtoull(argv[2],&end,16);if(*end || base<65536 || base%4096)return 2;
    char path[64],text[8192];snprintf(path,sizeof(path),"/proc/%ld/status",pid);
    FILE* f=fopen(path,"re");if(!f)return 2;size_t n=fread(text,1,sizeof(text)-1,f);fclose(f);text[n]=0;
    if(!strstr(text,"\nUid:\t1041\t1041\t1041\t1041\n"))return 2;
    snprintf(path,sizeof(path),"/proc/%ld/maps",pid);f=fopen(path,"re");if(!f)return 2;int mapped=0;
    while(fgets(text,sizeof(text),f)) {unsigned long long a,offset;char permissions[5];
        if(sscanf(text,"%llx-%*x %4s %llx",&a,permissions,&offset)==3 && a==base && offset==0
            && strstr(text,"/vendor/lib64/libaudioserviceexampleimpl.so"))mapped=1;}
    fclose(f);if(!mapped)return 2;
    return run((pid_t)pid,base+0x150518,0);
}
