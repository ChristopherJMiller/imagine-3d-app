// JNI bridge between Kotlin (app.imagine3d.llama.LlamaNative) and llama.cpp.
//
// One Engine per loaded model. generate() is stateless from the caller's point of view
// (it takes the whole conversation each time), but internally it snapshots the model
// state after the system prompt so later calls with the same system prompt skip
// re-processing it. Restoring a full sequence snapshot works for transformer, recurrent
// and hybrid (e.g. Qwen3.5) memory alike, unlike partial KV-cache trimming.

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"
#include <nlohmann/json.hpp>

#define TAG "imagine-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using json = nlohmann::json;
using clk = std::chrono::steady_clock;

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *mtmd = nullptr;
    common_chat_templates_ptr tmpls;
    int n_batch = 512;
    int n_ctx = 0;

    std::vector<llama_token> prefix_tokens;  // tokens covered by prefix_state
    std::vector<uint8_t> prefix_state;       // llama_state_seq snapshot after the prefix

    std::atomic<bool> cancel{false};
};

void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, "llama.cpp", "%s", text);
}

// GetStringUTFChars returns *modified* UTF-8 (emoji become surrogate pairs), so ask Java for
// real UTF-8 bytes instead.
std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    jclass cls = env->GetObjectClass(s);
    jmethodID get_bytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, get_bytes, charset);
    jsize len = env->GetArrayLength(arr);
    std::string out((size_t) len, '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(cls);
    return out;
}

// json::dump throws on invalid UTF-8 (e.g. a model emitting a broken byte sequence); replace instead.
std::string dump(const json &j) { return j.dump(-1, ' ', false, json::error_handler_t::replace); }

