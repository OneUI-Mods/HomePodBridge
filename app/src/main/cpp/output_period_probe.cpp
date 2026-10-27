// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <array>
#include <cstddef>
#include <cstdio>

// Exact pinned platform ABI, root process only. No global (io=0) parameter call exists here.
extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_ryuya_homepodbridge_halprobe_OutputPeriodProbe_probe(JNIEnv* env,jclass,jint io) {
    if(getuid()!=0 || sizeof(void*)!=8 || io<=0 || io>1000000) return nullptr;
    void* base=dlopen("/system/lib64/libaudiobase.so",RTLD_NOW|RTLD_LOCAL);
    void* utils=dlopen("/system/lib64/libutils.so",RTLD_NOW|RTLD_LOCAL);
    std::array<jlong,18> v{};v.fill(-1);
    if(base && utils) {
        using Count=int(*)(int,size_t*);
        using Rate=int(*)(int,unsigned*);
        using Set=int(*)(int,const void*);
        using Construct=void(*)(void*,const char*);
        using Destroy=void(*)(void*);
        auto count=reinterpret_cast<Count>(dlsym(base,"_ZN7android11AudioSystem13getFrameCountEiPm"));
        auto hal=reinterpret_cast<Count>(dlsym(base,"_ZN7android11AudioSystem16getFrameCountHALEiPm"));
        auto rate=reinterpret_cast<Rate>(dlsym(base,"_ZN7android11AudioSystem15getSamplingRateEiPj"));
        auto set=reinterpret_cast<Set>(dlsym(base,"_ZN7android11AudioSystem13setParametersEiRKNS_7String8E"));
        auto construct=reinterpret_cast<Construct>(dlsym(utils,"_ZN7android7String8C1EPKc"));
        auto destroy=reinterpret_cast<Destroy>(dlsym(utils,"_ZN7android7String8D1Ev"));
        if(count && hal && rate && set && construct && destroy) {
            auto read=[&](int start) {
                size_t frames=0,halFrames=0;unsigned hz=0;
                v[start]=count(io,&frames);v[start+1]=frames;
                v[start+2]=hal(io,&halFrames);v[start+3]=halFrames;
                v[start+4]=rate(io,&hz);v[start+5]=hz;
            };
            auto change=[&](size_t frames) {
                char text[48];snprintf(text,sizeof(text),"frame_count=%zu",frames);
                alignas(8) std::array<std::byte,8> string{};
                construct(string.data(),text);
                int status=set(io,string.data());
                destroy(string.data());return status;
            };
            read(0);
            if(v[0]==0 && v[2]==0 && v[4]==0 && v[1]>=88 && v[1]<=8192
                && v[3]>=88 && v[3]<=8192 && v[5]==44100) {
                v[6]=change(88);
                // Always restore the original HAL frame count, including a no-op or refused call.
                read(7);
                v[13]=change(size_t(v[3]));
                size_t frames=0,halFrames=0;
                v[14]=count(io,&frames);v[15]=frames;
                v[16]=hal(io,&halFrames);v[17]=halFrames;
            }
        }
    }
    if(utils) dlclose(utils);
    if(base) dlclose(base);
    auto result=env->NewLongArray(v.size());
    if(result) env->SetLongArrayRegion(result,0,v.size(),v.data());
    return result;
}
