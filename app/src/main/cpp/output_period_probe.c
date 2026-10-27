// SPDX-License-Identifier: Apache-2.0
#ifndef HP_OUTPUT_PERIOD_CLI
#include <jni.h>
#else
#include <stdlib.h>
typedef long long jlong;
#endif
#include <dlfcn.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <android/dlext.h>

static void* platform_library(const char* path) {
    void* library=dlopen(path,RTLD_NOW|RTLD_LOCAL);
    if(library) return library;
    // app_process already owns the platform namespace. Do not create or alter namespaces.
    typedef struct android_namespace_t*(*Namespace)(const char*);
    Namespace exported=(Namespace)dlsym(RTLD_DEFAULT,"android_get_exported_namespace");
    if(!exported) return NULL;
    const char* names[]={"platform","default"};
    for(size_t i=0;i<2;i++) {
        struct android_namespace_t* space=exported(names[i]);
        if(!space) continue;
        android_dlextinfo info={.flags=ANDROID_DLEXT_USE_NAMESPACE,.library_namespace=space};
        library=android_dlopen_ext(path,RTLD_NOW|RTLD_LOCAL,&info);
        if(library) return library;
    }
    return NULL;
}

// Exact pinned platform ABI, root process only. No global (io=0) parameter call exists here.
static void output_period(int io,jlong v[20]) {
    for(int i=0;i<20;i++) v[i]=-1;
    if(getuid()!=0 || sizeof(void*)!=8 || io<=0 || io>1000000) return;
    void* base=platform_library("/system/lib64/libaudiobase.so");
    void* utils=platform_library("/system/lib64/libutils.so");
    v[18]=(base?1:0)|(utils?2:0);
    if(base && utils) {
        typedef int(*Count)(int,size_t*);
        typedef int(*Rate)(int,unsigned*);
        typedef int(*Set)(int,const void*);
        typedef void(*Construct)(void*,const char*);
        typedef void(*Destroy)(void*);
        Count count=(Count)dlsym(base,"_ZN7android11AudioSystem13getFrameCountEiPm");
        Count hal=(Count)dlsym(base,"_ZN7android11AudioSystem16getFrameCountHALEiPm");
        Rate rate=(Rate)dlsym(base,"_ZN7android11AudioSystem15getSamplingRateEiPj");
        Set set=(Set)dlsym(base,"_ZN7android11AudioSystem13setParametersEiRKNS_7String8E");
        Construct construct=(Construct)dlsym(utils,"_ZN7android7String8C1EPKc");
        Destroy destroy=(Destroy)dlsym(utils,"_ZN7android7String8D1Ev");
        v[19]=(count?1:0)|(hal?2:0)|(rate?4:0)|(set?8:0)|(construct?16:0)|(destroy?32:0);
        if(count && hal && rate && set && construct && destroy) {
            #define READ(start) do { \
                size_t frames=0,halFrames=0;unsigned hz=0; \
                v[start]=count(io,&frames);v[start+1]=(jlong)frames; \
                v[start+2]=hal(io,&halFrames);v[start+3]=(jlong)halFrames; \
                v[start+4]=rate(io,&hz);v[start+5]=(jlong)hz; \
            } while(0)
            #define CHANGE(frames,status) do { \
                char text[48];snprintf(text,sizeof(text),"frame_count=%zu",(size_t)(frames)); \
                _Alignas(8) unsigned char string[8]={0}; \
                construct(string,text);status=set(io,string);destroy(string); \
            } while(0)
            READ(0);
            if(v[0]==0 && v[2]==0 && v[4]==0 && v[1]>=88 && v[1]<=8192
                && v[3]>=88 && v[3]<=8192 && v[5]==44100) {
                CHANGE(88,v[6]);
                // Always restore the original HAL frame count, including a no-op or refused call.
                READ(7);
                CHANGE(v[3],v[13]);
                size_t frames=0,halFrames=0;
                v[14]=count(io,&frames);v[15]=frames;
                v[16]=hal(io,&halFrames);v[17]=halFrames;
            }
        }
    }
    if(utils) dlclose(utils);
    if(base) dlclose(base);
}
#ifndef HP_OUTPUT_PERIOD_CLI
JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_OutputPeriodProbe_probe(JNIEnv* env,jclass type,jint io) {
    (void)type;jlong v[20];output_period(io,v);
    jlongArray result=(*env)->NewLongArray(env,20);
    if(result) (*env)->SetLongArrayRegion(env,result,0,20,v);
    return result;
}

#else
int main(int argc,char** argv) {
    if(getuid()!=0 || argc!=2 || !argv[1][0]) return 2;
    char* end=NULL;long io=strtol(argv[1],&end,10);
    if(*end || io<=0 || io>1000000) return 2;
    jlong values[20];output_period((int)io,values);
    printf("[");for(int i=0;i<20;i++) printf("%s%lld",i?",":"",values[i]);puts("]");
    return 0;
}
#endif
