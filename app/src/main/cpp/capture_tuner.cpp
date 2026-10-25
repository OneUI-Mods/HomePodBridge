// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdint>

extern "C" JNIEXPORT jint JNICALL
Java_dev_ryuya_homepodbridge_RootCaptureTuner_requestFrames(JNIEnv*, jclass, jlong recorder, jint frames) {
    if (recorder == 0 || frames < 16 || frames > 256) return -1001;
    // Resolve the platform's own implementation. Unavailable private APIs are a no-op.
    void* client = dlopen("libaudioclient.so", RTLD_NOW | RTLD_LOCAL);
    void* utils = dlopen("libutils.so", RTLD_NOW | RTLD_LOCAL);
    if (!client || !utils) {
        if (client) dlclose(client);
        if (utils) dlclose(utils);
        return -1002;
    }
    using Construct = void (*)(void*, const char*);
    using Destroy = void (*)(void*);
    using Set = int (*)(void*, const void*);
    auto construct = reinterpret_cast<Construct>(dlsym(utils, "_ZN7android7String8C1EPKc"));
    auto destroy = reinterpret_cast<Destroy>(dlsym(utils, "_ZN7android7String8D1Ev"));
    auto set = reinterpret_cast<Set>(dlsym(client, "_ZN7android11AudioRecord13setParametersERKNS_7String8E"));
    int result = -1003;
    if (construct && destroy && set) {
        // Android String8 is a single pointer-sized, non-polymorphic platform wrapper.
        // Construct/destroy through the loaded platform to retain its allocation ABI.
        alignas(void*) unsigned char parameter[sizeof(void*)]{};
        char text[40];
        std::snprintf(text, sizeof(text), "frame_count=%d", frames);
        construct(parameter, text);
        result = set(reinterpret_cast<void*>(static_cast<uintptr_t>(recorder)), parameter);
        destroy(parameter);
    }
    dlclose(utils);
    dlclose(client);
    return result;
}