// JNI's NewStringUTF wants modified UTF-8; go through a byte[] + String(bytes, UTF_8) instead
// so emoji and other 4-byte sequences survive.
jstring to_jstring(JNIEnv *env, const std::string &s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jclass str_cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(str_cls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(str_cls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(str_cls);
    return result;
}

// Number of bytes at the end of s that form an incomplete UTF-8 sequence.
size_t incomplete_utf8_tail(const std::string &s) {
    for (size_t i = 1; i <= 4 && i <= s.size(); ++i) {
        unsigned char c = s[s.size() - i];
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking back
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return need > i ? i : 0;
    }
    return 0;
}

bool decode_tokens(Engine *e, const std::vector<llama_token> &tokens, int &n_past, bool logits_last) {
    common_batch batch(e->ctx);
    for (size_t i = 0; i < tokens.size(); i += e->n_batch) {
        if (e->cancel.load()) return false;
        batch.clear();
        size_t n = std::min(tokens.size() - i, (size_t) e->n_batch);
        for (size_t j = 0; j < n; ++j) {
            bool last = (i + j == tokens.size() - 1);
            batch.add(tokens[i + j], n_past + (llama_pos) (i + j), 0, logits_last && last);
        }
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGE("llama_process failed at token %zu", i);
            return false;
        }
    }
    n_past += (int) tokens.size();
    return true;
}

std::vector<common_chat_msg> to_messages(JNIEnv *env, jobjectArray roles, jobjectArray contents) {
    std::vector<common_chat_msg> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        common_chat_msg m;
        m.role = jstr(env, r);
        m.content = jstr(env, c);
        msgs.push_back(std::move(m));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    return msgs;
}

std::string render(Engine *e, const std::vector<common_chat_msg> &msgs, bool add_generation_prompt) {
    common_chat_templates_inputs in;
    in.messages = msgs;
    in.add_generation_prompt = add_generation_prompt;
    in.use_jinja = true;
    in.enable_thinking = false;
    return common_chat_templates_apply(e->tmpls.get(), in).prompt.str();
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_app_imagine3d_llama_LlamaNative_backendInit(JNIEnv *env, jclass, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
    std::string dir = jstr(env, native_lib_dir);
    LOGI("loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
}

JNIEXPORT jstring JNICALL
Java_app_imagine3d_llama_LlamaNative_systemInfo(JNIEnv *env, jclass) {
    return to_jstring(env, llama_print_system_info());
}

static jlong load_impl(JNIEnv *env, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads);

JNIEXPORT jlong JNICALL
Java_app_imagine3d_llama_LlamaNative_load(JNIEnv *env, jclass, jstring jmodel, jstring jmmproj, jint n_ctx,
                                          jint n_threads) {
    try {
        return load_impl(env, jmodel, jmmproj, n_ctx, n_threads);
    } catch (const std::exception &ex) {
        LOGE("load failed: %s", ex.what());
        return 0;
    } catch (...) {
        return 0;
    }
}

static jlong load_impl(JNIEnv *env, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads) {
    auto *e = new Engine();
    std::string model_path = jstr(env, jmodel);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU: Mali Vulkan is not reliable enough yet (see docs)
    e->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!e->model) {
        LOGE("failed to load model %s", model_path.c_str());
        delete e;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = e->n_batch;
    cp.n_ubatch = e->n_batch;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) {
        LOGE("failed to create context");
        llama_model_free(e->model);
        delete e;
        return 0;
    }
    e->n_ctx = (int) llama_n_ctx(e->ctx);
    e->tmpls = common_chat_templates_init(e->model, "");

    std::string mmproj = jstr(env, jmmproj);
    if (!mmproj.empty()) {
        mtmd_context_params mcp = mtmd_context_params_default();
        mcp.use_gpu = false;
        mcp.n_threads = n_threads;
        mcp.print_timings = true;
        mcp.warmup = false;
        mcp.image_max_tokens = 384;  // bounds encoder + prefill time on a phone CPU (dynamic-resolution models only)
        e->mtmd = mtmd_init_from_file(mmproj.c_str(), e->model, mcp);
        if (!e->mtmd) LOGW("failed to load vision projector %s; continuing text-only", mmproj.c_str());
    }
    LOGI("model loaded: ctx=%d threads=%d vision=%d", e->n_ctx, n_threads, e->mtmd != nullptr);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_app_imagine3d_llama_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->mtmd) mtmd_free(e->mtmd);
    e->tmpls.reset();
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jstring JNICALL
Java_app_imagine3d_llama_LlamaNative_info(JNIEnv *env, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    try {
        char desc[256];
        llama_model_desc(e->model, desc, sizeof(desc));
        json j = {
            {"desc", desc},
            {"size_bytes", llama_model_size(e->model)},
            {"n_params", llama_model_n_params(e->model)},
            {"n_ctx", e->n_ctx},
            {"vision", e->mtmd != nullptr},
        };
        return to_jstring(env, dump(j));
    } catch (const std::exception &ex) {
        return to_jstring(env, dump(json{{"error", ex.what()}}));
    }
}

JNIEXPORT void JNICALL
Java_app_imagine3d_llama_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (e) e->cancel.store(true);
}

// Runs one assistant turn. Returns a JSON object: {text, stop, prompt_tokens, cached_tokens,
// prefill_ms, gen_tokens, gen_ms, error?}. `callback.onToken(String): Boolean` receives text
// pieces as they are produced; returning false stops generation.
static jstring generate_impl(JNIEnv *env, jlong handle, jobjectArray roles, jobjectArray contents, jobjectArray images,
                             jint max_tokens, jfloat temp, jfloat top_p, jint top_k, jint seed, jobject callback);

JNIEXPORT jstring JNICALL
Java_app_imagine3d_llama_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jobjectArray roles,
                                              jobjectArray contents, jobjectArray images, jint max_tokens,
                                              jfloat temp, jfloat top_p, jint top_k, jint seed,
                                              jobject callback) {
    try {
        return generate_impl(env, handle, roles, contents, images, max_tokens, temp, top_p, top_k, seed, callback);
    } catch (const std::exception &ex) {
        LOGE("generate failed: %s", ex.what());
        return to_jstring(env, dump(json{{"error", std::string("native error: ") + ex.what()}}));
    } catch (...) {
        LOGE("generate failed: unknown exception");
        return to_jstring(env, dump(json{{"error", "native error"}}));
    }
}

