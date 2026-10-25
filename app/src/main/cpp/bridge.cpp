// SPDX-License-Identifier: Apache-2.0
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <vector>
#include <exception>
#include <sys/resource.h>
#include "raop_loop.h"
#include "raop_sender.h"
#include "ring_buffer.h"

namespace {
struct Engine {
    fxchain::RingBuffer<int16_t> ring{1u << 16};
    std::atomic<bool> stop{false};
    std::atomic<int> state{1}; // connecting, 2=RECORD accepted, negative=failure
    std::atomic<int> volume{-1};
    std::mutex diagnosticMutex;
    std::string diagnostic;
    std::string statistics;
    std::atomic<uint64_t> backpressure{0};
    std::thread worker;
};
std::mutex engineMutex;
std::shared_ptr<Engine> current;

std::shared_ptr<Engine> getEngine() {
    std::lock_guard<std::mutex> lock(engineMutex);
    return current;
}
std::string utf(JNIEnv* env, jstring value) {
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}
void closeEngine() {
    std::shared_ptr<Engine> old;
    {
        std::lock_guard<std::mutex> lock(engineMutex);
        old = std::move(current);
    }
    if (old) {
        old->stop.store(true);
        if (old->worker.joinable()) old->worker.join();
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_start(JNIEnv* env, jclass, jstring host,
        jint port, jstring name, jstring deviceId, jstring identity, jint volumeLimit, jint bufferMs, jint latencyMs) {
    closeEngine();
    auto engine = std::make_shared<Engine>();
    auto ip = utf(env, host), label = utf(env, name), id = utf(env, deviceId);
    auto sourceId = utf(env, identity);
    if (env->ExceptionCheck()) return;
    {
        std::lock_guard<std::mutex> lock(engineMutex);
        current = engine;
    }
    engine->worker = std::thread([engine, ip, label, id, sourceId, port, volumeLimit, bufferMs, latencyMs] {
        try {
            if (setpriority(PRIO_PROCESS, 0, -16) != 0)
                __android_log_write(ANDROID_LOG_INFO, "HomePodBridge", "Audio sender priority unchanged");
            fxchain::RaopLoop loop;
            fxchain::RaopEvents events;
            events.launched = [engine](bool ok, const std::string& error) {
                if (!ok) { std::lock_guard<std::mutex> lock(engine->diagnosticMutex); engine->diagnostic += "Failure: " + error + "\n"; }
                if (!ok) __android_log_print(ANDROID_LOG_WARN, "HomePodBridge", "AirPlay connection failed: %s", error.c_str());
                engine->state.store(ok ? 2 : -1);
            };
            events.closed = [engine] {
                __android_log_write(ANDROID_LOG_WARN, "HomePodBridge", "AirPlay connection closed by receiver");
                engine->state.store(-2);
            };
            events.pinRequired = [engine](const std::string&) { engine->state.store(-3); };
            fxchain::RaopSender sender(loop, std::move(events), [engine](fxchain::RaopLogLevel level, const std::string& message) {
                { std::lock_guard<std::mutex> lock(engine->diagnosticMutex);
                  if (engine->diagnostic.size() < 16384) engine->diagnostic += message + "\n"; }
                __android_log_print(level == fxchain::RaopLogLevel::Warn ? ANDROID_LOG_WARN : ANDROID_LOG_INFO,
                                    "HomePodBridge", "%s", message.c_str());
            });
            sender.setStrictReceiverAuth(true);
            sender.setVolumePolicy(0, std::max(0, std::min(100, static_cast<int>(volumeLimit))));
            sender.setStreamBeforeRecord(false);
            fxchain::RaopIdentity source;
            source.name = "HomePod Bridge";
            source.deviceId = sourceId;
            sender.setIdentity(source);
            sender.attachRing(&engine->ring);
            sender.setInputFormat(44100);
            sender.setInputBufferFrames(static_cast<size_t>(std::clamp(static_cast<int>(bufferMs), 0, 300)) * 44100 / 1000);
            sender.setPresentationLatencyMs(static_cast<uint32_t>(std::clamp(static_cast<int>(latencyMs), 250, 2000)));
            sender.setAuth(fxchain::RaopDeviceInfo::Auth::HapTransient, true, id, {}, {});
            sender.start(ip, static_cast<uint16_t>(port), label);
            auto nextStatistics = std::chrono::steady_clock::now();
            while (!engine->stop.load() && engine->state.load() > 0) {
                const int volume = engine->volume.exchange(-1);
                if (volume >= 0) sender.setVolume(volume);
                loop.pump(sender, std::chrono::milliseconds(20));
                auto now = std::chrono::steady_clock::now();
                if (now >= nextStatistics) {
                    std::lock_guard<std::mutex> lock(engine->diagnosticMutex);
                    engine->statistics = "queueFrames=" + std::to_string(engine->ring.availableRead() / 2)
                        + " underrunFrames=" + std::to_string(sender.inputUnderrunFrames())
                        + " bufferingFrames=" + std::to_string(sender.bufferingFrames())
                        + " rebuffers=" + std::to_string(sender.rebufferCount())
                        + " backpressure=" + std::to_string(engine->backpressure.load());
                    nextStatistics = now + std::chrono::seconds(1);
                }
            }
            sender.stop();
        } catch (const std::exception& error) {
            { std::lock_guard<std::mutex> lock(engine->diagnosticMutex); engine->diagnostic += std::string("Exception: ") + error.what() + "\n"; }
            engine->state.store(-4);
        } catch (...) {
            { std::lock_guard<std::mutex> lock(engine->diagnosticMutex); engine->diagnostic += "Unknown native exception\n"; }
            engine->state.store(-4);
        }
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_write(JNIEnv* env, jclass, jshortArray pcm, jint count) {
    auto engine = getEngine();
    if (!engine || count <= 0 || count % 2 || count > env->GetArrayLength(pcm)) return JNI_FALSE;
    std::vector<int16_t> samples(static_cast<size_t>(count));
    env->GetShortArrayRegion(pcm, 0, count, samples.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    if (engine->ring.tryPush(std::span<const int16_t>(samples))) return JNI_TRUE;
    ++engine->backpressure;
    return JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_state(JNIEnv*, jclass) {
    auto engine = getEngine();
    return engine ? engine->state.load() : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_volume(JNIEnv*, jclass, jint volume) {
    auto engine = getEngine();
    if (engine) engine->volume.store(std::max(0, std::min(100, static_cast<int>(volume))));
}
extern "C" JNIEXPORT void JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_stop(JNIEnv*, jclass) { closeEngine(); }
extern "C" JNIEXPORT jstring JNICALL
Java_dev_ryuya_homepodbridge_NativeSender_diagnostic(JNIEnv* env, jclass) {
    auto engine = getEngine();
    if (!engine) return env->NewStringUTF("No sender instance");
    std::lock_guard<std::mutex> lock(engine->diagnosticMutex);
    std::string result = engine->diagnostic + "\n" + engine->statistics;
    return env->NewStringUTF(result.c_str());
}
