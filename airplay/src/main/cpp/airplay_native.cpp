#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>

extern "C" {
#include "dnssd.h"
#include "logger.h"
#include "raop.h"
#include "stream.h"
}

namespace {
constexpr char kTag[] = "CastAirPlay";

struct AirPlayEngine {
    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    jmethodID onVideoFrame = nullptr;
    jmethodID onAudioFrame = nullptr;
    raop_t* raop = nullptr;
    dnssd_t* dnssd = nullptr;
    std::atomic_bool running = false;
    std::mutex mutex;
};

JNIEnv* attach(AirPlayEngine* engine) {
    JNIEnv* env = nullptr;
    if (engine->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        engine->vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

void sendVideo(void* opaque, raop_ntp_t*, video_decode_struct* frame) {
    auto* engine = static_cast<AirPlayEngine*>(opaque);
    if (!engine->running || !frame || frame->data_len <= 0) return;
    JNIEnv* env = attach(engine);
    if (!env) return;
    jbyteArray data = env->NewByteArray(frame->data_len);
    if (!data) return;
    env->SetByteArrayRegion(data, 0, frame->data_len, reinterpret_cast<const jbyte*>(frame->data));
    env->CallVoidMethod(engine->callback, engine->onVideoFrame, data,
                        frame->is_h265 ? JNI_TRUE : JNI_FALSE,
                        static_cast<jlong>(frame->ntp_time_remote));
    env->DeleteLocalRef(data);
}

void sendAudio(void* opaque, raop_ntp_t*, audio_decode_struct* frame) {
    auto* engine = static_cast<AirPlayEngine*>(opaque);
    if (!engine->running || !frame || frame->data_len <= 0) return;
    JNIEnv* env = attach(engine);
    if (!env) return;
    jbyteArray data = env->NewByteArray(frame->data_len);
    if (!data) return;
    env->SetByteArrayRegion(data, 0, frame->data_len, reinterpret_cast<const jbyte*>(frame->data));
    env->CallVoidMethod(engine->callback, engine->onAudioFrame, data,
                        static_cast<jint>(frame->ct),
                        static_cast<jlong>(frame->ntp_time_remote));
    env->DeleteLocalRef(data);
}

void admitClient(void*, char*, char*, char*, bool* admit) {
    if (admit) *admit = true;
}

int setCodec(void*, video_codec_t codec) {
    return codec == VIDEO_CODEC_H264 || codec == VIDEO_CODEC_H265 ? 0 : -1;
}

void logMessage(void*, int level, const char* message) {
    int priority = ANDROID_LOG_INFO;
    if (level <= LOGGER_ERR) priority = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) priority = ANDROID_LOG_WARN;
    __android_log_print(priority, kTag, "%s", message ? message : "");
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeCreate(JNIEnv* env, jobject, jobject callback) {
    auto* engine = new AirPlayEngine();
    env->GetJavaVM(&engine->vm);
    engine->callback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(callback);
    engine->onVideoFrame = env->GetMethodID(callbackClass, "onVideoFrame", "([BZJ)V");
    engine->onAudioFrame = env->GetMethodID(callbackClass, "onAudioFrame", "([BIJ)V");
    env->DeleteLocalRef(callbackClass);
    if (!engine->onVideoFrame || !engine->onAudioFrame) {
        env->DeleteGlobalRef(engine->callback);
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeStart(JNIEnv* env, jobject, jlong handle, jstring keyFile) {
    auto* engine = reinterpret_cast<AirPlayEngine*>(handle);
    if (!engine || !keyFile) return JNI_FALSE;

    const char* keyPath = env->GetStringUTFChars(keyFile, nullptr);
    if (!keyPath) return JNI_FALSE;

    raop_callbacks_t callbacks{};
    callbacks.cls = engine;
    callbacks.audio_process = sendAudio;
    callbacks.video_process = sendVideo;
    callbacks.report_client_request = admitClient;
    callbacks.video_set_codec = setCodec;

    engine->raop = raop_init(&callbacks);
    if (!engine->raop || raop_init2(engine->raop, 1, "02:00:00:00:00:01", keyPath) != 0) {
        if (engine->raop) raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return JNI_FALSE;
    }
    raop_set_log_level(engine->raop, LOGGER_WARNING);
    raop_set_log_callback(engine->raop, logMessage, engine);
    raop_set_plist(engine->raop, "width", 1920);
    raop_set_plist(engine->raop, "height", 1080);
    raop_set_plist(engine->raop, "refreshRate", 60);
    raop_set_plist(engine->raop, "maxFPS", 60);

    unsigned char hardwareAddress[6] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x01};
    int dnssdError = 0;
    engine->dnssd = dnssd_init("i投屏", 7, reinterpret_cast<const char*>(hardwareAddress), 6, 0, &dnssdError);
    if (!engine->dnssd) {
        raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return JNI_FALSE;
    }
    raop_set_dnssd(engine->raop, engine->dnssd);
    unsigned short port = 0;
    if (raop_start_httpd(engine->raop, &port) != 0 ||
        dnssd_register_raop(engine->dnssd, port) != 0 ||
        dnssd_register_airplay(engine->dnssd, port) != 0) {
        dnssd_destroy(engine->dnssd);
        engine->dnssd = nullptr;
        raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return JNI_FALSE;
    }
    engine->running = true;
    env->ReleaseStringUTFChars(keyFile, keyPath);
    __android_log_print(ANDROID_LOG_INFO, kTag, "AirPlay listening on port %u", port);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeStop(JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AirPlayEngine*>(handle);
    if (!engine) return;
    engine->running = false;
    if (engine->dnssd) {
        dnssd_unregister_airplay(engine->dnssd);
        dnssd_unregister_raop(engine->dnssd);
        dnssd_destroy(engine->dnssd);
        engine->dnssd = nullptr;
    }
    if (engine->raop) {
        raop_destroy(engine->raop);
        engine->raop = nullptr;
    }
    env->DeleteGlobalRef(engine->callback);
    delete engine;
    __android_log_print(ANDROID_LOG_INFO, kTag, "AirPlay native engine stopped");
}