static jstring generate_impl(JNIEnv *env, jlong handle, jobjectArray roles, jobjectArray contents, jobjectArray images,
                             jint max_tokens, jfloat temp, jfloat top_p, jint top_k, jint seed, jobject callback) {
    auto *e = reinterpret_cast<Engine *>(handle);
    e->cancel.store(false);
    json result;
    auto fail = [&](const std::string &msg) {
        LOGE("%s", msg.c_str());
        result["error"] = msg;
        return to_jstring(env, dump(result));
    };

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");

    auto msgs = to_messages(env, roles, contents);
    if (msgs.empty()) return fail("no messages");

    // Images are attached to the last user message.
    std::vector<std::vector<uint8_t>> image_bufs;
    if (images) {
        jsize n = env->GetArrayLength(images);
        for (jsize i = 0; i < n; ++i) {
            auto arr = (jbyteArray) env->GetObjectArrayElement(images, i);
            jsize len = env->GetArrayLength(arr);
            std::vector<uint8_t> buf(len);
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(buf.data()));
            image_bufs.push_back(std::move(buf));
            env->DeleteLocalRef(arr);
        }
    }
    if (!image_bufs.empty()) {
        if (!e->mtmd) return fail("this model has no vision projector loaded");
        for (auto it = msgs.rbegin(); it != msgs.rend(); ++it) {
            if (it->role == "user") {
                std::string markers;
                for (size_t i = 0; i < image_bufs.size(); ++i) markers += std::string(mtmd_default_marker()) + "\n";
                it->content = markers + it->content;
                break;
            }
        }
    }

    const llama_vocab *vocab = llama_model_get_vocab(e->model);
    std::string full = render(e, msgs, true);

    // Split off everything up to where the first user message's text starts, so the processed
    // system prompt can be cached. Rendering system + a sentinel user turn (rather than the system
    // message alone) works for templates that reject conversations without a user turn (Qwen3.5)
    // and for ones that fold the system prompt into the first user turn (Gemma).
    std::string prefix;
    if (msgs.size() >= 2 && msgs.front().role == "system") {
        static const std::string SENTINEL = "\x01IMAGINE_SPLIT\x01";
        common_chat_msg probe_user;
        probe_user.role = "user";
        probe_user.content = SENTINEL;
        std::string probe = render(e, {msgs.front(), probe_user}, false);
        size_t pos = probe.find(SENTINEL);
        if (pos != std::string::npos && full.compare(0, pos, probe, 0, pos) == 0) prefix = probe.substr(0, pos);
    }
    std::string rest = full.substr(prefix.size());
    std::vector<llama_token> prefix_tokens = common_tokenize(vocab, prefix, true, true);
    std::vector<llama_token> rest_tokens = common_tokenize(vocab, rest, prefix.empty(), true);

    auto t0 = clk::now();
    llama_memory_t mem = llama_get_memory(e->ctx);
    int n_past = 0;
    int cached = 0;
    bool restored = false;
    if (!prefix_tokens.empty() && prefix_tokens == e->prefix_tokens && !e->prefix_state.empty()) {
        llama_memory_clear(mem, true);
        if (llama_state_seq_set_data(e->ctx, e->prefix_state.data(), e->prefix_state.size(), 0) > 0) {
            n_past = (int) prefix_tokens.size();
            cached = n_past;
            restored = true;
        }
    }
    if (!restored) {
        llama_memory_clear(mem, true);
        e->prefix_tokens.clear();
        e->prefix_state.clear();
        if (!prefix_tokens.empty()) {
            if (!decode_tokens(e, prefix_tokens, n_past, false)) return fail(e->cancel ? "cancelled" : "prefix decode failed");
            size_t sz = llama_state_seq_get_size(e->ctx, 0);
            e->prefix_state.resize(sz);
            llama_state_seq_get_data(e->ctx, e->prefix_state.data(), sz, 0);
            e->prefix_tokens = prefix_tokens;
            LOGI("cached prefix: %zu tokens, %zu KiB state", prefix_tokens.size(), sz / 1024);
        }
    }

    int prompt_tokens = (int) (prefix_tokens.size() + rest_tokens.size());
    if (image_bufs.empty()) {
        if (prompt_tokens + 16 > e->n_ctx) return fail("prompt too long for context (" + std::to_string(prompt_tokens) + " tokens)");
        if (!decode_tokens(e, rest_tokens, n_past, true)) return fail(e->cancel ? "cancelled" : "prompt decode failed");
    } else {
        std::vector<mtmd::bitmap> bitmaps;
        for (auto &buf : image_bufs) {
            auto w = mtmd_helper_bitmap_init_from_buf(e->mtmd, buf.data(), buf.size(), false, mtmd_helper_init_opt_default());
            if (!w.bitmap) return fail("could not decode image");
            bitmaps.emplace_back(w.bitmap);
        }
        std::vector<const mtmd_bitmap *> ptrs;
        for (auto &b : bitmaps) ptrs.push_back(b.ptr.get());
        mtmd_input_text txt{rest.c_str(), rest.size(), prefix.empty(), true};
        mtmd::input_chunks_ptr chunks(mtmd_input_chunks_init());
        if (mtmd_tokenize(e->mtmd, chunks.get(), &txt, ptrs.data(), ptrs.size()) != 0) return fail("mtmd_tokenize failed");
        prompt_tokens = (int) prefix_tokens.size() + (int) mtmd_helper_get_n_tokens(chunks.get());
        if (prompt_tokens + 16 > e->n_ctx) return fail("prompt too long for context (" + std::to_string(prompt_tokens) + " tokens)");
        llama_pos new_n_past = n_past;
        if (mtmd_helper_eval_chunks(e->mtmd, e->ctx, chunks.get(), n_past, 0, e->n_batch, true, &new_n_past) != 0)
            return fail("image/prompt evaluation failed");
        n_past = new_n_past;
    }
    auto t1 = clk::now();

    common_params_sampling sp;
    sp.temp = temp;
    sp.top_p = top_p;
    sp.top_k = top_k;
    sp.min_p = 0.0f;
    if (seed >= 0) sp.seed = (uint32_t) seed;
    common_sampler_ptr smpl(common_sampler_init(e->model, sp));

    std::string text, pending;
    std::string stop = "length";
    int gen = 0;
    common_batch batch(e->ctx);
    int budget = std::min((int) max_tokens, e->n_ctx - n_past - 1);
    for (; gen < budget; ++gen) {
        if (e->cancel.load()) { stop = "cancelled"; break; }
        llama_token id = common_sampler_sample(smpl.get(), e->ctx, -1);
        common_sampler_accept(smpl.get(), id, true);
        if (llama_vocab_is_eog(vocab, id)) { stop = "eog"; break; }

        pending += common_token_to_piece(vocab, id, false);
        size_t tail = incomplete_utf8_tail(pending);
        if (pending.size() > tail) {
            std::string ready = pending.substr(0, pending.size() - tail);
            pending.erase(0, pending.size() - tail);
            text += ready;
            jstring js = to_jstring(env, ready);
            jboolean keep_going = env->CallBooleanMethod(callback, on_token, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) { env->ExceptionClear(); stop = "callback_error"; break; }
            if (!keep_going) { stop = "callback"; ++gen; break; }
        }

        batch.clear();
        batch.add(id, n_past, 0, true);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { stop = "decode_error"; break; }
        ++n_past;
    }
    auto t2 = clk::now();

    using ms = std::chrono::milliseconds;
    result["text"] = text;
    result["stop"] = stop;
    result["prompt_tokens"] = prompt_tokens;
    result["cached_tokens"] = cached;
    result["prefill_ms"] = std::chrono::duration_cast<ms>(t1 - t0).count();
    result["gen_tokens"] = gen;
    result["gen_ms"] = std::chrono::duration_cast<ms>(t2 - t1).count();
    return to_jstring(env, dump(result));
}

}  // extern "C"

