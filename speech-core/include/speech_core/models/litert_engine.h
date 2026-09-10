#pragma once

#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_layout.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_model_types.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_types.h"

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <initializer_list>
#include <memory>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#ifndef LOG_TAG
#define LOG_TAG "Speech"
#endif
#ifndef LOGI
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif
#else
#include <cstdio>
#ifndef LOGI
#define LOGI(...) do { std::fprintf(stderr, "[speech] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif
#ifndef LOGE
#define LOGE(...) do { std::fprintf(stderr, "[speech ERROR] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif
#endif

namespace speech_core {

inline void litert_check(LiteRtStatus status, const char* what) {
    if (status != kLiteRtStatusOk) {
        const char* status_text = LiteRtGetStatusString(status);
        std::string message = std::string("LiteRT: ") + what
            + " failed (status=" + std::to_string(status);
        if (status_text && *status_text) {
            message += ", ";
            message += status_text;
        }
        message += ")";
        throw std::runtime_error(message);
    }
}

/// Build a ranked tensor type from element dtype + static dims (rank ≤ 8).
inline LiteRtRankedTensorType make_type(LiteRtElementType dtype,
                                         std::initializer_list<int32_t> dims) {
    LiteRtRankedTensorType t{};
    t.element_type       = dtype;
    t.layout.rank        = static_cast<unsigned int>(dims.size());
    t.layout.has_strides = false;
    size_t i = 0;
    for (int32_t d : dims) t.layout.dimensions[i++] = d;
    return t;
}

/// Total element count from a layout. Assumes all dims are static.
inline size_t layout_element_count(const LiteRtLayout& l) {
    size_t n = 1;
    for (unsigned int i = 0; i < l.rank; ++i) {
        n *= static_cast<size_t>(l.dimensions[i]);
    }
    return n;
}

/// RAII wrapper around a *managed* host-memory TensorBuffer.
/// We tried wrapping caller-owned memory via `LiteRtCreateTensorBufferFromHostMemory`
/// but it returns `kLiteRtStatusErrorMemoryAllocationFailure` for the sizes
/// VoxCPM2 uses — LiteRT v2.1.x's host-memory backend has stricter alignment
/// requirements than std::vector::data() guarantees. The managed-allocation
/// path picks aligned memory internally; we just lock/write before Run and
/// lock/read after. Buffer lifetime is per-Invoke (cheap to construct).
class LiteRtHostBuffer {
public:
    /// Allocate a managed host-memory tensor buffer of the given size and
    /// (optionally) seed it with the contents of `seed` (e.g. an input).
    LiteRtHostBuffer(LiteRtEnvironment env,
                     const LiteRtRankedTensorType& type,
                     size_t bytes,
                     const void* seed = nullptr) : bytes_(bytes) {
        litert_check(LiteRtCreateManagedTensorBuffer(
                         env, kLiteRtTensorBufferTypeHostMemory, &type, bytes, &buf_),
                     "CreateManagedTensorBuffer");
        if (seed) write(seed, bytes);
    }
    /// Allocate using the exact buffer requirements returned by CompiledModel.
    /// Accelerator runtimes (notably Qualcomm HTP and Android GPU backends) may
    /// require alignment or a non-host backing type. Using a generic HostMemory
    /// buffer ignores that contract and can produce invalid accelerator results.
    LiteRtHostBuffer(LiteRtEnvironment env,
                     const LiteRtRankedTensorType& type,
                     LiteRtTensorBufferRequirements requirements,
                     const void* seed = nullptr) {
        litert_check(LiteRtCreateManagedTensorBufferFromRequirements(
                         env, &type, requirements, &buf_),
                     "CreateManagedTensorBufferFromRequirements");
        litert_check(LiteRtGetTensorBufferPackedSize(buf_, &bytes_),
                     "GetTensorBufferPackedSize");
        if (seed) write(seed, bytes_);
    }

    ~LiteRtHostBuffer() { if (buf_) LiteRtDestroyTensorBuffer(buf_); }

    LiteRtHostBuffer(const LiteRtHostBuffer&)            = delete;
    LiteRtHostBuffer& operator=(const LiteRtHostBuffer&) = delete;
    LiteRtHostBuffer(LiteRtHostBuffer&& o) noexcept
        : buf_(o.buf_), bytes_(o.bytes_) { o.buf_ = nullptr; }
    LiteRtHostBuffer& operator=(LiteRtHostBuffer&&)      = delete;

