// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

// Exact pinned platform ABI, root process only. No global (io=0) parameter call exists here.
JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_OutputPeriodProbe_probe(JNIEnv* env,jclass type,jint io) {
    (void)type;
    if(getuid()!=0 || sizeof(void*)!=8 || io<=0 || io>1000000) return NULL;
    void* base=dlopen("/system/lib64/libaudiobase.so",RTLD_NOW|RTLD_LOCAL);
    void* utils=dlopen("/system/lib64/libutils.so",RTLD_NOW|RTLD_LOCAL);
    jlong v[18];for(int i=0;i<18;i++) v[i]=-1;
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
    jlongArray result=(*env)->NewLongArray(env,18);
    if(result) (*env)->SetLongArrayRegion(env,result,0,18,v);
    return result;
}
