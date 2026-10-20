// C ABI of the official Needle engine (libneedle), as used by the upstream Python package
// (cactus-compute/needle: needle/_worker.py, needle/__init__.py, needle/agent/whistle.py,
// engine 3.3.3). These are declarations only, written from those verified ctypes bindings.
// We deliberately do not include the engine's own header: it is not available to review here,
// and C linkage does not depend on it.
//
// Not used by this app: needle_embed, needle_stream_transcribe_process, needle_stream_transcribe_stop.
#ifndef CACTUSAI_NEEDLE_ABI_H
#define CACTUSAI_NEEDLE_ABI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Loads a .cact archive (Needle 3 or Whistle; the engine tells them apart). Returns < 0 on error.
int needle_load(const void* cact_bytes, uint64_t size);

// Text of the last error, or NULL.
const char* needle_last_error(void);

// Sets the facts and the tools JSON for the Needle conversation. Returns the prompt prefix length
// in tokens (>= 0) or < 0 on error. tool_index_path must be NULL for Needle 3.
int needle_init(const char* system, const char* tools_json, const char* tool_index_path);

// One Needle turn. audio may be NULL with n_audio 0. Writes a NUL-terminated JSON envelope to out.
// Returns < 0 on error (out may then hold a message).
int needle_complete(const char* text, const float* audio, int n_audio, int max_new_tokens,
                    char* out, int out_capacity);

// Starts a fresh conversation, keeping the tools.
void needle_reset(void);

// Whistle: 16 kHz mono float samples in [-1, 1]. language may be NULL (detect). keywords may be NULL.
// Writes a NUL-terminated JSON object {"text","language","ttft_ms","decode_tps"[,"words"]} to out.
int needle_transcribe(const float* samples, int n_samples, const char* language, const char* keywords,
                      int word_timestamps, char* out, int out_capacity);

#ifdef __cplusplus
}
#endif

#endif  // CACTUSAI_NEEDLE_ABI_H