    /// Copy `bytes` from `src` into this buffer's host memory.
    void write(const void* src, size_t bytes) {
        if (bytes > bytes_) throw std::runtime_error("LiteRT TensorBuffer write exceeds packed size");
        void* p = nullptr;
        litert_check(LiteRtLockTensorBuffer(buf_, &p, kLiteRtTensorBufferLockModeWrite),
                     "LockTensorBuffer(write)");
        std::memcpy(p, src, bytes);
        litert_check(LiteRtUnlockTensorBuffer(buf_), "UnlockTensorBuffer");
    }

    /// Copy `bytes` from this buffer's host memory into `dst`.
    void read(void* dst, size_t bytes) const {
        if (bytes > bytes_) throw std::runtime_error("LiteRT TensorBuffer read exceeds packed size");
        void* p = nullptr;
        litert_check(LiteRtLockTensorBuffer(buf_, &p, kLiteRtTensorBufferLockModeRead),
                     "LockTensorBuffer(read)");
        std::memcpy(dst, p, bytes);
        litert_check(LiteRtUnlockTensorBuffer(buf_), "UnlockTensorBuffer");
    }

    size_t             byte_size() const { return bytes_; }
    LiteRtTensorBuffer raw()       const { return buf_; }

private:
    LiteRtTensorBuffer buf_   = nullptr;
    size_t             bytes_ = 0;
};

/// Process-wide LiteRT environment + per-model load helper.
///
/// Backed by `libLiteRt.{so,dll,dylib}` from Google's `ai-edge-litert` package
/// (extracted from the PyPI wheel by `scripts/fetch_litert.sh` locally; pulled
/// the same way in CI). Replaces the legacy `libtensorflowlite_c` path — the
/// old TFLite C API in our v2.18-v2.20 source builds couldn't load >2 GB
/// models (VoxCPM2's text_prefill is 2.08 GB).
class LiteRTEngine {
public:
    static LiteRTEngine& get() {
        static LiteRTEngine instance;
        return instance;
    }

    /// Configure accelerator discovery before the first CompiledModel is created.
    /// Android extracts the runtime/accelerator .so files into nativeLibraryDir.
    /// LiteRT's CompiledModel API needs that directory explicitly in order to
    /// discover out-of-tree accelerator libraries such as ClGl GPU.
    void configure_android_accelerators(const std::string& native_library_dir,
                                        const std::string& compiler_cache_dir) {
        if (native_library_dir.empty()) return;
        if (env_) {
            // All app backends use the same extracted native library directory.
            // Reconfiguration after environment creation is intentionally ignored.
            return;
        }
        runtime_library_dir_ = native_library_dir;
        compiler_plugin_library_dir_ = native_library_dir;
        dispatch_library_dir_ = native_library_dir;
        compiler_cache_dir_ = compiler_cache_dir;
    }

    LiteRtEnvironment env() {
        if (!env_) {
            std::vector<LiteRtEnvOption> options;
            auto add_string = [&](LiteRtEnvOptionTag tag, const std::string& value) {
                if (value.empty()) return;
                LiteRtEnvOption o{};
                o.tag = tag;
                o.value.type = kLiteRtAnyTypeString;
                o.value.str_value = value.c_str();
                options.push_back(o);
            };
            add_string(kLiteRtEnvOptionTagRuntimeLibraryDir, runtime_library_dir_);
            add_string(kLiteRtEnvOptionTagCompilerPluginLibraryDir, compiler_plugin_library_dir_);
            add_string(kLiteRtEnvOptionTagDispatchLibraryDir, dispatch_library_dir_);
            add_string(kLiteRtEnvOptionTagCompilerCacheDir, compiler_cache_dir_);

            // Register all Android accelerators needed by this app. Accelerator
            // selection itself remains strict in load(): GPU requests GPU only,
            // Qualcomm NPU requests NPU only, and no CPU/GPU fallback bit is set.
            // The runtime/compiler/dispatch search paths above point at the APK's
            // nativeLibraryDir, where the ClGl accelerator and Qualcomm QAIRT
            // runtime libraries are packaged.
            LiteRtEnvOption accel{};
            accel.tag = kLiteRtEnvOptionTagAutoRegisterAccelerators;
            accel.value.type = kLiteRtAnyTypeInt;
            accel.value.int_value = static_cast<int64_t>(
                kLiteRtHwAcceleratorCpu | kLiteRtHwAcceleratorGpu | kLiteRtHwAcceleratorNpu);
            options.push_back(accel);

            litert_check(LiteRtCreateEnvironment(static_cast<int>(options.size()),
                                                  options.data(), &env_),
                         "CreateEnvironment");
            LOGI("LiteRT environment: native libs=%s cache=%s",
                 runtime_library_dir_.empty() ? "<default>" : runtime_library_dir_.c_str(),
                 compiler_cache_dir_.empty() ? "<none>" : compiler_cache_dir_.c_str());
        }
        return env_;
    }

