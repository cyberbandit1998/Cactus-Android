// C ABI of the official Needle engine (libneedle), declared to match the vendor header needle.h
// that ships with the engine (Cactus-Compute/needle3, folder android-arm64/, commit d6c7d6a2e965).
// That header was printed by this project's CI run and compared with these declarations; the same
// functions are defined in libneedle.a. We keep our own copy instead of including the vendor file so
// the build does not depend on the header's exact text, and we declare only what the app uses.
//
// Not used by this app: needle_embed, needle_set_audio, needle_stream_transcribe_process,
// needle_stream_transcribe_stop.
#ifndef CACTUSAI_NEEDLE_ABI_H
#define CACTUSAI_NEEDLE_ABI_H

#ifdef __cplusplus
extern "C" {
#endif

#define NEEDLE_TEXT 1
#define NEEDLE_SPEECH 2

// One process-global, non-thread-safe model per kind. Loads whichever kind the .cact holds and keeps
// the other kind, so Needle and Whistle take one call each. Negative on failure.
int needle_load(const unsigned char* cact, unsigned long long n);

// Bitmask of NEEDLE_TEXT / NEEDLE_SPEECH for the models loaded in this process.
int needle_models(void);

// Last process-global error; owned by the runtime and valid until the next API call. May be NULL.
const char* needle_last_error(void);

// Returns the tokenized static-prefix length. Fails when the system prompt plus the tools do not fit
// the context window. tool_index_path may be NULL.
int needle_init(const char* system_prompt, const char* tools_json, const char* tool_index_path);

// Exactly one of input and pcm is non-null. Writes a NUL-terminated JSON envelope to out.
int needle_complete(const char* input, const float* pcm, int samples, int max_new_tokens, char* out, int out_capacity);

void needle_reset(void);

// 16 kHz mono float PCM in [-1, 1]. language: "en","de","fr","es","it","nl","pl" or NULL to detect.
// keywords: NULL or newline-separated words/phrases. Returns the transcript's token count (>= 0) or < 0.
// out receives {"text":"...","language":"en","ttft_ms":0.0,"decode_tps":0.0}.
int needle_transcribe(const float* pcm, int samples, const char* language, const char* keywords,
                      int word_timestamps, char* out, int out_capacity);

#ifdef __cplusplus
}
#endif

#endif  // CACTUSAI_NEEDLE_ABI_H
