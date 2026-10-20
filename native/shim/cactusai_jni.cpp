// JNI shim between Kotlin (app.cactusai.core.engine.NativeLib) and the official Needle engine.
//
// It adds no AI logic. It marshals strings and audio, keeps model files mapped, serialises calls
// (the engine is not thread-safe), turns the engine's telemetry off, and reports errors as return
// values instead of throwing.
//
// CACTUSAI_ENGINE_LINKED=1  -> calls the engine's needle_* functions (the real build).
// CACTUSAI_ENGINE_LINKED=0  -> every call fails with a clear message; used only for compile checks.

#include <jni.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "needle_abi.h"

#ifndef CACTUSAI_ENGINE_LINKED
#define CACTUSAI_ENGINE_LINKED 0
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CactusAI", __VA_ARGS__)
#else
#define LOGE(...) ((void)0)
#endif

namespace {

constexpr const char* kShimVersion = "1";
constexpr size_t kCompleteBuffer = 128 * 1024;
constexpr size_t kMinTranscribeBuffer = 256 * 1024;
constexpr size_t kBytesPerAudioSecond = 4096;  // upstream OUTPUT_BYTES_PER_SECOND
constexpr jsize kMaxSamples = 16000 * 120;      // 2 minutes; the app records at most 30 s

std::mutex g_mutex;
std::string g_last_error;

struct Mapping {
    void* data = nullptr;
    size_t size = 0;
};
// Model files stay mapped for the life of the process: the C ABI does not say whether the engine
// copies the bytes it is given, and a mapped file costs only reclaimable page cache.
std::map<std::string, Mapping> g_mappings;

void set_error(const std::string& message) {
    g_last_error = message;
    LOGE("%s", message.c_str());
}

#if CACTUSAI_ENGINE_LINKED
const char* engine_last_error() { return needle_last_error(); }
int engine_load(const void* p, uint64_t n) { return needle_load(static_cast<const unsigned char*>(p), n); }
int engine_models() { return needle_models(); }
int engine_init(const char* s, const char* t) { return needle_init(s, t, nullptr); }
int engine_complete(const char* text, int max_new, char* out, int cap) {
    return needle_complete(text, nullptr, 0, max_new, out, cap);
}
int engine_transcribe(const float* x, int n, const char* lang, const char* kw, char* out, int cap) {
    return needle_transcribe(x, n, lang, kw, 0, out, cap);
}
void engine_reset() { needle_reset(); }
constexpr bool kLinked = true;
#else
const char* kNotLinked = "This build does not contain the Needle engine library (see native-engine/README.md).";
const char* engine_last_error() { return kNotLinked; }
int engine_load(const void*, uint64_t) { return -1; }
int engine_models() { return 0; }
int engine_init(const char*, const char*) { return -1; }
int engine_complete(const char*, int, char*, int) { return -1; }
int engine_transcribe(const float*, int, const char*, const char*, char*, int) { return -1; }
void engine_reset() {}
constexpr bool kLinked = false;
#endif

// The upstream wrappers read the output buffer after a failed needle_complete but needle_last_error()
// after a failed needle_transcribe, so the engine may use either. Keep whatever either one says.
std::string engine_error_text(int rc, const char* out_buffer) {
    const char* last = engine_last_error();
    std::string text = (last && *last) ? last : "";
    const std::string detail = (out_buffer && *out_buffer) ? out_buffer : "";
    if (!detail.empty() && detail != text) text = text.empty() ? detail : text + "; " + detail;
    if (text.empty()) text = "the engine failed";
    return text + " (code " + std::to_string(rc) + ")";
}

// ---- UTF-16 <-> UTF-8 (JNI's own UTF-8 is "modified" and mishandles emoji and NUL) ----

std::string to_utf8(JNIEnv* env, jstring s) {
    std::string out;
    if (!s) return out;
    const jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    if (!chars) return out;
    out.reserve(static_cast<size_t>(len) * 3);
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;  // lone surrogate
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring from_utf8(JNIEnv* env, const char* s) {
    std::vector<jchar> u16;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(s);
    while (*p) {
        uint32_t c = *p;
        int extra = 0;
        if (c < 0x80) {
            extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            c &= 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            c &= 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            c &= 0x07;
            extra = 3;
        } else {
            u16.push_back(0xFFFD);
            ++p;
            continue;
        }
        ++p;
        bool ok = true;
        for (int k = 0; k < extra; ++k) {
            if ((*p & 0xC0) != 0x80) {
                ok = false;
                break;
            }
            c = (c << 6) | (*p & 0x3F);
            ++p;
        }
        if (!ok || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) c = 0xFFFD;
        if (c >= 0x10000) {
            c -= 0x10000;
            u16.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            u16.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            u16.push_back(static_cast<jchar>(c));
        }
    }
    return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    // The upstream README says telemetry is on by default and is switched off with these variables.
    // This app promises no analytics, so they are set before the engine is ever called.
    setenv("NEEDLE_TELEMETRY", "0", 1);
    setenv("DO_NOT_TRACK", "1", 1);
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL Java_app_cactusai_core_engine_NativeLib_nativeIsLinked(JNIEnv*, jclass) {
    return kLinked ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_app_cactusai_core_engine_NativeLib_nativeShimVersion(JNIEnv* env, jclass) {
    return from_utf8(env, kShimVersion);
}

JNIEXPORT jstring JNICALL Java_app_cactusai_core_engine_NativeLib_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return from_utf8(env, g_last_error.c_str());
}

JNIEXPORT jint JNICALL Java_app_cactusai_core_engine_NativeLib_nativeLoadModel(JNIEnv* env, jclass, jstring jpath) {
    const std::string path = to_utf8(env, jpath);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!kLinked) {
        set_error(engine_last_error());
        return -1;
    }
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        set_error("cannot open model file: " + std::string(strerror(errno)));
        return -2;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        set_error("model file is empty or unreadable");
        return -3;
    }
    const size_t size = static_cast<size_t>(st.st_size);
    void* data = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (data == MAP_FAILED) {
        set_error("cannot map the model file into memory: " + std::string(strerror(errno)));
        return -4;
    }
    const int rc = engine_load(data, size);
    if (rc < 0) {
        munmap(data, size);
        set_error("the engine could not load the model: " + engine_error_text(rc, nullptr));
        return rc;
    }
    g_mappings[path] = Mapping{data, size};
    g_last_error.clear();
    return 0;
}

JNIEXPORT jint JNICALL Java_app_cactusai_core_engine_NativeLib_nativeLoadedKinds(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return engine_models();
}

JNIEXPORT jint JNICALL Java_app_cactusai_core_engine_NativeLib_nativeInit(JNIEnv* env, jclass, jstring jsystem, jstring jtools) {
    const std::string system = to_utf8(env, jsystem);
    const std::string tools = to_utf8(env, jtools);
    std::lock_guard<std::mutex> lock(g_mutex);
    const int rc = engine_init(system.c_str(), tools.c_str());
    if (rc < 0) {
        set_error("needle_init failed: " + engine_error_text(rc, nullptr));
        return -1;
    }
    g_last_error.clear();
    return rc;
}

JNIEXPORT jstring JNICALL Java_app_cactusai_core_engine_NativeLib_nativeComplete(JNIEnv* env, jclass, jstring jtext, jint max_new_tokens) {
    const std::string text = to_utf8(env, jtext);
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        std::vector<char> out(kCompleteBuffer, 0);
        const int rc = engine_complete(text.c_str(), max_new_tokens, out.data(), static_cast<int>(out.size() - 1));
        if (rc < 0) {
            set_error("needle_complete failed: " + engine_error_text(rc, out.data()));
            return nullptr;
        }
        g_last_error.clear();
        return from_utf8(env, out.data());
    } catch (const std::bad_alloc&) {
        set_error("out of memory");
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL Java_app_cactusai_core_engine_NativeLib_nativeTranscribe(JNIEnv* env, jclass, jfloatArray jsamples, jstring jlang, jstring jkeywords) {
    if (!jsamples) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error("no audio was given");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(jsamples);
    if (n <= 0 || n > kMaxSamples) {
        std::lock_guard<std::mutex> lock(g_mutex);
        set_error(n <= 0 ? "the recording is empty" : "the recording is too long");
        return nullptr;
    }
    const bool has_lang = jlang != nullptr;
    const bool has_keywords = jkeywords != nullptr;
    const std::string lang = has_lang ? to_utf8(env, jlang) : std::string();
    const std::string keywords = has_keywords ? to_utf8(env, jkeywords) : std::string();
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        std::vector<float> samples(static_cast<size_t>(n));
        env->GetFloatArrayRegion(jsamples, 0, n, samples.data());
        const size_t seconds = static_cast<size_t>(n) / 16000;
        const size_t capacity = std::max(kMinTranscribeBuffer, seconds * kBytesPerAudioSecond);
        std::vector<char> out(capacity, 0);
        const int rc = engine_transcribe(samples.data(), n, has_lang && !lang.empty() ? lang.c_str() : nullptr,
                                         has_keywords && !keywords.empty() ? keywords.c_str() : nullptr, out.data(),
                                         static_cast<int>(out.size() - 1));
        if (rc < 0) {
            set_error("needle_transcribe failed: " + engine_error_text(rc, out.data()));
            return nullptr;
        }
        g_last_error.clear();
        return from_utf8(env, out.data());
    } catch (const std::bad_alloc&) {
        set_error("out of memory");
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_app_cactusai_core_engine_NativeLib_nativeReset(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    engine_reset();
}

}  // extern "C"