    /// Load a `.tflite` and compile it for CPU execution.
    ///
    /// `out_model` and `out_compiled` are caller-owned. Free in reverse order:
    /// `LiteRtDestroyCompiledModel(compiled)` first, then `LiteRtDestroyModel(model)`.
    void load(const std::string& path,
              bool hw_accel,
              LiteRtModel* out_model,
              LiteRtCompiledModel* out_compiled) {
        load(path, hw_accel ? kLiteRtHwAcceleratorGpu : kLiteRtHwAcceleratorCpu, out_model, out_compiled);
    }

    /// Load a `.tflite` and compile it for an explicit accelerator.
    /// CPU is always available. GPU/NPU require the corresponding Android
    /// accelerator/runtime libraries to be packaged with the app/device.
    void load(const std::string& path,
              LiteRtHwAccelerators accelerator,
              LiteRtModel* out_model,
              LiteRtCompiledModel* out_compiled,
              bool allow_cpu_fallback = false) {
        LOGI("Loading LiteRT model: %s",
             path.substr(path.find_last_of('/') + 1).c_str());

        LiteRtEnvironment environment = env();
        LiteRtModel m = nullptr;
        // LiteRtCreateModelFromBuffer keeps a zero-copy view of the caller's
        // bytes. It is reserved for exceptionally large desktop graphs whose
        // file-size handling can overflow on Windows. Normal graphs use LiteRT's
        // file-backed loader, including Android.
        //
        // IMPORTANT: libLiteRt.so and the vendored declarations are both pinned
        // to LiteRT 2.1.4. Its model loader ABI does not take LiteRtEnvironment.
        // Passing one (as newer headers require) shifts all following arm64 C
        // arguments: the file loader reports FileIO and the buffer loader can
        // abort while treating the wrong address as a model buffer.
        constexpr std::uint64_t kBufferThreshold = std::uint64_t{1} << 30;  // 1 GiB
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        if (!f) {
            throw std::runtime_error("LiteRT: cannot open " + path);
        }
        const std::ifstream::pos_type end = f.tellg();
        if (end == std::ifstream::pos_type(-1) || end <= std::ifstream::pos_type(0)) {
            throw std::runtime_error("LiteRT: invalid or empty model file " + path);
        }
        const std::uint64_t size = static_cast<std::uint64_t>(end);
        const bool use_buffer_loader = size > kBufferThreshold;
        const std::string model_name = path.substr(path.find_last_of('/') + 1);
        LOGI("LiteRT model source: file=%s bytes=%llu loader=%s",
             model_name.c_str(),
             static_cast<unsigned long long>(size),
             use_buffer_loader ? "buffer" : "file");
        if (use_buffer_loader) {
            auto it = retained_buffers_.find(path);
            const std::vector<char>* buf_ptr = nullptr;
            if (it != retained_buffers_.end()) {
                buf_ptr = it->second.get();
            } else {
                auto buf = std::make_unique<std::vector<char>>(static_cast<size_t>(size));
                f.seekg(0);
                f.read(buf->data(), static_cast<std::streamsize>(size));
                if (!f) {
                    throw std::runtime_error("LiteRT: read failed for " + path);
                }
                buf_ptr = buf.get();
                retained_buffers_.emplace(path, std::move(buf));
            }
            litert_check(LiteRtCreateModelFromBuffer(buf_ptr->data(), buf_ptr->size(), &m),
                         "CreateModelFromBuffer");
        } else {
            f.close();
            litert_check(LiteRtCreateModelFromFile(path.c_str(), &m), "CreateModelFromFile");
        }

        // Build compile options for the requested accelerator. LiteRT rejects
        // a NULL options pointer (kLiteRtStatusErrorInvalidArgument). Android
        // builds may package GPU and vendor NPU runtimes; callers handle a
        // failed accelerator compile and may fall back to CPU.
        LiteRtOptions opts = nullptr;
        litert_check(LiteRtCreateOptions(&opts), "CreateOptions");
        LiteRtHwAccelerators requested = accelerator;
        if (allow_cpu_fallback && accelerator != kLiteRtHwAcceleratorCpu) {
            requested = static_cast<LiteRtHwAccelerators>(
                static_cast<int>(accelerator) | static_cast<int>(kLiteRtHwAcceleratorCpu));
        }
        LiteRtStatus s = LiteRtSetOptionsHardwareAccelerators(opts, requested);
        if (s != kLiteRtStatusOk) {
            LiteRtDestroyOptions(opts);
            LiteRtDestroyModel(m);
            litert_check(s, "SetOptionsHardwareAccelerators");
        }

        if (accelerator == kLiteRtHwAcceleratorNpu) {
            // LiteRT's Qualcomm compiler consumes an opaque "qualcomm" TOML
            // payload. This is the same public contract produced by
            // LrtGetOpaqueQualcommOptionsData in LiteRT 2.1.4. Keep the
            // official sample's HIGH_PERFORMANCE mode, while disabling the two
            // documented accuracy-risk optimizations. The earlier O1 experiment
            // did not fix Soniqo VE NaN/Inf and was slower, so this multi-model
            // comparison returns to the normal aggressive O3 inference mode.
            constexpr char kQualcommToml[] =
                "log_level = 3\n"
                "profiling = 1\n"
                "use_conv_hmx = false\n"
                "use_fold_relu = false\n"
                "htp_performance_mode = 3\n"
                "optimization_level = 2\n";
            void* payload = std::malloc(sizeof(kQualcommToml));
            if (!payload) {
                LiteRtDestroyOptions(opts);
                LiteRtDestroyModel(m);
                throw std::bad_alloc();
            }
            std::memcpy(payload, kQualcommToml, sizeof(kQualcommToml));

            LiteRtOpaqueOptions qualcomm = nullptr;
            s = LiteRtCreateOpaqueOptions(
                "qualcomm", payload,
                +[](void* p) { std::free(p); },
                &qualcomm);
            if (s != kLiteRtStatusOk) {
                std::free(payload);
                LiteRtDestroyOptions(opts);
                LiteRtDestroyModel(m);
                litert_check(s, "CreateOpaqueOptions(Qualcomm)");
            }
            s = LiteRtAddOpaqueOptions(opts, qualcomm);
            if (s != kLiteRtStatusOk) {
                LiteRtDestroyOpaqueOptions(qualcomm);
                LiteRtDestroyOptions(opts);
                LiteRtDestroyModel(m);
                litert_check(s, "AddOpaqueOptions(Qualcomm)");
            }
        }

        LiteRtCompiledModel c = nullptr;
        s = LiteRtCreateCompiledModel(environment, m, opts, &c);
        LiteRtDestroyOptions(opts);
        if (s != kLiteRtStatusOk) {
            LiteRtDestroyModel(m);
            litert_check(s, "CreateCompiledModel");
        }
        *out_model    = m;
        *out_compiled = c;
    }

