#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

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
    jmethodID onLog = nullptr;
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

void emitLog(AirPlayEngine* engine, const char* message) {
    if (!engine || !engine->callback || !engine->onLog) return;
    JNIEnv* env = attach(engine);
    if (!env) return;
    jstring value = env->NewStringUTF(message ? message : "");
    if (!value) return;
    env->CallVoidMethod(engine->callback, engine->onLog, value);
    env->DeleteLocalRef(value);
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

void logMessage(void* opaque, int level, const char* message) {
    int priority = ANDROID_LOG_INFO;
    if (level <= LOGGER_ERR) priority = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) priority = ANDROID_LOG_WARN;
    __android_log_print(priority, kTag, "%s", message ? message : "");
    emitLog(static_cast<AirPlayEngine*>(opaque), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeCreate(JNIEnv* env, jobject, jobject callback) {
    auto* engine = new AirPlayEngine();
    env->GetJavaVM(&engine->vm);
    engine->callback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(callback);
    engine->onLog = env->GetMethodID(callbackClass, "onLog", "(Ljava/lang/String;)V");
    engine->onVideoFrame = env->GetMethodID(callbackClass, "onVideoFrame", "([BZJ)V");
    engine->onAudioFrame = env->GetMethodID(callbackClass, "onAudioFrame", "([BIJ)V");
    env->DeleteLocalRef(callbackClass);
    if (!engine->onLog || !engine->onVideoFrame || !engine->onAudioFrame) {
        env->DeleteGlobalRef(engine->callback);
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeStart(JNIEnv* env, jobject, jlong handle, jstring keyFile) {
    auto* engine = reinterpret_cast<AirPlayEngine*>(handle);
    if (!engine || !keyFile) return -1;

    const char* keyPath = env->GetStringUTFChars(keyFile, nullptr);
    if (!keyPath) return -1;
    emitLog(engine, "native: 开始启动 AirPlay 协议服务");

    raop_callbacks_t callbacks{};
    callbacks.cls = engine;
    callbacks.audio_process = sendAudio;
    callbacks.video_process = sendVideo;
    callbacks.report_client_request = admitClient;
    callbacks.video_set_codec = setCodec;

    engine->raop = raop_init(&callbacks);
    if (!engine->raop) {
        emitLog(engine, "native: raop_init 失败");
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return -1;
    }
    raop_set_log_level(engine->raop, LOGGER_WARNING);
    raop_set_log_callback(engine->raop, logMessage, engine);
    if (raop_init2(engine->raop, 1, "02:00:00:00:00:01", keyPath) != 0) {
        emitLog(engine, "native: AirPlay 配对/HTTP 初始化失败");
        if (engine->raop) raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return -1;
    }
    raop_set_plist(engine->raop, "width", 1920);
    raop_set_plist(engine->raop, "height", 1080);
    raop_set_plist(engine->raop, "refreshRate", 60);
    raop_set_plist(engine->raop, "maxFPS", 60);

    unsigned char hardwareAddress[6] = {0x02, 0x00, 0x00, 0x00, 0x00, 0x01};
    int dnssdError = 0;
    engine->dnssd = dnssd_init("i投屏", 7, reinterpret_cast<const char*>(hardwareAddress), 6, 0, &dnssdError);
    if (!engine->dnssd) {
        const std::string errorMessage = "native: dnssd_init 失败，错误码 " + std::to_string(dnssdError);
        emitLog(engine, errorMessage.c_str());
        raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return -1;
    }
    raop_set_dnssd(engine->raop, engine->dnssd);
    unsigned short port = 0;
    if (raop_start_httpd(engine->raop, &port) != 0) {
        emitLog(engine, "native: HTTP/RTSP 监听启动失败");
        dnssd_destroy(engine->dnssd);
        engine->dnssd = nullptr;
        raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return -1;
    }
    if (dnssd_prepare_raop(engine->dnssd, port) != 0 ||
        dnssd_prepare_airplay(engine->dnssd, port) != 0) {
        emitLog(engine, "native: AirPlay TXT 记录生成失败");
        dnssd_destroy(engine->dnssd);
        engine->dnssd = nullptr;
        raop_destroy(engine->raop);
        engine->raop = nullptr;
        env->ReleaseStringUTFChars(keyFile, keyPath);
        return -1;
    }
    engine->running = true;
    env->ReleaseStringUTFChars(keyFile, keyPath);
    emitLog(engine, "native: HTTP/RTSP 已监听，Android NsdManager 将广播服务");
    __android_log_print(ANDROID_LOG_INFO, kTag, "AirPlay listening on port %u", port);
    return static_cast<jint>(port);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeGetTxt(JNIEnv* env, jobject, jlong handle, jboolean airplay) {
    auto* engine = reinterpret_cast<AirPlayEngine*>(handle);
    if (!engine || !engine->dnssd) return nullptr;

    int length = 0;
    const char* raw = airplay
        ? dnssd_get_airplay_txt(engine->dnssd, &length)
        : dnssd_get_raop_txt(engine->dnssd, &length);
    jclass stringClass = env->FindClass("java/lang/String");
    int count = 0;
    for (int offset = 0; offset < length;) {
        const unsigned char itemLength = static_cast<unsigned char>(raw[offset]);
        if (itemLength == 0 || offset + 1 + itemLength > length) break;
        count++;
        offset += itemLength + 1;
    }
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    int index = 0;
    for (int offset = 0; offset < length && index < count;) {
        const unsigned char itemLength = static_cast<unsigned char>(raw[offset++]);
        std::string item(raw + offset, raw + offset + itemLength);
        jstring value = env->NewStringUTF(item.c_str());
        env->SetObjectArrayElement(result, index++, value);
        env->DeleteLocalRef(value);
        offset += itemLength;
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_w_cast_airplay_AirPlayNative_nativeStop(JNIEnv* env, jobject, jlong handle) {
    auto* engine = reinterpret_cast<AirPlayEngine*>(handle);
    if (!engine) return;
    engine->running = false;
    if (engine->dnssd) {
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
