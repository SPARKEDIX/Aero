#include <jni.h>
#include <cactus.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <fstream>

static const char* TAG = "MirageNative";

extern "C" JNIEXPORT jint JNICALL
Java_com_aero_voice_Mirage_nativeLoad(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return -1;
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::ifstream file(p, std::ios::binary);
    if (!file.good()) {
        env->ReleaseStringUTFChars(path, p);
        return -1;
    }
    file.seekg(0, std::ios::end);
    std::streamoff len = file.tellg();
    file.seekg(0, std::ios::beg);
    std::vector<unsigned char> bytes(static_cast<size_t>(len));
    file.read(reinterpret_cast<char*>(bytes.data()), len);
    file.close();
    env->ReleaseStringUTFChars(path, p);

    int rc = cactus_load(bytes.data(), static_cast<unsigned long long>(bytes.size()));
    __android_log_print(ANDROID_LOG_INFO, TAG, "cactus_load rc=%d", rc);
    if (rc < 0) {
        const char* err = cactus_last_error();
        if (err) __android_log_print(ANDROID_LOG_ERROR, TAG, "load error: %s", err);
    }
    // Initialise with a light vision prompt; no tools needed for OCR/description.
    cactus_init("Describe the image in detail and transcribe all visible text.", "", "");
    return rc;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aero_voice_Mirage_nativeDescribe(
        JNIEnv* env, jclass,
        jbyteArray rgb, jint width, jint height,
        jstring options, jstring language, jstring keywords) {

    if (rgb == nullptr) return env->NewStringUTF("");

    jsize len = env->GetArrayLength(rgb);
    std::vector<unsigned char> pixels(static_cast<size_t>(len));
    env->GetByteArrayRegion(rgb, 0, len, reinterpret_cast<jbyte*>(pixels.data()));

    const char* opt = options ? env->GetStringUTFChars(options, nullptr) : "";
    const char* lang = language ? env->GetStringUTFChars(language, nullptr) : "";
    const char* keys = keywords ? env->GetStringUTFChars(keywords, nullptr) : "";

    std::vector<char> out(32768, 0);
    int rc = cactus_describe(
            pixels.data(), width, height,
            opt, lang, keys,
            out.data(), static_cast<int>(out.size()));

    if (options) env->ReleaseStringUTFChars(options, opt);
    if (language) env->ReleaseStringUTFChars(language, lang);
    if (keywords) env->ReleaseStringUTFChars(keywords, keys);

    if (rc < 0) {
        const char* err = cactus_last_error();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "describe error: %s", err ? err : "unknown");
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(out.data());
}