// ---- persisted system-prompt cache -------------------------------------------------------
// File layout: "I3DP" | u32 n_tokens | tokens[n] | u64 n_state | state bytes.

extern "C" JNIEXPORT jboolean JNICALL
Java_app_imagine3d_llama_LlamaNative_prefixSave(JNIEnv *env, jclass, jlong handle, jstring jpath) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e || e->prefix_state.empty()) return JNI_FALSE;
    std::string path = jstr(env, jpath);
    std::string tmp = path + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return JNI_FALSE;
    uint32_t n = (uint32_t) e->prefix_tokens.size();
    uint64_t s = e->prefix_state.size();
    bool ok = fwrite("I3DP", 1, 4, f) == 4 && fwrite(&n, sizeof(n), 1, f) == 1 &&
              fwrite(e->prefix_tokens.data(), sizeof(llama_token), n, f) == n && fwrite(&s, sizeof(s), 1, f) == 1 &&
              fwrite(e->prefix_state.data(), 1, s, f) == s;
    ok = (fclose(f) == 0) && ok;
    if (ok) ok = rename(tmp.c_str(), path.c_str()) == 0;
    if (!ok) remove(tmp.c_str());
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_imagine3d_llama_LlamaNative_prefixLoad(JNIEnv *env, jclass, jlong handle, jstring jpath) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;
    std::string path = jstr(env, jpath);
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return -1;
    char magic[4];
    uint32_t n = 0;
    uint64_t s = 0;
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
    bool ok = fread(magic, 1, 4, f) == 4 && memcmp(magic, "I3DP", 4) == 0 && fread(&n, sizeof(n), 1, f) == 1 && n < 1000000;
    if (ok) { tokens.resize(n); ok = fread(tokens.data(), sizeof(llama_token), n, f) == n; }
    if (ok) ok = fread(&s, sizeof(s), 1, f) == 1 && s < (8ull << 30);
    if (ok) { state.resize(s); ok = fread(state.data(), 1, s, f) == s; }
    fclose(f);
    if (!ok) return -1;
    e->prefix_tokens = std::move(tokens);
    e->prefix_state = std::move(state);
    return (jint) n;
}