    /// Release the retained file buffer for `path`, if any.
    ///
    /// The caller MUST have already destroyed any `LiteRtModel` created from
    /// this path -- the model holds a zero-copy pointer into the buffer, so
    /// freeing the buffer while a model still references it is undefined
    /// behaviour. This is the lazy-unload path used by the VoxCPM2 wrapper
    /// to drop the ~4.1 GiB FP16 text_prefill graph between synthesize()
    /// calls, freeing node headroom (the FP16 bundle needs ~10 GiB resident;
    /// servers run the ONNX/CUDA path instead, so LiteRT is the edge/desktop
    /// path). No-op when `path` was below the
    /// CreateModelFromBuffer threshold (1 GiB) and therefore was never
    /// retained.
    void release_buffer(const std::string& path) {
        retained_buffers_.erase(path);
    }

private:
    LiteRTEngine() = default;
    ~LiteRTEngine() { if (env_) LiteRtDestroyEnvironment(env_); }
    LiteRTEngine(const LiteRTEngine&)            = delete;
    LiteRTEngine& operator=(const LiteRTEngine&) = delete;

    LiteRtEnvironment env_ = nullptr;
    std::string runtime_library_dir_;
    std::string compiler_plugin_library_dir_;
    std::string dispatch_library_dir_;
    std::string compiler_cache_dir_;
    // Backing storage for models loaded via LiteRtCreateModelFromBuffer,
    // keyed by file path. LiteRT retains a zero-copy pointer into each
    // buffer for the model's lifetime, so buffers must outlive any models
    // created from them. The engine is a singleton, so this naturally
    // lives until process exit. Keying by path means re-loading the same
    // file reuses the existing buffer instead of allocating another copy
    // (matters for test suites that re-instantiate big-model wrappers).
    std::unordered_map<std::string, std::unique_ptr<std::vector<char>>> retained_buffers_;
};

}  // namespace speech_core
