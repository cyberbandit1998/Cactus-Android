// TEST DOUBLE for the Needle engine's C ABI. It is NOT the engine and does no AI: it returns
// canned JSON derived from its inputs so the JNI shim's marshalling can be checked on a computer
// with no phone and no models. It must never be linked into the APK.
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "needle_abi.h"

namespace {
std::string g_error;
std::string g_system;
std::string g_tools;
int g_loads = 0;
int g_resets = 0;
int g_kinds = 0;

int write_out(const std::string& s, char* out, int cap) {
    if (static_cast<int>(s.size()) + 1 > cap) {
        g_error = "output buffer too small";
        return -3;
    }
    std::memcpy(out, s.c_str(), s.size() + 1);
    return static_cast<int>(s.size());
}
}  // namespace

extern "C" {

int needle_models(void) { return g_kinds; }

int needle_load(const unsigned char* bytes, unsigned long long size) {
    g_error.clear();
    if (!bytes || size < 196) {
        g_error = "archive too small";
        return -1;
    }
    uint32_t tag;
    std::memcpy(&tag, bytes, 4);
    if (tag != 0x05E12A84u) {
        g_error = "unknown format tag";
        return -2;
    }
    // Reads the last byte to prove the whole region is readable memory.
    volatile unsigned char last = bytes[size - 1];
    (void)last;
    // The test fixture marks the kind in header word 3: 1 = text (Needle), 2 = speech (Whistle).
    uint32_t kind;
    std::memcpy(&kind, bytes + 12, 4);
    g_kinds |= static_cast<int>(kind & 3u);
    ++g_loads;
    return 0;
}

const char* needle_last_error(void) { return g_error.empty() ? nullptr : g_error.c_str(); }

int needle_init(const char* system, const char* tools_json, const char*) {
    g_error.clear();
    if (!tools_json || !*tools_json) {
        g_error = "no tools";
        return -1;
    }
    g_system = system ? system : "";
    g_tools = tools_json;
    return static_cast<int>(g_tools.size() / 4);
}

int needle_complete(const char* text, const float*, int, int max_new_tokens, char* out, int cap) {
    g_error.clear();
    std::string t = text ? text : "";
    if (t == "__fail__") {
        const std::string detail = "boom in out buffer";
        std::memcpy(out, detail.c_str(), detail.size() + 1);
        return -2;
    }
    if (t == "__fail_with_last_error__") {
        g_error = "engine says no";
        return -2;
    }
    if (t == "__env__") {
        const char* a = std::getenv("NEEDLE_TELEMETRY");
        const char* b = std::getenv("DO_NOT_TRACK");
        return write_out(std::string("{\"telemetry\":\"") + (a ? a : "unset") + "\",\"doNotTrack\":\"" + (b ? b : "unset") + "\"}", out, cap);
    }
    if (t == "__stats__") {
        return write_out("{\"loads\":" + std::to_string(g_loads) + ",\"resets\":" + std::to_string(g_resets) +
                         ",\"system\":\"" + g_system + "\",\"toolsLength\":" + std::to_string(g_tools.size()) + "}", out, cap);
    }
    return write_out("{\"echo\":\"" + t + "\",\"max\":" + std::to_string(max_new_tokens) + "}", out, cap);
}

void needle_reset(void) { ++g_resets; }

int needle_transcribe(const float* samples, int n, const char* language, const char* keywords, int, char* out, int cap) {
    g_error.clear();
    if (n == 123) {
        g_error = "bad clip";
        return -1;
    }
    double sum = 0;
    for (int i = 0; i < n; ++i) sum += samples[i];
    char buf[256];
    std::snprintf(buf, sizeof(buf), "{\"n\":%d,\"sum\":%.4f,\"language\":\"%s\",\"keywords\":\"%s\",\"cap\":%d}", n, sum,
                  language ? language : "auto", keywords ? keywords : "none", cap);
    return write_out(buf, out, cap);
}

}  // extern "C"
