#include "speech_core/models/litert_supertonic_tts.h"
#include "tflite_c_api_minimal.h"
#include "speech_core/models/litert_engine.h"

#include "speech_core/util/json.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <future>
#include <iomanip>
#include <deque>
#include <limits>
#include <mutex>
#include <random>
#include <sstream>
#include <unordered_map>
#include <utility>
#include <stdexcept>
#if defined(__linux__)
#include <sched.h>
#include <unistd.h>
#endif

namespace speech_core {
namespace {
constexpr int kSampleRateConst = 44100;
using SteadyClock = std::chrono::steady_clock;

double elapsed_ms(SteadyClock::time_point a, SteadyClock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

// Local UTF-8 helpers.
//
// supertonic_tokenizer.cpp keeps these in its private anonymous namespace, so
// they are intentionally not visible from this translation unit.  The
// Supertonic synthesis path also needs codepoint-level splitting when the
// duration predictor would otherwise truncate a chunk.
std::vector<char32_t> utf8_to_u32(const std::string& s) {
    std::vector<char32_t> out;
    out.reserve(s.size());

    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        char32_t cp = 0;
        int len = 0;

        if ((c & 0x80u) == 0x00u) {
            cp = c; len = 1;
        } else if ((c & 0xE0u) == 0xC0u) {
            cp = c & 0x1Fu; len = 2;
        } else if ((c & 0xF0u) == 0xE0u) {
            cp = c & 0x0Fu; len = 3;
        } else if ((c & 0xF8u) == 0xF0u) {
            cp = c & 0x07u; len = 4;
        } else {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        if (i + static_cast<size_t>(len) > n) {
            out.push_back(0xFFFD);
            break;
        }

        bool ok = true;
        for (int k = 1; k < len; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0u) != 0x80u) {
                ok = false;
                break;
            }
            cp = (cp << 6) | (cc & 0x3Fu);
        }

        if (!ok) {
            out.push_back(0xFFFD);
            ++i;
            continue;
        }

        out.push_back(cp);
        i += static_cast<size_t>(len);
    }

    return out;
}

void append_u32(std::string& s, char32_t cp) {
    if (cp < 0x80) {
        s.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        s.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        s.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        s.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        s.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

std::string u32_to_utf8(const std::vector<char32_t>& v) {
    std::string s;
    s.reserve(v.size() * 2);
    for (const char32_t cp : v) append_u32(s, cp);
    return s;
}

// Choose a semantic split point near the middle of a chunk.
// For whitespace languages (including Korean), never split inside an eojeol/word
// merely because the duration predictor overflowed. Punctuation stays with the
// left side. Only fall back to a codepoint split when there is no safe boundary
// (e.g. an unbroken URL or a long Japanese run without punctuation).
size_t choose_semantic_split(const std::vector<char32_t>& cps) {
    if (cps.size() < 2) return 0;

    const size_t target = cps.size() / 2;
    size_t best = 0;
    size_t best_distance = std::numeric_limits<size_t>::max();

    auto consider = [&](size_t split) {
        if (split == 0 || split >= cps.size()) return;
        // Avoid producing a tiny fragment when a more balanced boundary exists.
        const size_t left = split;
        const size_t right = cps.size() - split;
        if (left < 2 || right < 2) return;
        const size_t distance = left > target ? left - target : target - left;
        if (distance < best_distance) {
            best_distance = distance;
            best = split;
        }
    };

    for (size_t i = 0; i < cps.size(); ++i) {
        const char32_t c = cps[i];
        const bool whitespace =
            c == U' ' || c == U'\t' || c == U'\n' || c == U'\r';
        const bool punctuation =
            c == U',' || c == U';' || c == U':' ||
            c == U'.' || c == U'!' || c == U'?' ||
            c == U'，' || c == U'；' || c == U'：' ||
            c == U'。' || c == U'！' || c == U'？' ||
            c == U'、';
        if (whitespace) {
            // Drop the whitespace from the boundary itself.
            consider(i);
        } else if (punctuation) {
            // Keep sentence/clause punctuation on the left.
            consider(i + 1);
        }
    }

    if (best != 0) return best;
    return target;
}

// Find the active audio range for profiling only.  This does NOT trim or
// modify the waveform.  A conservative threshold avoids classifying the
// low-energy edges of Korean/Chinese phonemes as silence.
std::pair<size_t, size_t> active_range(
    const std::vector<float>& pcm, size_t begin, size_t end) {
    begin = std::min(begin, pcm.size());
    end = std::min(end, pcm.size());
    if (begin >= end) return {begin, begin};

    float peak = 0.0f;
    for (size_t i = begin; i < end; ++i) {
        peak = std::max(peak, std::fabs(pcm[i]));
    }
    if (peak <= 1.0e-6f) return {end, end};

    // Keep the threshold deliberately conservative because this is only a
    // diagnostic/profile measurement.
    const float threshold = std::max(1.0e-4f, peak * 0.005f);

    size_t first = begin;
    while (first < end && std::fabs(pcm[first]) < threshold) ++first;

    size_t last = end;
    while (last > first && std::fabs(pcm[last - 1]) < threshold) --last;

    return {first, last};
}


int graph_latent_frames() {
    if (const char* e = std::getenv("SUPERTONIC_LATENT_FRAMES")) {
        const int v = std::atoi(e);
        if (v > 0) return v;
    }
    return 64;
}

bool is_cpu_backend(LiteRTSupertonicTts::Backend backend) {
    return backend == LiteRTSupertonicTts::Backend::Cpu ||
           backend == LiteRTSupertonicTts::Backend::CpuFp16;
}

long long read_positive_integer_file(const std::string& path) {
    std::ifstream in(path);
    long long value = 0;
    if (in >> value && value > 0) return value;
    return 0;
}

// XNNPACK creates its pthreadpool while the delegate is constructed. Linux/Android
// worker threads inherit the creating thread's affinity mask, so briefly pinning the
// creator to the highest-capacity CPUs gives the XNNPACK pool a topology-aware mask
// without permanently pinning the Android caller thread. If sysfs topology metadata
// is unavailable we leave scheduling completely to Android.
class ScopedTopCpuAffinity {
public:
    ScopedTopCpuAffinity(int requested_threads, const std::string& graph_name) {
#if defined(__linux__)
        CPU_ZERO(&previous_);
        if (sched_getaffinity(0, sizeof(previous_), &previous_) != 0) return;

        struct Entry { int cpu; long long score; };
        std::vector<Entry> entries;
        bool used_capacity = false;
        bool used_frequency = false;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (!CPU_ISSET(cpu, &previous_)) continue;
            const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/";
            long long score = read_positive_integer_file(base + "cpu_capacity");
            if (score > 0) {
                used_capacity = true;
            } else {
                score = read_positive_integer_file(base + "cpufreq/cpuinfo_max_freq");
                if (score <= 0) score = read_positive_integer_file(base + "cpufreq/scaling_max_freq");
                if (score > 0) used_frequency = true;
            }
            entries.push_back({cpu, score});
        }
        if (entries.empty()) return;
        // If Android exposes no useful topology score, do not invent one from CPU ids.
        if (!used_capacity && !used_frequency) {
            LOGI("[CPU-AFFINITY] graph=%s mode=android-default reason=no-capacity-metadata",
                 graph_name.c_str());
            return;
        }
        std::sort(entries.begin(), entries.end(), [](const Entry& a, const Entry& b) {
            if (a.score != b.score) return a.score > b.score;
            return a.cpu < b.cpu;
        });
        const int wanted = std::max(1, std::min(requested_threads, static_cast<int>(entries.size())));
        cpu_set_t selected;
        CPU_ZERO(&selected);
        std::ostringstream chosen;
        for (int i = 0; i < wanted; ++i) {
            CPU_SET(entries[static_cast<size_t>(i)].cpu, &selected);
            if (i) chosen << ',';
            chosen << entries[static_cast<size_t>(i)].cpu;
        }
        if (sched_setaffinity(0, sizeof(selected), &selected) == 0) {
            active_ = true;
            LOGI("[CPU-AFFINITY] graph=%s mode=topology-aware threads=%d cpus=%s score=%s",
                 graph_name.c_str(), wanted, chosen.str().c_str(),
                 used_capacity ? "capacity" : "max_freq");
        } else {
            LOGI("[CPU-AFFINITY] graph=%s mode=android-default reason=sched_setaffinity-failed",
                 graph_name.c_str());
        }
#else
        (void)requested_threads;
        (void)graph_name;
#endif
    }

    ~ScopedTopCpuAffinity() {
#if defined(__linux__)
        if (active_) sched_setaffinity(0, sizeof(previous_), &previous_);
#endif
    }

private:
#if defined(__linux__)
    cpu_set_t previous_{};
#endif
    bool active_ = false;
};

// Persistent XNNPACK cache files are runtime-build sensitive. Keep the
// diagnostic B cache in its own ABI generation so it never consumes cache
// files written by A or by older experimental LiteRT runtimes.
constexpr const char* kSupertonicXnnpackPersistentCacheAbi =
    "litert22-diagB-overlap-xnncache-v3-upstream-lifecycle-20260902";

std::string make_xnnpack_weight_cache_path(
    const std::string& cache_root,
    const std::string& model_path,
    const std::string& graph_name,
    LiteRTSupertonicTts::Backend backend) {
    namespace fs = std::filesystem;
    try {
        fs::path root = cache_root.empty()
            ? (fs::path(model_path).parent_path() / ".xnnpack-cache" /
               kSupertonicXnnpackPersistentCacheAbi)
            : (fs::path(cache_root) / "xnnpack-weights" /
               kSupertonicXnnpackPersistentCacheAbi);
        fs::create_directories(root);
        const fs::path model(model_path);
        const auto size = fs::exists(model) ? fs::file_size(model) : 0;
        long long stamp = 0;
        if (fs::exists(model)) stamp = fs::last_write_time(model).time_since_epoch().count();
        std::ostringstream name;
        name << graph_name << '-' << size << '-' << stamp << '-'
             << (backend == LiteRTSupertonicTts::Backend::CpuFp16 ? "fp16" : "fp32")
             << ".xnncache";
        return (root / name.str()).string();
    } catch (const std::exception& e) {
        LOGI("[XNNPACK-CACHE] graph=%s enabled=0 reason=%s", graph_name.c_str(), e.what());
        return {};
    }
}

std::uintmax_t xnnpack_cache_bytes(const std::string& path) noexcept {
    if (path.empty()) return 0;
    try {
        std::error_code ec;
        if (!std::filesystem::is_regular_file(path, ec) || ec) return 0;
        const auto bytes = std::filesystem::file_size(path, ec);
        return ec ? 0 : bytes;
    } catch (...) {
        return 0;
    }
}

std::vector<float> parse_float_array(const std::string& s, size_t& i) {
    std::vector<float> out;
    json::skip_ws(s, i);
    if (i >= s.size() || s[i] != '[') return out;
    int depth = 0;
    while (i < s.size()) {
        const char c = s[i];
        if (c == '[') { ++depth; ++i; continue; }
        if (c == ']') { --depth; ++i; if (depth == 0) break; continue; }
        if (c == ',' || c == ' ' || c == '\t' || c == '\n' || c == '\r') { ++i; continue; }
        const std::string v = json::parse_value_raw(s, i);
        if (!v.empty()) out.push_back(std::strtof(v.c_str(), nullptr));
    }
    return out;
}

std::vector<float> extract_style(const std::string& text, const std::string& key) {
    size_t i = 0;
    json::skip_ws(text, i);
    if (i >= text.size() || text[i] != '{') return {};
    ++i;
    while (i < text.size()) {
        json::skip_ws(text, i);
        if (text[i] == '}') break;
        if (text[i] == ',') { ++i; continue; }
        const std::string k = json::parse_string(text, i);
        json::skip_ws(text, i);
        if (i < text.size() && text[i] == ':') ++i;
        json::skip_ws(text, i);
        if (k == key && i < text.size() && text[i] == '[') {
            return parse_float_array(text, i);
        }
        if (k == key && i < text.size() && text[i] == '{') {
            ++i;
            while (i < text.size()) {
                json::skip_ws(text, i);
                if (text[i] == '}') { ++i; break; }
                if (text[i] == ',') { ++i; continue; }
                const std::string kk = json::parse_string(text, i);
                json::skip_ws(text, i);
                if (i < text.size() && text[i] == ':') ++i;
                json::skip_ws(text, i);
                if (kk == "data" && i < text.size() && text[i] == '[') return parse_float_array(text, i);
                json::skip_value(text, i);
            }
            return {};
        }
        json::skip_value(text, i);
    }
    return {};
}


void tflite_check(TfLiteStatus status, const char* operation) {
    if (status != kTfLiteOk) {
        throw std::runtime_error(std::string("Supertonic TFLite: ") + operation + " failed");
    }
}

bool tensor_has_shape(const TfLiteTensor* tensor, std::initializer_list<int> expected) {
    if (!tensor || TfLiteTensorNumDims(tensor) != static_cast<int>(expected.size())) return false;
    int i = 0;
    for (int d : expected) {
        if (TfLiteTensorDim(tensor, i++) != d) return false;
    }
    return true;
}

TfLiteTensor* find_input(TfLiteInterpreter* interpreter, std::initializer_list<int> shape) {
    TfLiteTensor* result = nullptr;
    const int count = TfLiteInterpreterGetInputTensorCount(interpreter);
    for (int i = 0; i < count; ++i) {
        auto* t = TfLiteInterpreterGetInputTensor(interpreter, i);
        if (!tensor_has_shape(t, shape)) continue;
        if (result) throw std::runtime_error("Supertonic: ambiguous input shape");
        result = t;
    }
    if (!result) throw std::runtime_error("Supertonic: required input tensor missing");
    return result;
}

const TfLiteTensor* find_output(TfLiteInterpreter* interpreter, std::initializer_list<int> shape) {
    const TfLiteTensor* result = nullptr;
    const int count = TfLiteInterpreterGetOutputTensorCount(interpreter);
    for (int i = 0; i < count; ++i) {
        const auto* t = TfLiteInterpreterGetOutputTensor(interpreter, i);
        if (!tensor_has_shape(t, shape)) continue;
        if (result) throw std::runtime_error("Supertonic: ambiguous output shape");
        result = t;
    }
    if (!result) throw std::runtime_error("Supertonic: required output tensor missing");
    return result;
}

struct Graph {
    using Backend = LiteRTSupertonicTts::Backend;

    Backend backend = Backend::Cpu;
    std::string name;
    std::string model_path;
    std::string xnnpack_weight_cache_path;
    void* xnnpack_weight_cache_provider = nullptr;
    std::mutex* xnnpack_weight_cache_mutex = nullptr;
    int cpu_threads = 4;
    bool deep_profiler = false;
    std::string deep_profiler_dir;
    std::vector<double> deep_invoke_ms;

    // CPU reference path: legacy TfLite C API + XNNPACK. This path is kept
    // unchanged because it is the already-verified fast baseline on Android.
    TfLiteModel* model = nullptr;
    bool owns_model = false;
    TfLiteOpaqueDelegate* delegate = nullptr;
    bool owns_delegate = false;
    TfLiteInterpreter* interpreter = nullptr;
    TfLiteSignatureRunner* signature_runner = nullptr;
    std::string cpu_signature_key;
    std::vector<std::string> cpu_signature_input_names;
    std::vector<std::string> cpu_signature_output_names;

    // Fixed-shape CPU graphs never resize tensors after AllocateTensors().
    // Cache the hot named input/output tensor pointers after first resolution
    // instead of scanning every tensor on every VE flow step.
    std::unordered_map<std::string, TfLiteTensor*> cpu_named_inputs;
    const TfLiteTensor* cpu_cached_output = nullptr;

    // Strict accelerator path: LiteRT CompiledModel. GPU and NPU are requested
    // WITHOUT CPU/GPU fallback and are accepted only if LiteRT reports that the
    // entire graph is accelerated. This deliberately forbids mixed execution.
    LiteRtModel accel_model = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtSignature signature = nullptr;
    LiteRtParamIndex compiled_signature_index = 0;
    std::vector<LiteRtRankedTensorType> input_types;
    std::vector<LiteRtRankedTensorType> output_types;
    std::vector<std::string> input_names;
    std::vector<std::string> output_names;
    std::vector<std::unique_ptr<LiteRtHostBuffer>> input_buffers;
    std::vector<std::unique_ptr<LiteRtHostBuffer>> output_buffers;
    std::vector<uint16_t> fp16_input_scratch;
    std::vector<uint16_t> fp16_output_scratch;
    bool fully_accelerated = false;
    std::string acceleration_detail;

    ~Graph() { release_resources(); }

    Graph() = default;
    Graph(const Graph&) = delete;
    Graph& operator=(const Graph&) = delete;

    void release_resources() noexcept {
        input_buffers.clear();
        output_buffers.clear();
        fp16_input_scratch.clear();
        fp16_output_scratch.clear();
        input_types.clear();
        output_types.clear();
        input_names.clear();
        output_names.clear();
        signature = nullptr;
        compiled_signature_index = 0;
        cpu_named_inputs.clear();
        cpu_cached_output = nullptr;
        cpu_signature_input_names.clear();
        cpu_signature_output_names.clear();
        cpu_signature_key.clear();
        deep_invoke_ms.clear();
        if (signature_runner) {
            TfLiteSignatureRunnerDelete(signature_runner);
            signature_runner = nullptr;
        }
        if (interpreter) { TfLiteInterpreterDelete(interpreter); interpreter = nullptr; }
        if (delegate && owns_delegate) TfLiteXNNPackDelegateDelete(delegate);
        delegate = nullptr;
        owns_delegate = false;
        if (model && owns_model) TfLiteModelDelete(model);
        model = nullptr;
        owns_model = false;
        if (compiled) { LiteRtDestroyCompiledModel(compiled); compiled = nullptr; }
        if (accel_model) { LiteRtDestroyModel(accel_model); accel_model = nullptr; }
    }

    bool uses_interpreter_primary() const {
        return is_cpu_backend(backend);
    }
    bool uses_signature_runner() const { return signature_runner != nullptr; }

    static uint16_t float_to_half(float value) {
        uint32_t bits = 0;
        std::memcpy(&bits, &value, sizeof(bits));
        const uint32_t sign = (bits >> 16) & 0x8000u;
        const uint32_t mantissa = bits & 0x007fffffu;
        const uint32_t raw_exp = (bits >> 23) & 0xffu;
        const int32_t exp = static_cast<int32_t>(raw_exp) - 127 + 15;
        if (raw_exp == 0xffu) {
            return static_cast<uint16_t>(
                sign | (mantissa == 0 ? 0x7c00u : 0x7e00u));
        }
        if (exp <= 0) {
            if (exp < -10) return static_cast<uint16_t>(sign);
            uint32_t m = mantissa | 0x00800000u;
            const uint32_t shift = static_cast<uint32_t>(14 - exp);
            uint32_t hm = m >> shift;
            const uint32_t rb = (m >> (shift - 1)) & 1u;
            const uint32_t sticky = m & ((1u << (shift - 1)) - 1u);
            if (rb && (sticky || (hm & 1u))) ++hm;
            return static_cast<uint16_t>(sign | hm);
        }
        if (exp >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
        uint32_t hm = mantissa >> 13;
        const uint32_t round = mantissa & 0x1fffu;
        if (round > 0x1000u || (round == 0x1000u && (hm & 1u))) {
            ++hm;
            if (hm == 0x400u) {
                hm = 0;
                if (exp + 1 >= 31) {
                    return static_cast<uint16_t>(sign | 0x7c00u);
                }
                return static_cast<uint16_t>(
                    sign | (static_cast<uint32_t>(exp + 1) << 10));
            }
        }
        return static_cast<uint16_t>(
            sign | (static_cast<uint32_t>(exp) << 10) | hm);
    }

    static float half_to_float(uint16_t h) {
        const uint32_t sign = static_cast<uint32_t>(h & 0x8000u) << 16;
        uint32_t exp = (h >> 10) & 0x1fu;
        uint32_t mantissa = h & 0x03ffu;
        uint32_t bits = 0;
        if (exp == 0) {
            if (mantissa == 0) {
                bits = sign;
            } else {
                int shift = 0;
                while ((mantissa & 0x0400u) == 0) {
                    mantissa <<= 1;
                    ++shift;
                }
                mantissa &= 0x03ffu;
                const uint32_t exp32 =
                    static_cast<uint32_t>(127 - 14 - shift);
                bits = sign | (exp32 << 23) | (mantissa << 13);
            }
        } else if (exp == 31) {
            bits = sign | 0x7f800000u | (mantissa << 13);
        } else {
            const uint32_t exp32 = exp + (127 - 15);
            bits = sign | (exp32 << 23) | (mantissa << 13);
        }
        float out = 0.0f;
        std::memcpy(&out, &bits, sizeof(out));
        return out;
    }

    static size_t elem_size(LiteRtElementType t) {
        switch (t) {
            case kLiteRtElementTypeFloat32: return 4;
            case kLiteRtElementTypeInt64: return 8;
            case kLiteRtElementTypeInt32: return 4;
            case kLiteRtElementTypeFloat16: return 2;
            default: throw std::runtime_error("Supertonic: unsupported accelerated tensor type");
        }
    }

    static bool layout_has_shape(const LiteRtLayout& l, const std::vector<int>& shape) {
        if (l.rank != shape.size()) return false;
        for (size_t i = 0; i < shape.size(); ++i) if (l.dimensions[i] != shape[i]) return false;
        return true;
    }

    static bool tensor_has_shape_vec(const TfLiteTensor* tensor, const std::vector<int>& expected) {
        if (!tensor || TfLiteTensorNumDims(tensor) != static_cast<int>(expected.size())) return false;
        for (size_t i = 0; i < expected.size(); ++i)
            if (TfLiteTensorDim(tensor, static_cast<int>(i)) != expected[i]) return false;
        return true;
    }

    int find_accel_input(const std::vector<int>& shape, int occurrence = 0) const {
        int seen = 0;
        for (size_t i = 0; i < input_types.size(); ++i) {
            if (!layout_has_shape(input_types[i].layout, shape)) continue;
            if (seen++ == occurrence) return static_cast<int>(i);
        }
        throw std::runtime_error("Supertonic[" + name + "]: required accelerated input tensor missing");
    }

    int find_accel_output(const std::vector<int>& shape, int occurrence = 0) const {
        int seen = 0;
        for (size_t i = 0; i < output_types.size(); ++i) {
            if (!layout_has_shape(output_types[i].layout, shape)) continue;
            if (seen++ == occurrence) return static_cast<int>(i);
        }
        throw std::runtime_error("Supertonic[" + name + "]: required accelerated output tensor missing");
    }

    static std::string normalize_tensor_name(std::string value) {
        // SavedModel/TFLite signatures sometimes prefix semantic names with
        // "serving_default_" or append a ':0' tensor suffix. Strip those so
        // the Supertonic canonical names remain stable across conversions.
        constexpr const char* prefix = "serving_default_";
        if (value.rfind(prefix, 0) == 0) value.erase(0, std::strlen(prefix));
        const auto colon = value.find(':');
        if (colon != std::string::npos) value.erase(colon);
        return value;
    }

    int expected_generic_arg_index(const std::string& semantic) const {
        // Supertonic-3-LiteRT was exported from positional Python callables. In
        // the published models LiteRT therefore exposes generic signature names
        // such as args_0/args_1/... instead of the semantic names used by the
        // C++ pipeline. Map those positional names explicitly per graph.
        //
        // IMPORTANT: never use signature *iteration order* here. LiteRT is free
        // to return names in an order such as args_2,args_0,args_1 (observed on
        // the real duration model). The args_N suffix is the stable positional
        // identity.
        if (name == "duration") {
            if (semantic == "text_ids") return 0;
            if (semantic == "style_dp") return 1;
            if (semantic == "text_mask") return 2;
        } else if (name == "encoder") {
            if (semantic == "text_ids") return 0;
            if (semantic == "style_ttl") return 1;
            if (semantic == "text_mask") return 2;
        } else if (name == "vector_estimator") {
            // vector_estimator(xt, text_emb, style_ttl, latent_mask,
            //                  text_mask, current_step, total_step)
            if (semantic == "noisy_latent") return 0;
            if (semantic == "text_emb") return 1;
            if (semantic == "style_ttl") return 2;
            if (semantic == "latent_mask") return 3;
            if (semantic == "text_mask") return 4;
            if (semantic == "current_step") return 5;
            if (semantic == "total_step") return 6;
        } else if (name == "vocoder") {
            if (semantic == "latent") return 0;
        }
        return -1;
    }

    TfLiteTensor* find_cpu_signature_input(
        const std::string& semantic,
        const std::vector<int>& expected_shape,
        int shape_occurrence = 0) {
        if (!signature_runner) return nullptr;

        const std::string want = normalize_tensor_name(semantic);
        auto by_name = [&](const std::string& needle) -> TfLiteTensor* {
            for (const auto& actual : cpu_signature_input_names) {
                if (normalize_tensor_name(actual) != needle) continue;
                auto* t = TfLiteSignatureRunnerGetInputTensor(
                    signature_runner, actual.c_str());
                if (!tensor_has_shape_vec(t, expected_shape)) {
                    throw std::runtime_error(
                        "Supertonic[" + name + "]: signature input '" + actual +
                        "' matched but shape mismatched");
                }
                return t;
            }
            return nullptr;
        };

        if (!want.empty()) {
            if (auto* t = by_name(want)) return t;
        }
        const int arg_index = expected_generic_arg_index(semantic);
        if (arg_index >= 0) {
            if (auto* t = by_name("args_" + std::to_string(arg_index))) return t;
        }

        int seen = 0;
        for (const auto& actual : cpu_signature_input_names) {
            auto* t = TfLiteSignatureRunnerGetInputTensor(
                signature_runner, actual.c_str());
            if (!tensor_has_shape_vec(t, expected_shape)) continue;
            if (seen++ == shape_occurrence) return t;
        }
        return nullptr;
    }

    const TfLiteTensor* find_cpu_signature_output(
        const std::vector<int>& expected_shape,
        int shape_occurrence = 0) const {
        if (!signature_runner) return nullptr;
        int seen = 0;
        for (const auto& actual : cpu_signature_output_names) {
            const auto* t = TfLiteSignatureRunnerGetOutputTensor(
                signature_runner, actual.c_str());
            if (!tensor_has_shape_vec(t, expected_shape)) continue;
            if (seen++ == shape_occurrence) return t;
        }
        return nullptr;
    }

    int find_accel_input_name(const std::string& semantic,
                              const std::vector<int>& expected_shape,
                              int shape_occurrence = 0) const {
        const std::string want = normalize_tensor_name(semantic);

        // 1) Prefer a real semantic signature name when a future/re-exported
        // model contains one.
        for (size_t i = 0; i < input_names.size(); ++i) {
            if (normalize_tensor_name(input_names[i]) == want) {
                if (!layout_has_shape(input_types[i].layout, expected_shape)) {
                    throw std::runtime_error("Supertonic[" + name + "]: input '" + semantic +
                                             "' name matched but shape mismatched");
                }
                return static_cast<int>(i);
            }
        }

        // 2) Published soniqo Supertonic-3-LiteRT uses args_N positional
        // signatures. Resolve by the encoded N, not by returned list order.
        const int arg_index = expected_generic_arg_index(semantic);
        if (arg_index >= 0) {
            const std::string generic = "args_" + std::to_string(arg_index);
            for (size_t i = 0; i < input_names.size(); ++i) {
                if (normalize_tensor_name(input_names[i]) != generic) continue;
                if (!layout_has_shape(input_types[i].layout, expected_shape)) {
                    throw std::runtime_error("Supertonic[" + name + "]: mapped input '" + semantic +
                                             "' -> " + generic + " but shape mismatched");
                }
                LOGI("Supertonic[%s] mapped semantic input %s -> %s (signature slot %zu)",
                     name.c_str(), semantic.c_str(), generic.c_str(), i);
                return static_cast<int>(i);
            }
        }

        // 3) Last-resort compatibility for exports that have neither semantic
        // names nor args_N names. Shape matching is safe for unique shapes;
        // occurrence disambiguates the two {1} VE scalars.
        int seen = 0;
        for (size_t i = 0; i < input_types.size(); ++i) {
            if (!layout_has_shape(input_types[i].layout, expected_shape)) continue;
            if (seen++ == shape_occurrence) {
                LOGI("Supertonic[%s] fallback shape-mapped semantic input %s -> %s (slot %zu)",
                     name.c_str(), semantic.c_str(), input_names[i].c_str(), i);
                return static_cast<int>(i);
            }
        }

        throw std::runtime_error("Supertonic[" + name + "]: accelerated input '" + semantic +
                                 "' missing; expected=" +
                                 (arg_index >= 0 ? "args_" + std::to_string(arg_index) : std::string("semantic/shape")) +
                                 "; signature names=" + join_names(input_names));
    }

    static std::string join_names(const std::vector<std::string>& names) {
        std::string out;
        for (size_t i = 0; i < names.size(); ++i) {
            if (i) out += ",";
            out += names[i];
        }
        return out;
    }

    static bool all_finite(const float* x, size_t n) {
        if (!x && n) return false;
        for (size_t i = 0; i < n; ++i) if (!std::isfinite(x[i])) return false;
        return true;
    }

    struct FloatStats {
        size_t count = 0;
        size_t finite = 0;
        size_t nan = 0;
        size_t pos_inf = 0;
        size_t neg_inf = 0;
        size_t first_bad = std::numeric_limits<size_t>::max();
        float min = std::numeric_limits<float>::infinity();
        float max = -std::numeric_limits<float>::infinity();
        long double abs_sum = 0.0L;
    };

    static FloatStats collect_float_stats(const float* x, size_t n) {
        FloatStats st;
        st.count = n;
        if (!x) {
            if (n) st.first_bad = 0;
            return st;
        }
        for (size_t i = 0; i < n; ++i) {
            const float v = x[i];
            if (std::isnan(v)) {
                ++st.nan;
                if (st.first_bad == std::numeric_limits<size_t>::max()) st.first_bad = i;
            } else if (std::isinf(v)) {
                if (v > 0.0f) ++st.pos_inf; else ++st.neg_inf;
                if (st.first_bad == std::numeric_limits<size_t>::max()) st.first_bad = i;
            } else {
                ++st.finite;
                st.min = std::min(st.min, v);
                st.max = std::max(st.max, v);
                st.abs_sum += std::fabs(static_cast<long double>(v));
            }
        }
        return st;
    }

    static void log_float_stats(const char* label, const float* x, size_t n) {
        const auto st = collect_float_stats(x, n);
        const double min_v = st.finite ? static_cast<double>(st.min) : std::numeric_limits<double>::quiet_NaN();
        const double max_v = st.finite ? static_cast<double>(st.max) : std::numeric_limits<double>::quiet_NaN();
        const double mean_abs = st.finite ? static_cast<double>(st.abs_sum / st.finite) : std::numeric_limits<double>::quiet_NaN();
        const long long first_bad = st.first_bad == std::numeric_limits<size_t>::max()
            ? -1LL : static_cast<long long>(st.first_bad);
        LOGI("[NPU-DIAG][TENSOR] %s count=%zu finite=%zu nan=%zu +inf=%zu -inf=%zu min=%.9g max=%.9g mean_abs=%.9g first_bad=%lld",
             label ? label : "tensor", st.count, st.finite, st.nan, st.pos_inf, st.neg_inf,
             min_v, max_v, mean_abs, first_bad);

        std::ostringstream preview;
        preview << std::setprecision(9);
        const size_t preview_n = std::min<size_t>(n, 8);
        for (size_t i = 0; i < preview_n; ++i) {
            if (i) preview << ',';
            preview << x[i];
        }
        if (n > preview_n) preview << ",...";
        LOGI("[NPU-DIAG][PREVIEW] %s first=[%s]",
             label ? label : "tensor", preview.str().c_str());
    }

    static const char* element_type_name(LiteRtElementType t) {
        switch (t) {
            case kLiteRtElementTypeFloat32: return "float32";
            case kLiteRtElementTypeFloat16: return "float16";
            case kLiteRtElementTypeInt32: return "int32";
            case kLiteRtElementTypeInt64: return "int64";
            default: return "other";
        }
    }

    static std::string layout_string(const LiteRtLayout& l) {
        std::ostringstream os;
        os << '[';
        for (unsigned int i = 0; i < l.rank; ++i) {
            if (i) os << ',';
            os << l.dimensions[i];
        }
        os << ']';
        return os.str();
    }

    void log_accel_io_metadata() const {
        if (uses_interpreter_primary()) return;
        for (size_t i = 0; i < input_types.size(); ++i) {
            const size_t logical = layout_element_count(input_types[i].layout) * elem_size(input_types[i].element_type);
            LOGI("[NPU-DIAG][IO] input slot=%zu name=%s dtype=%s shape=%s logical_bytes=%zu packed_bytes=%zu",
                 i, input_names[i].c_str(), element_type_name(input_types[i].element_type),
                 layout_string(input_types[i].layout).c_str(), logical, input_buffers[i]->byte_size());
        }
        for (size_t i = 0; i < output_types.size(); ++i) {
            const size_t logical = layout_element_count(output_types[i].layout) * elem_size(output_types[i].element_type);
            LOGI("[NPU-DIAG][IO] output slot=%zu name=%s dtype=%s shape=%s logical_bytes=%zu packed_bytes=%zu",
                 i, output_names[i].c_str(), element_type_name(output_types[i].element_type),
                 layout_string(output_types[i].layout).c_str(), logical, output_buffers[i]->byte_size());
        }
    }

    void log_output_raw_prefix(std::initializer_list<int> shape_il, int occurrence = 0) const {
        if (uses_interpreter_primary()) return;
        const std::vector<int> shape(shape_il);
        const int output_index = find_accel_output(shape, occurrence);
        const size_t bytes = std::min<size_t>(output_buffers[output_index]->byte_size(), 64);
        std::vector<unsigned char> raw(bytes);
        if (bytes) output_buffers[output_index]->read(raw.data(), bytes);
        std::ostringstream os;
        os << std::hex << std::setfill('0');
        for (size_t i = 0; i < raw.size(); ++i) {
            if (i) os << ' ';
            os << std::setw(2) << static_cast<unsigned int>(raw[i]);
        }
        LOGI("[NPU-DIAG][RAW] output slot=%d dtype=%s shape=%s packed_bytes=%zu prefix=%s",
             output_index, element_type_name(output_types[output_index].element_type),
             layout_string(output_types[output_index].layout).c_str(),
             output_buffers[output_index]->byte_size(), os.str().c_str());
    }

    void log_accel_input_buffer_stats(const char* semantic_name,
                                      std::initializer_list<int> shape_il,
                                      int occurrence = 0) const {
        if (uses_interpreter_primary()) return;
        const std::vector<int> shape(shape_il);
        const int input_index = find_accel_input_name(
            semantic_name ? semantic_name : "", shape, occurrence);
        const size_t elements = layout_element_count(input_types[input_index].layout);
        std::vector<float> decoded(elements);
        if (input_types[input_index].element_type == kLiteRtElementTypeFloat32) {
            input_buffers[input_index]->read(decoded.data(), elements * sizeof(float));
        } else if (input_types[input_index].element_type == kLiteRtElementTypeFloat16) {
            std::vector<uint16_t> half(elements);
            input_buffers[input_index]->read(half.data(), elements * sizeof(uint16_t));
            for (size_t i = 0; i < elements; ++i) decoded[i] = half_to_float(half[i]);
        } else {
            LOGI("[NPU-DIAG][BUFFER] input %s slot=%d dtype=%s shape=%s stats=skipped(non-float)",
                 semantic_name ? semantic_name : "", input_index,
                 element_type_name(input_types[input_index].element_type),
                 layout_string(input_types[input_index].layout).c_str());
            return;
        }
        const std::string label = std::string("npu-buffer.") +
            (semantic_name ? semantic_name : "input");
        log_float_stats(label.c_str(), decoded.data(), decoded.size());
    }

    static double relative_rmse(const float* test, const float* reference, size_t n) {
        if ((!test || !reference) && n) return std::numeric_limits<double>::infinity();
        long double diff_sq = 0.0L;
        long double ref_sq = 0.0L;
        for (size_t i = 0; i < n; ++i) {
            if (!std::isfinite(test[i]) || !std::isfinite(reference[i]))
                return std::numeric_limits<double>::infinity();
            const long double d =
                static_cast<long double>(test[i]) - static_cast<long double>(reference[i]);
            const long double r = static_cast<long double>(reference[i]);
            diff_sq += d * d;
            ref_sq += r * r;
        }
        if (n == 0) return 0.0;
        const long double denom = std::max(ref_sq, static_cast<long double>(1.0e-18L));
        return static_cast<double>(std::sqrt(diff_sq / denom));
    }

    void load(const std::string& path,
              int threads,
              Backend requested,
              const char* graph_name,
              std::uintptr_t /*unused_qnn_delegate_handle*/ = 0,
              const std::string& weight_cache_path = {},
              const std::string& signature_key = {},
              bool enable_deep_profiler = false,
              const std::string& profiler_dir = {},
              TfLiteModel* borrowed_model = nullptr,
              void* shared_weight_cache_provider = nullptr,
              std::mutex* shared_weight_cache_mutex = nullptr,
              const std::string& native_library_dir = {},
              const std::string& accelerator_cache_dir = {}) {
        backend = requested;
        name = graph_name ? graph_name : "graph";
        model_path = path;
        xnnpack_weight_cache_path = weight_cache_path;
        xnnpack_weight_cache_provider = shared_weight_cache_provider;
        xnnpack_weight_cache_mutex = shared_weight_cache_mutex;
        cpu_threads = std::max(1, threads);
        deep_profiler = enable_deep_profiler;
        deep_profiler_dir = profiler_dir;
        deep_invoke_ms.clear();

        if (is_cpu_backend(backend)) {
            const auto init_total_t0 = SteadyClock::now();
            const auto model_t0 = SteadyClock::now();
            model = borrowed_model ? borrowed_model : TfLiteModelCreateFromFile(path.c_str());
            owns_model = borrowed_model == nullptr;
            const double model_open_ms = elapsed_ms(model_t0, SteadyClock::now());
            const std::uintmax_t cache_bytes_before = xnnpack_cache_bytes(xnnpack_weight_cache_path);
            if (!model) throw std::runtime_error("Supertonic[" + name + "]: failed to load model " + path);
            auto* options = TfLiteInterpreterOptionsCreate();
            if (!options) throw std::runtime_error("Supertonic[" + name + "]: failed to create interpreter options");
            TfLiteInterpreterOptionsSetNumThreads(options, threads);
            auto xnn = TfLiteXNNPackDelegateOptionsDefault();
            xnn.num_threads = threads;
            if (!xnnpack_weight_cache_path.empty()) {
                xnn.weight_cache_file_path = xnnpack_weight_cache_path.c_str();
                if (xnnpack_weight_cache_provider) {
                    xnn.weight_cache_provider = xnnpack_weight_cache_provider;
                }
                std::error_code cache_ec;
                const bool cache_exists = std::filesystem::exists(xnnpack_weight_cache_path, cache_ec);
                const auto existing = cache_exists
                    ? std::filesystem::file_size(xnnpack_weight_cache_path, cache_ec) : 0;
                LOGI("[XNNPACK-CACHE] graph=%s enabled=1 state=%s bytes=%llu mode=%s path=%s",
                     name.c_str(), (!cache_ec && existing > 0) ? "hit-or-reuse" : "build",
                     static_cast<unsigned long long>(existing),
                     xnnpack_weight_cache_provider ? "stage-shared-provider" : "per-delegate-provider",
                     xnnpack_weight_cache_path.c_str());
            } else {
                LOGI("[XNNPACK-CACHE] graph=%s enabled=0", name.c_str());
            }
            if (backend == Backend::CpuFp16) {
                xnn.flags |= kTfLiteXNNPackDelegateFlagForceFp16;
            }
            // A shared file-backed MMapWeightCacheProvider is not thread-safe while
            // it is still accepting new packed entries. AutoBucket serializes delegate
            // preparation per stage through this mutex; inference itself is unaffected.
            std::unique_lock<std::mutex> shared_cache_lock;
            if (xnnpack_weight_cache_provider && xnnpack_weight_cache_mutex) {
                shared_cache_lock = std::unique_lock<std::mutex>(*xnnpack_weight_cache_mutex);
            }

            // The scoped mask is restored for the Android caller immediately after
            // construction. XNNPACK's newly-created pthreadpool keeps the inherited
            // topology-aware CPU mask.
            ScopedTopCpuAffinity affinity(threads, name);
            const auto delegate_t0 = SteadyClock::now();
            delegate = TfLiteXNNPackDelegateCreate(&xnn);
            const double delegate_create_ms = elapsed_ms(delegate_t0, SteadyClock::now());
            owns_delegate = true;
            if (!delegate) {
                TfLiteInterpreterOptionsDelete(options);
                throw std::runtime_error("Supertonic[" + name + "]: failed to create XNNPACK delegate");
            }
            const bool selected_signature = !signature_key.empty();
            if (!selected_signature) {
                TfLiteInterpreterOptionsAddDelegate(options, delegate);
            }
            const auto interpreter_t0 = SteadyClock::now();
            interpreter = TfLiteInterpreterCreate(model, options);
            const double interpreter_create_ms = elapsed_ms(interpreter_t0, SteadyClock::now());
            TfLiteInterpreterOptionsDelete(options);
            if (!interpreter) {
                throw std::runtime_error("Supertonic[" + name + "]: CPU interpreter creation failed");
            }

            double delegate_apply_ms = 0.0;
            double runner_create_ms = 0.0;
            double allocate_ms = 0.0;
            if (selected_signature) {
                cpu_signature_key = signature_key;
                const auto delegate_apply_t0 = SteadyClock::now();
                if (TfLiteInterpreterModifyGraphWithDelegateForSignature(
                        interpreter, delegate, cpu_signature_key.c_str()) != kTfLiteOk) {
                    throw std::runtime_error(
                        "Supertonic[" + name + "]: selected-subgraph XNNPACK failed for signature " +
                        cpu_signature_key);
                }
                delegate_apply_ms = elapsed_ms(delegate_apply_t0, SteadyClock::now());
                const auto runner_t0 = SteadyClock::now();
                signature_runner =
                    TfLiteInterpreterGetSignatureRunner(interpreter, cpu_signature_key.c_str());
                runner_create_ms = elapsed_ms(runner_t0, SteadyClock::now());
                if (!signature_runner) {
                    throw std::runtime_error(
                        "Supertonic[" + name + "]: signature not found: " + cpu_signature_key);
                }
                const size_t ni = TfLiteSignatureRunnerGetInputCount(signature_runner);
                const size_t no = TfLiteSignatureRunnerGetOutputCount(signature_runner);
                cpu_signature_input_names.reserve(ni);
                cpu_signature_output_names.reserve(no);
                for (size_t i = 0; i < ni; ++i) {
                    const char* n = TfLiteSignatureRunnerGetInputName(
                        signature_runner, static_cast<std::int32_t>(i));
                    cpu_signature_input_names.emplace_back(n ? n : "");
                }
                for (size_t i = 0; i < no; ++i) {
                    const char* n = TfLiteSignatureRunnerGetOutputName(
                        signature_runner, static_cast<std::int32_t>(i));
                    cpu_signature_output_names.emplace_back(n ? n : "");
                }
                const auto allocate_t0 = SteadyClock::now();
                if (TfLiteSignatureRunnerAllocateTensors(signature_runner) != kTfLiteOk) {
                    throw std::runtime_error(
                        "Supertonic[" + name + "]: SignatureRunner AllocateTensors failed");
                }
                allocate_ms = elapsed_ms(allocate_t0, SteadyClock::now());
                LOGI("[AUTO-BUCKET][XNNPACK-SELECTED] graph=%s signature=%s inputs=%zu outputs=%zu",
                     name.c_str(), cpu_signature_key.c_str(), ni, no);
            } else {
                const auto allocate_t0 = SteadyClock::now();
                if (TfLiteInterpreterAllocateTensors(interpreter) != kTfLiteOk) {
                    throw std::runtime_error("Supertonic[" + name + "]: CPU AllocateTensors failed");
                }
                allocate_ms = elapsed_ms(allocate_t0, SteadyClock::now());
            }
            if (!xnnpack_weight_cache_path.empty()) {
                std::error_code ec;
                const auto bytes = std::filesystem::file_size(xnnpack_weight_cache_path, ec);
                LOGI("[XNNPACK-CACHE-READY] graph=%s bytes=%llu status=%s",
                     name.c_str(), static_cast<unsigned long long>(ec ? 0 : bytes),
                     ec ? ec.message().c_str() : "ok");
            }
            const double init_total_ms = elapsed_ms(init_total_t0, SteadyClock::now());
            const std::uintmax_t cache_bytes_after = xnnpack_cache_bytes(xnnpack_weight_cache_path);
            const std::uintmax_t cache_grow_bytes =
                cache_bytes_after > cache_bytes_before ? cache_bytes_after - cache_bytes_before : 0;
            LOGI("[LITERT-INIT-TIMING] graph=%s signature=%s model_source=%s model_ms=%.3f delegate_ms=%.3f interpreter_ms=%.3f apply_ms=%.3f runner_ms=%.3f allocate_ms=%.3f total_ms=%.3f cache_before=%llu cache_after=%llu cache_grow=%llu cache_scope=%s",
                 name.c_str(), selected_signature ? cpu_signature_key.c_str() : "fixed",
                 borrowed_model ? "shared" : "open", model_open_ms, delegate_create_ms,
                 interpreter_create_ms, delegate_apply_ms, runner_create_ms, allocate_ms, init_total_ms,
                 static_cast<unsigned long long>(cache_bytes_before),
                 static_cast<unsigned long long>(cache_bytes_after),
                 static_cast<unsigned long long>(cache_grow_bytes),
                 xnnpack_weight_cache_provider ? "stage-shared" : "delegate-local");
            fully_accelerated = true;
            acceleration_detail = backend == Backend::CpuFp16
                ? "CPU/XNNPACK FP16 (Experimental)"
                : "CPU/XNNPACK";
            return;
        }


        const LiteRtHwAccelerators accel = backend == Backend::Gpu
            ? kLiteRtHwAcceleratorGpu
            : kLiteRtHwAcceleratorNpu;
        const char* accel_name = backend == Backend::Gpu ? "GPU" : "Qualcomm NPU/QNN";

        try {
            const std::vector<std::string> selected_signatures =
                signature_key.empty()
                    ? std::vector<std::string>{}
                    : std::vector<std::string>{signature_key};
            LiteRTEngine::get().load(path, accel, &accel_model, &compiled,
                                     /*allow_cpu_fallback=*/false,
                                     selected_signatures);
        } catch (const std::exception& e) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " strict compile failed: " + e.what());
        }

        bool full = false;
        const LiteRtStatus full_status = LiteRtCompiledModelIsFullyAccelerated(compiled, &full);
        if (full_status != kLiteRtStatusOk) {
            const char* status_text = LiteRtGetStatusString(full_status);
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " full-delegation check failed (status=" +
                                     std::to_string(full_status) +
                                     (status_text && *status_text ? std::string(", ") + status_text : std::string()) + ")");
        }
        if (!full) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name +
                                     " FULL DELEGATION FAILED: LiteRT reported a partial graph. "
                                     "Strict mode forbids CPU/GPU fallback.");
        }
        fully_accelerated = true;
        acceleration_detail = backend == Backend::Gpu
            ? "LiteRT GPU STRICT FULL"
            : "LiteRT Qualcomm NPU/QNN STRICT FULL";

        try {
            LiteRtParamIndex signature_count = 0;
            litert_check(LiteRtGetNumModelSignatures(accel_model, &signature_count),
                         "Supertonic signature count");
            bool signature_found = signature_key.empty();
            compiled_signature_index = 0;
            for (LiteRtParamIndex i = 0; i < signature_count; ++i) {
                LiteRtSignature candidate = nullptr;
                litert_check(LiteRtGetModelSignature(accel_model, i, &candidate),
                             "Supertonic signature lookup");
                const char* candidate_key = nullptr;
                litert_check(LiteRtGetSignatureKey(candidate, &candidate_key),
                             "Supertonic signature key");
                if (signature_key.empty() ||
                    (candidate_key && signature_key == candidate_key)) {
                    signature = candidate;
                    compiled_signature_index = i;
                    signature_found = true;
                    break;
                }
            }
            if (!signature_found || !signature) {
                throw std::runtime_error(
                    "Supertonic[" + name + "]: compiled signature not found: " +
                    signature_key);
            }
            LiteRtParamIndex ni = 0, no = 0;
            litert_check(LiteRtGetNumSignatureInputs(signature, &ni), "Supertonic num inputs");
            litert_check(LiteRtGetNumSignatureOutputs(signature, &no), "Supertonic num outputs");
            input_types.resize(ni); output_types.resize(no);
            input_names.resize(ni); output_names.resize(no);
            input_buffers.reserve(ni); output_buffers.reserve(no);
            auto env = LiteRTEngine::get().env();
            for (LiteRtParamIndex i = 0; i < ni; ++i) {
                const char* tensor_name = nullptr;
                litert_check(LiteRtGetSignatureInputName(signature, i, &tensor_name), "Supertonic input name");
                input_names[i] = tensor_name ? tensor_name : "";
                LiteRtTensor t = nullptr;
                litert_check(LiteRtGetSignatureInputTensorByIndex(signature, i, &t), "Supertonic input tensor");
                litert_check(LiteRtGetRankedTensorType(t, &input_types[i]), "Supertonic input type");
                if (backend == Backend::Npu) {
                    // Qualcomm HTP has its own alignment/backing requirements.
                    // Obey the CompiledModel contract instead of forcing host memory.
                    LiteRtTensorBufferRequirements req = nullptr;
                    litert_check(LiteRtGetCompiledModelInputBufferRequirements(compiled, compiled_signature_index, i, &req),
                                 "Supertonic input buffer requirements");
                    input_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, input_types[i], req));
                } else {
                    // The Android OpenCL accelerator currently advertises a packed
                    // OpenCL buffer type that managed allocation may reject; host
                    // buffers are the documented working path for this delegate.
                    const size_t bytes = layout_element_count(input_types[i].layout) * elem_size(input_types[i].element_type);
                    input_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, input_types[i], bytes));
                }
            }
            for (LiteRtParamIndex i = 0; i < no; ++i) {
                const char* tensor_name = nullptr;
                litert_check(LiteRtGetSignatureOutputName(signature, i, &tensor_name), "Supertonic output name");
                output_names[i] = tensor_name ? tensor_name : "";
                LiteRtTensor t = nullptr;
                litert_check(LiteRtGetSignatureOutputTensorByIndex(signature, i, &t), "Supertonic output tensor");
                litert_check(LiteRtGetRankedTensorType(t, &output_types[i]), "Supertonic output type");
                if (backend == Backend::Npu) {
                    LiteRtTensorBufferRequirements req = nullptr;
                    litert_check(LiteRtGetCompiledModelOutputBufferRequirements(compiled, compiled_signature_index, i, &req),
                                 "Supertonic output buffer requirements");
                    output_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, output_types[i], req));
                } else {
                    const size_t bytes = layout_element_count(output_types[i].layout) * elem_size(output_types[i].element_type);
                    output_buffers.push_back(std::make_unique<LiteRtHostBuffer>(env, output_types[i], bytes));
                }
            }
            LOGI("[LITERT-QNN-COMPILED] graph=%s signature=%s index=%u full=%d inputs=[%s] outputs=[%s]",
                 name.c_str(), signature_key.empty() ? "<default>" : signature_key.c_str(),
                 static_cast<unsigned int>(compiled_signature_index),
                 fully_accelerated ? 1 : 0, join_names(input_names).c_str(),
                 join_names(output_names).c_str());

        } catch (const std::exception& e) {
            throw std::runtime_error("Supertonic[" + name + "] " + accel_name + " I/O setup failed: " + e.what());
        }
    }

    bool switch_cpu_signature(const std::string& signature_key) {
        if (!is_cpu_backend(backend) || !interpreter || signature_key.empty()) return false;
        if (cpu_signature_key == signature_key && signature_runner) return true;

        const std::string previous = cpu_signature_key;
        const auto total_t0 = SteadyClock::now();
        const std::uintmax_t cache_bytes_before = xnnpack_cache_bytes(xnnpack_weight_cache_path);
        double remove_ms = 0.0;
        double delegate_ms = 0.0;
        double apply_ms = 0.0;
        double runner_ms = 0.0;
        double allocate_ms = 0.0;

        // SignatureRunner/tensor pointers are invalidated by delegate graph mutation.
        cpu_named_inputs.clear();
        cpu_cached_output = nullptr;
        cpu_signature_input_names.clear();
        cpu_signature_output_names.clear();
        if (signature_runner) {
            TfLiteSignatureRunnerDelete(signature_runner);
            signature_runner = nullptr;
        }

        std::unique_lock<std::mutex> shared_cache_lock;
        if (xnnpack_weight_cache_provider && xnnpack_weight_cache_mutex) {
            shared_cache_lock = std::unique_lock<std::mutex>(*xnnpack_weight_cache_mutex);
        }

        const auto remove_t0 = SteadyClock::now();
        if (TfLiteInterpreterRemoveAllDelegates(interpreter) != kTfLiteOk) {
            LOGI("[LITERT-SIGNATURE-SWITCH-FAIL] graph=%s from=%s to=%s stage=remove-delegates",
                 name.c_str(), previous.c_str(), signature_key.c_str());
            cpu_signature_key.clear();
            return false;
        }
        remove_ms = elapsed_ms(remove_t0, SteadyClock::now());

        // Deliberately do NOT reuse the delegate here. Interpreter reuse is optimization
        // #2; delegate/threadpool reuse remains optimization #3 and is kept independent
        // until repeated-switch RSS and correctness are proven on device.
        if (delegate && owns_delegate) TfLiteXNNPackDelegateDelete(delegate);
        delegate = nullptr;
        owns_delegate = false;

        auto xnn = TfLiteXNNPackDelegateOptionsDefault();
        xnn.num_threads = cpu_threads;
        if (!xnnpack_weight_cache_path.empty()) {
            xnn.weight_cache_file_path = xnnpack_weight_cache_path.c_str();
            if (xnnpack_weight_cache_provider) {
                xnn.weight_cache_provider = xnnpack_weight_cache_provider;
            }
        }
        if (backend == Backend::CpuFp16) {
            xnn.flags |= kTfLiteXNNPackDelegateFlagForceFp16;
        }

        ScopedTopCpuAffinity affinity(cpu_threads, name);
        const auto delegate_t0 = SteadyClock::now();
        delegate = TfLiteXNNPackDelegateCreate(&xnn);
        delegate_ms = elapsed_ms(delegate_t0, SteadyClock::now());
        owns_delegate = true;
        if (!delegate) {
            LOGI("[LITERT-SIGNATURE-SWITCH-FAIL] graph=%s from=%s to=%s stage=create-delegate",
                 name.c_str(), previous.c_str(), signature_key.c_str());
            cpu_signature_key.clear();
            return false;
        }

        const auto apply_t0 = SteadyClock::now();
        if (TfLiteInterpreterModifyGraphWithDelegateForSignature(
                interpreter, delegate, signature_key.c_str()) != kTfLiteOk) {
            apply_ms = elapsed_ms(apply_t0, SteadyClock::now());
            LOGI("[LITERT-SIGNATURE-SWITCH-FAIL] graph=%s from=%s to=%s stage=apply apply_ms=%.3f",
                 name.c_str(), previous.c_str(), signature_key.c_str(), apply_ms);
            cpu_signature_key.clear();
            return false;
        }
        apply_ms = elapsed_ms(apply_t0, SteadyClock::now());

        const auto runner_t0 = SteadyClock::now();
        signature_runner = TfLiteInterpreterGetSignatureRunner(interpreter, signature_key.c_str());
        runner_ms = elapsed_ms(runner_t0, SteadyClock::now());
        if (!signature_runner) {
            LOGI("[LITERT-SIGNATURE-SWITCH-FAIL] graph=%s from=%s to=%s stage=create-runner",
                 name.c_str(), previous.c_str(), signature_key.c_str());
            cpu_signature_key.clear();
            return false;
        }

        const size_t ni = TfLiteSignatureRunnerGetInputCount(signature_runner);
        const size_t no = TfLiteSignatureRunnerGetOutputCount(signature_runner);
        cpu_signature_input_names.reserve(ni);
        cpu_signature_output_names.reserve(no);
        for (size_t i = 0; i < ni; ++i) {
            const char* n = TfLiteSignatureRunnerGetInputName(
                signature_runner, static_cast<std::int32_t>(i));
            cpu_signature_input_names.emplace_back(n ? n : "");
        }
        for (size_t i = 0; i < no; ++i) {
            const char* n = TfLiteSignatureRunnerGetOutputName(
                signature_runner, static_cast<std::int32_t>(i));
            cpu_signature_output_names.emplace_back(n ? n : "");
        }

        const auto allocate_t0 = SteadyClock::now();
        if (TfLiteSignatureRunnerAllocateTensors(signature_runner) != kTfLiteOk) {
            allocate_ms = elapsed_ms(allocate_t0, SteadyClock::now());
            LOGI("[LITERT-SIGNATURE-SWITCH-FAIL] graph=%s from=%s to=%s stage=allocate allocate_ms=%.3f",
                 name.c_str(), previous.c_str(), signature_key.c_str(), allocate_ms);
            cpu_signature_key.clear();
            return false;
        }
        allocate_ms = elapsed_ms(allocate_t0, SteadyClock::now());
        cpu_signature_key = signature_key;

        const std::uintmax_t cache_bytes_after = xnnpack_cache_bytes(xnnpack_weight_cache_path);
        const std::uintmax_t cache_grow_bytes =
            cache_bytes_after > cache_bytes_before ? cache_bytes_after - cache_bytes_before : 0;
        LOGI("[LITERT-SIGNATURE-SWITCH] graph=%s from=%s to=%s interpreter=reused delegate=recreated cache=%s remove_ms=%.3f delegate_ms=%.3f apply_ms=%.3f runner_ms=%.3f allocate_ms=%.3f total_ms=%.3f cache_before=%llu cache_after=%llu cache_grow=%llu",
             name.c_str(), previous.empty() ? "none" : previous.c_str(),
             cpu_signature_key.c_str(),
             xnnpack_weight_cache_provider ? "stage-shared" : "delegate-local",
             remove_ms, delegate_ms, apply_ms, runner_ms, allocate_ms,
             elapsed_ms(total_t0, SteadyClock::now()),
             static_cast<unsigned long long>(cache_bytes_before),
             static_cast<unsigned long long>(cache_bytes_after),
             static_cast<unsigned long long>(cache_grow_bytes));
        return true;
    }


    void write_input_dims(const std::vector<int>& shape, const void* data, size_t bytes, int occurrence = 0) {
        if (uses_interpreter_primary()) {
            TfLiteTensor* target = nullptr;
            if (uses_signature_runner()) {
                int seen = 0;
                for (const auto& actual : cpu_signature_input_names) {
                    auto* t = TfLiteSignatureRunnerGetInputTensor(
                        signature_runner, actual.c_str());
                    if (!tensor_has_shape_vec(t, shape)) continue;
                    if (seen++ == occurrence) { target = t; break; }
                }
            } else {
                int seen = 0;
                const int count = TfLiteInterpreterGetInputTensorCount(interpreter);
                for (int i = 0; i < count; ++i) {
                    auto* t = TfLiteInterpreterGetInputTensor(interpreter, i);
                    if (!tensor_has_shape_vec(t, shape)) continue;
                    if (seen++ == occurrence) { target = t; break; }
                }
            }
            if (!target) throw std::runtime_error("Supertonic[" + name + "]: required input tensor missing");
            tflite_check(TfLiteTensorCopyFromBuffer(target, data, bytes), "input copy");
        } else {
            input_buffers[find_accel_input(shape, occurrence)]->write(data, bytes);
        }
    }

    void write_input(std::initializer_list<int> shape, const void* data, size_t bytes, int occurrence = 0) {
        write_input_dims(std::vector<int>(shape), data, bytes, occurrence);
    }

    void write_input_named(const char* semantic_name, std::initializer_list<int> cpu_shape,
                           const void* data, size_t bytes, int cpu_occurrence = 0) {
        if (uses_interpreter_primary()) {
            const std::string key = semantic_name ? semantic_name : "";
            TfLiteTensor* target = nullptr;
            const auto cached = cpu_named_inputs.find(key);
            if (cached != cpu_named_inputs.end()) {
                target = cached->second;
            } else {
                if (uses_signature_runner()) {
                    target = find_cpu_signature_input(
                        key, std::vector<int>(cpu_shape), cpu_occurrence);
                } else {
                    int seen = 0;
                    const int count = TfLiteInterpreterGetInputTensorCount(interpreter);
                    for (int i = 0; i < count; ++i) {
                        auto* t = TfLiteInterpreterGetInputTensor(interpreter, i);
                        if (!tensor_has_shape(t, cpu_shape)) continue;
                        if (seen++ == cpu_occurrence) {
                            target = t;
                            break;
                        }
                    }
                }
                if (!target) {
                    throw std::runtime_error("Supertonic[" + name + "]: required CPU input tensor missing");
                }
                cpu_named_inputs.emplace(key, target);
            }
            const size_t target_bytes = TfLiteTensorByteSize(target);
            if (bytes == target_bytes) {
                tflite_check(TfLiteTensorCopyFromBuffer(target, data, bytes), "input copy");
                return;
            }
            // FP16 TFLite graphs may expose FP16 floating tensors even on the
            // CPU/XNNPACK path, while the synthesis orchestration intentionally
            // stays FP32. Infer the FP16 boundary from the tensor byte size and
            // convert only the 2:1 float32->float16 case. Integer text_ids keep
            // the direct-copy path above.
            if (bytes % sizeof(float) == 0 && target_bytes * 2 == bytes) {
                const size_t elements = bytes / sizeof(float);
                const auto* src = static_cast<const float*>(data);
                fp16_input_scratch.resize(elements);
                for (size_t i = 0; i < elements; ++i) {
                    fp16_input_scratch[i] = float_to_half(src[i]);
                }
                tflite_check(
                    TfLiteTensorCopyFromBuffer(
                        target, fp16_input_scratch.data(), target_bytes),
                    "FP32->FP16 input copy");
                return;
            }
            throw std::runtime_error(
                "Supertonic[" + name + "]: CPU input byte-size mismatch: got=" +
                std::to_string(bytes) + " tensor=" + std::to_string(target_bytes));
        }
        const std::vector<int> expected_shape(cpu_shape);
        const int index = find_accel_input_name(
            semantic_name ? semantic_name : "", expected_shape, cpu_occurrence);
        const size_t elements = layout_element_count(input_types[index].layout);
        const auto expected = elements * elem_size(input_types[index].element_type);
        if (
            input_types[index].element_type == kLiteRtElementTypeFloat16 &&
            bytes == elements * sizeof(float)
        ) {
            const auto* src = static_cast<const float*>(data);
            fp16_input_scratch.resize(elements);
            for (size_t i = 0; i < elements; ++i) {
                fp16_input_scratch[i] = float_to_half(src[i]);
            }
            input_buffers[index]->write(
                fp16_input_scratch.data(),
                fp16_input_scratch.size() * sizeof(uint16_t));
            return;
        }
        if (bytes != expected) {
            throw std::runtime_error("Supertonic[" + name + "]: input '" + semantic_name +
                                     "' byte-size mismatch: got=" + std::to_string(bytes) +
                                     " expected=" + std::to_string(expected));
        }
        input_buffers[index]->write(data, bytes);
    }

    void run(const char* what) {
        const auto profile_start = SteadyClock::now();
        if (uses_interpreter_primary()) {
            const TfLiteStatus status = uses_signature_runner()
                ? TfLiteSignatureRunnerInvoke(signature_runner)
                : TfLiteInterpreterInvoke(interpreter);
            if (status != kTfLiteOk)
                throw std::runtime_error("Supertonic[" + name + "]: " + std::string(what ? what : "invoke") + " failed");
            if (deep_profiler) {
                const double ms = elapsed_ms(profile_start, SteadyClock::now());
                deep_invoke_ms.push_back(ms);
                LOGI("[LITERT-PROFILER] graph=%s invoke=%zu ms=%.3f path=%s",
                     name.c_str(), deep_invoke_ms.size(), ms,
                     uses_signature_runner() ? "selected-signature-xnnpack" : "legacy-tflite-xnnpack");
            }
            return;
        }
        std::vector<LiteRtTensorBuffer> ins, outs;
        ins.reserve(input_buffers.size()); outs.reserve(output_buffers.size());
        for (auto& b : input_buffers) ins.push_back(b->raw());
        for (auto& b : output_buffers) outs.push_back(b->raw());
        LOGI("Supertonic[%s] [RUN-BEGIN] %s backend=%s",
             name.c_str(),
             what ? what : "invoke",
             backend == Backend::Npu ? "NPU" : "GPU");

        const auto status = LiteRtRunCompiledModel(
            compiled, compiled_signature_index,
            ins.size(), ins.data(), outs.size(), outs.data());

        const char* run_status_text = LiteRtGetStatusString(status);
        LOGI("Supertonic[%s] [RUN-END] status=%d (%s)",
             name.c_str(),
             static_cast<int>(status),
             run_status_text ? run_status_text : "");

        if (deep_profiler) {
            const double ms = elapsed_ms(profile_start, SteadyClock::now());
            deep_invoke_ms.push_back(ms);
            LOGI("[LITERT-PROFILER] graph=%s invoke=%zu ms=%.3f path=compiled-model",
                 name.c_str(), deep_invoke_ms.size(), ms);
        }
        if (status != kLiteRtStatusOk) {
            const char* status_text = LiteRtGetStatusString(status);
            throw std::runtime_error("Supertonic[" + name + "]: strict accelerator invoke failed (status=" +
                                     std::to_string(status) +
                                     (status_text && *status_text ? std::string(", ") + status_text : std::string()) + ")");
        }
    }

    void write_deep_profile_csv(const std::string& file_path) const {
        if (!deep_profiler || deep_invoke_ms.empty() || file_path.empty()) return;
        std::error_code ec;
        std::filesystem::create_directories(std::filesystem::path(file_path).parent_path(), ec);
        std::ofstream out(file_path, std::ios::trunc);
        if (!out) {
            LOGI("[LITERT-PROFILE-WRITE-FAIL] graph=%s path=%s", name.c_str(), file_path.c_str());
            return;
        }
        out << "graph,invoke,ms,path\n";
        const char* path_name = uses_signature_runner()
            ? "selected-signature-xnnpack"
            : (uses_interpreter_primary() ? "legacy-tflite-xnnpack" : "compiled-model");
        for (size_t i = 0; i < deep_invoke_ms.size(); ++i) {
            out << name << ',' << (i + 1) << ',' << std::fixed << std::setprecision(6)
                << deep_invoke_ms[i] << ',' << path_name << '\n';
        }
        LOGI("[LITERT-PROFILE-FILE] graph=%s invokes=%zu path=%s",
             name.c_str(), deep_invoke_ms.size(), file_path.c_str());
    }

    void read_output_impl(std::initializer_list<int> shape_il, void* data, size_t bytes,
                          int occurrence, bool validate) {
        const std::vector<int> shape(shape_il);
        if (uses_interpreter_primary()) {
            const TfLiteTensor* result = cpu_cached_output;
            if (!result || !tensor_has_shape_vec(result, shape)) {
                result = nullptr;
                if (uses_signature_runner()) {
                    result = find_cpu_signature_output(shape, occurrence);
                } else {
                    const int count = TfLiteInterpreterGetOutputTensorCount(interpreter);
                    int seen = 0;
                    for (int i = 0; i < count; ++i) {
                        const auto* t = TfLiteInterpreterGetOutputTensor(interpreter, i);
                        if (!tensor_has_shape_vec(t, shape)) continue;
                        if (seen++ == occurrence) { result = t; break; }
                    }
                }
                if (!result) throw std::runtime_error("Supertonic[" + name + "]: required output tensor missing");
                cpu_cached_output = result;
            }
            const size_t source_bytes = TfLiteTensorByteSize(result);
            if (bytes == source_bytes) {
                if (TfLiteTensorCopyToBuffer(result, data, bytes) != kTfLiteOk)
                    throw std::runtime_error("Supertonic[" + name + "]: output copy failed");
                return;
            }
            // Mirror the FP32->FP16 input conversion for FP16 tensor models.
            // The public API hands FP32 buffers to the model wrapper, so decode
            // an FP16 graph output back to FP32 when the sizes have a 1:2 ratio.
            if (bytes % sizeof(float) == 0 && source_bytes * 2 == bytes) {
                const size_t elements = bytes / sizeof(float);
                fp16_output_scratch.resize(elements);
                if (TfLiteTensorCopyToBuffer(
                        result, fp16_output_scratch.data(), source_bytes) != kTfLiteOk) {
                    throw std::runtime_error("Supertonic[" + name + "]: FP16 output copy failed");
                }
                auto* dst = static_cast<float*>(data);
                for (size_t i = 0; i < elements; ++i) {
                    dst[i] = half_to_float(fp16_output_scratch[i]);
                }
                return;
            }
            throw std::runtime_error(
                "Supertonic[" + name + "]: CPU output byte-size mismatch: requested=" +
                std::to_string(bytes) + " tensor=" + std::to_string(source_bytes));
        }

        const int output_index = find_accel_output(shape, occurrence);
        const size_t output_elements =
            layout_element_count(output_types[output_index].layout);
        if (
            output_types[output_index].element_type == kLiteRtElementTypeFloat16 &&
            bytes == output_elements * sizeof(float)
        ) {
            fp16_output_scratch.resize(output_elements);
            output_buffers[output_index]->read(
                fp16_output_scratch.data(),
                fp16_output_scratch.size() * sizeof(uint16_t));
            auto* dst = static_cast<float*>(data);
            for (size_t i = 0; i < output_elements; ++i) {
                dst[i] = half_to_float(fp16_output_scratch[i]);
            }
            if (name == "duration" && output_elements == 1) {
                LOGI("[FP16-DURATION-RAW] bits=0x%04x decoded=%.9g",
                     static_cast<unsigned int>(fp16_output_scratch[0]),
                     static_cast<double>(dst[0]));
            }
        } else {
            const size_t expected = output_elements *
                elem_size(output_types[output_index].element_type);
            if (bytes != expected) {
                throw std::runtime_error(
                    "Supertonic[" + name + "]: output byte-size mismatch: got=" +
                    std::to_string(bytes) + " expected=" + std::to_string(expected));
            }
            output_buffers[output_index]->read(data, bytes);
        }

        if (!validate) return;

        // Full accelerator means no silent CPU rescue. If the hardware path
        // produces invalid floats, stop synthesis before those values can turn
        // into the loud digital/static noise seen in earlier QNN experiments.
        if (bytes % sizeof(float) == 0) {
            const size_t n = bytes / sizeof(float);
            const auto* f = static_cast<const float*>(data);
            if (!all_finite(f, n)) {
                throw std::runtime_error("Supertonic[" + name + "]: STRICT accelerator output contains NaN/Inf; audio blocked");
            }
            if (name == "duration" && n > 0 && !(f[0] > 0.0f && f[0] < 60.0f)) {
                throw std::runtime_error("Supertonic[duration]: STRICT accelerator returned invalid duration");
            }
            if (name == "vocoder") {
                float peak = 0.0f;
                for (size_t i = 0; i < n; ++i) peak = std::max(peak, std::fabs(f[i]));
                if (!std::isfinite(peak) || peak > 8.0f)
                    throw std::runtime_error("Supertonic[vocoder]: STRICT accelerator waveform is numerically invalid; audio blocked");
            }
        }
    }

    void read_output(std::initializer_list<int> shape_il, void* data, size_t bytes, int occurrence = 0) {
        read_output_impl(shape_il, data, bytes, occurrence, true);
    }

    void read_output_unchecked(std::initializer_list<int> shape_il, void* data, size_t bytes, int occurrence = 0) {
        read_output_impl(shape_il, data, bytes, occurrence, false);
    }

    std::string decision_label() const {
        if (backend == Backend::Cpu) return "CPU/XNNPACK";
        if (backend == Backend::CpuFp16) return "CPU/XNNPACK FP16";
        return backend == Backend::Gpu ? "GPU(FULL)" : "NPU(FULL)";
    }
};
std::vector<float> trim_edge_silence(const std::vector<float>& pcm, int trailing_trim_ms) {
    if (trailing_trim_ms <= 0 || pcm.size() < 4096) return pcm;
    float peak = 0.0f;
    for (float x : pcm) peak = std::max(peak, std::fabs(x));
    if (peak < 1.0e-5f) return pcm;

    const float threshold = std::max(5.0e-4f, peak * 0.003f);
    const size_t min_run = static_cast<size_t>(0.035 * kSampleRateConst);
    const size_t leading_max_trim = static_cast<size_t>(0.45 * kSampleRateConst);
    const size_t trailing_max_trim = static_cast<size_t>(std::max(0, trailing_trim_ms) * kSampleRateConst / 1000);

    size_t first = 0;
    size_t low = 0;
    for (size_t i = 0; i < pcm.size() && i < leading_max_trim + min_run; ++i) {
        if (std::fabs(pcm[i]) < threshold) {
            ++low;
        } else {
            if (low >= min_run) first = i;
            else first = 0;
            break;
        }
    }
    if (low >= min_run && first == 0) first = std::min(low, leading_max_trim);
    if (first > leading_max_trim) first = leading_max_trim;

    size_t last = pcm.size();
    low = 0;
    for (size_t i = pcm.size(); i-- > 0 && (pcm.size() - i) <= trailing_max_trim + min_run;) {
        if (std::fabs(pcm[i]) < threshold) {
            ++low;
        } else {
            if (low >= min_run) last = i + 1;
            break;
        }
    }
    if (low >= min_run && last == pcm.size()) last = pcm.size() - std::min(low, trailing_max_trim);
    if (last > pcm.size()) last = pcm.size();

    if (first >= last) return pcm;
    std::vector<float> out(pcm.begin() + static_cast<std::ptrdiff_t>(first),
                          pcm.begin() + static_cast<std::ptrdiff_t>(last));
    return out.size() >= 2048 ? out : pcm;
}

void load_style(const std::string& path, std::vector<float>& ttl, std::vector<float>& dp) {
    const std::string text = json::read_file(path);
    if (text.empty()) throw std::runtime_error("Supertonic: cannot read voice style " + path);
    ttl = extract_style(text, "style_ttl");
    dp = extract_style(text, "style_dp");
}
} // namespace


struct LiteRTSupertonicTts::InterpreterState {
    // MultiPreset bundles reuse the same four FlatBuffer models across bucket
    // switches. Graph interpreters/delegates remain one-active-per-stage, so
    // XNNPACK packed graphs do not accumulate while repeated file mmap/parse is removed.
    TfLiteModel* shared_duration_model = nullptr;
    TfLiteModel* shared_encoder_model = nullptr;
    TfLiteModel* shared_vector_model = nullptr;
    TfLiteModel* shared_vocoder_model = nullptr;

    // One file-backed MMapWeightCacheProvider per model/stage. Signatures of the
    // same multi-preset model share packed weights; different models never do.
    void* shared_duration_cache_provider = nullptr;
    void* shared_encoder_cache_provider = nullptr;
    void* shared_vector_cache_provider = nullptr;
    void* shared_vocoder_cache_provider = nullptr;
    std::string shared_duration_cache_path;
    std::string shared_encoder_cache_path;
    std::string shared_vector_cache_path;
    std::string shared_vocoder_cache_path;
    std::mutex shared_duration_cache_mutex;
    std::mutex shared_encoder_cache_mutex;
    std::mutex shared_vector_cache_mutex;
    std::mutex shared_vocoder_cache_mutex;

    Graph duration;
    Graph encoder;
    Graph vector;
    Graph vocoder;

    ~InterpreterState() {
        // Graphs are declared after shared models and therefore are destroyed
        // first; explicit cleanup here documents the required lifetime.
        duration.release_resources();
        encoder.release_resources();
        vector.release_resources();
        vocoder.release_resources();

        auto close_cache = [](void*& p) {
            if (!p) return;
            SupertonicXnnpackWeightCacheProviderStopBuild(p);
            SupertonicXnnpackWeightCacheProviderDelete(p);
            p = nullptr;
        };
        close_cache(shared_duration_cache_provider);
        close_cache(shared_encoder_cache_provider);
        close_cache(shared_vector_cache_provider);
        close_cache(shared_vocoder_cache_provider);

        if (shared_duration_model) TfLiteModelDelete(shared_duration_model);
        if (shared_encoder_model) TfLiteModelDelete(shared_encoder_model);
        if (shared_vector_model) TfLiteModelDelete(shared_vector_model);
        if (shared_vocoder_model) TfLiteModelDelete(shared_vocoder_model);
    }
};

LiteRTSupertonicTts::LiteRTSupertonicTts(
    const std::string& duration_path,
    const std::string& text_encoder_path,
    const std::string& vector_estimator_path,
    const std::string& vocoder_path,
    const std::string& tokenizer_dir,
    const std::string& voice_styles_dir,
    bool hw_accel,
    int num_threads,
    Backend backend,
    std::string native_library_dir,
    std::string accelerator_cache_dir,
    bool enable_deep_profiler,
    std::shared_ptr<SupertonicExternalRunner> external_runner,
    bool static_multipreset_bundle)
    : num_threads_(std::max(1, std::min(64, num_threads))),
      backend_(backend),
      native_library_dir_(std::move(native_library_dir)),
      accelerator_cache_dir_(std::move(accelerator_cache_dir)),
      deep_profiler_(enable_deep_profiler),
      external_runner_(std::move(external_runner)),
      static_multipreset_bundle_(static_multipreset_bundle),
      duration_path_(duration_path),
      text_encoder_path_(text_encoder_path),
      vector_estimator_path_(vector_estimator_path),
      vocoder_path_(vocoder_path),
      tokenizer_dir_(tokenizer_dir),
      voice_styles_dir_(voice_styles_dir) {
    (void)hw_accel;
    if (deep_profiler_) {
        try {
            const std::filesystem::path root = accelerator_cache_dir_.empty()
                ? (std::filesystem::path(tokenizer_dir) / ".perf_profiles")
                : (std::filesystem::path(accelerator_cache_dir_) / "perf_profiles");
            const std::string model_tag = std::filesystem::path(tokenizer_dir).filename().string();
            const std::filesystem::path model_root =
                root / "litert" / (model_tag.empty() ? "unknown-model" : model_tag);
            std::filesystem::create_directories(model_root);
            deep_profiler_dir_ = model_root.string();
            LOGI("[DEEP-PROFILER] backend=LiteRT enabled=1 dir=%s timing_is_diagnostic=1 granularity=graph-invoke",
                 deep_profiler_dir_.c_str());
        } catch (const std::exception& e) {
            LOGI("[DEEP-PROFILER] backend=LiteRT enabled=0 reason=%s", e.what());
            deep_profiler_ = false;
        }
    }

    // Stage-specific CPU thread plan. Duration prediction is tiny and gains little
    // from a large pool; the encoder is capped to avoid waking every core for a short
    // graph, while VE/vocoder retain the full user-selected budget because they make
    // up the overwhelming majority of steady-state CPU time.
    duration_threads_ = std::max(1, std::min(2, num_threads_));
    encoder_threads_ = std::max(1, std::min(4, num_threads_));
    vector_threads_ = num_threads_;
    vocoder_threads_ = num_threads_;
    LOGI("[CPU-THREAD-PLAN] total=%d duration=%d encoder=%d vector=%d vocoder=%d",
         num_threads_, duration_threads_, encoder_threads_, vector_threads_, vocoder_threads_);

    auto xnn_cache = [&](const std::string& model, const char* graph) {
        return make_xnnpack_weight_cache_path(
            accelerator_cache_dir_, model, graph ? graph : "graph", backend_);
    };

    const bool full_external_runner =
        external_runner_ && external_runner_->supports_duration() &&
        external_runner_->supports_encoder() && external_runner_->supports_vector() &&
        external_runner_->supports_vocoder();

    if (full_external_runner) {
        // A full external runner owns all four stages. Keep native LiteRT out of
        // that path so ONNX FP32/FP16/INT8 all use the same ORT orchestration
        // instead of silently falling back into fixed-shape LiteRT graphs.
        backend_report_ = external_runner_->backend_report();
        pre_generation_ = false;
    } else if (is_cpu_backend(backend_)) {
        namespace fs = std::filesystem;
        interp_ = std::make_unique<InterpreterState>();

        if (static_multipreset_bundle_) {
            const fs::path bundle_root = fs::path(duration_path_).parent_path();
            const bool has_manifest =
                fs::is_regular_file(bundle_root / "multi_preset_manifest.json") ||
                fs::is_regular_file(bundle_root / "manifest.json");
            if (!has_manifest) {
                throw std::runtime_error(
                    "Supertonic GELU MultiPreset manifest is missing from " +
                    bundle_root.string());
            }

            autobucket_dir_ = bundle_root.string();
            autobucket_enabled_ = true;

            const auto shared_open_t0 = SteadyClock::now();
            interp_->shared_duration_model = TfLiteModelCreateFromFile(duration_path_.c_str());
            interp_->shared_encoder_model = TfLiteModelCreateFromFile(text_encoder_path_.c_str());
            interp_->shared_vector_model = TfLiteModelCreateFromFile(vector_estimator_path_.c_str());
            interp_->shared_vocoder_model = TfLiteModelCreateFromFile(vocoder_path_.c_str());
            if (!interp_->shared_duration_model || !interp_->shared_encoder_model ||
                !interp_->shared_vector_model || !interp_->shared_vocoder_model) {
                throw std::runtime_error("Supertonic MultiPreset: failed to open one or more shared TfLiteModel objects");
            }
            LOGI("[AUTO-BUCKET][SHARED-MODELS] opened=4 total_ms=%.3f lifetime=engine",
                 elapsed_ms(shared_open_t0, SteadyClock::now()));

            interp_->shared_duration_cache_path = make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, duration_path_, "duration_shared", backend_);
            interp_->shared_encoder_cache_path = make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, text_encoder_path_, "encoder_shared", backend_);
            interp_->shared_vector_cache_path = make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, vector_estimator_path_, "vector_shared", backend_);
            interp_->shared_vocoder_cache_path = make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, vocoder_path_, "vocoder_shared", backend_);
            LOGI("[AUTO-BUCKET][XNN-CACHE-ABI] generation=%s stale_runtime_caches_ignored=1",
                 kSupertonicXnnpackPersistentCacheAbi);

            // Remove only the legacy per-bucket cache files generated for this
            // exact model metadata/backend. This frees old duplicated packed
            // weights without touching another model or the new shared files.
            std::uintmax_t legacy_cache_bytes_removed = 0;
            int legacy_cache_files_removed = 0;
            auto remove_legacy_cache = [&](const std::string& model_path, const std::string& graph_key) {
                const std::string legacy = make_xnnpack_weight_cache_path(
                    accelerator_cache_dir_, model_path, graph_key, backend_);
                if (legacy.empty()) return;
                std::error_code ec;
                std::uintmax_t bytes = 0;
                if (std::filesystem::is_regular_file(legacy, ec) && !ec) {
                    bytes = std::filesystem::file_size(legacy, ec);
                    if (ec) bytes = 0;
                }
                ec.clear();
                if (std::filesystem::remove(legacy, ec) && !ec) {
                    ++legacy_cache_files_removed;
                    legacy_cache_bytes_removed += bytes;
                }
            };
            for (const int t : kAutoBuckets) {
                remove_legacy_cache(duration_path_, "duration_T" + std::to_string(t));
                remove_legacy_cache(text_encoder_path_, "encoder_T" + std::to_string(t));
                for (const int l : kAutoBuckets) {
                    remove_legacy_cache(vector_estimator_path_,
                        "vector_T" + std::to_string(t) + "_L" + std::to_string(l));
                }
            }
            for (const int l : kAutoBuckets) {
                remove_legacy_cache(vocoder_path_, "vocoder_L" + std::to_string(l));
            }
            LOGI("[AUTO-BUCKET][XNN-CACHE-MIGRATION] removed_files=%d removed_bytes=%llu old_layout=per-bucket new_layout=stage-shared",
                 legacy_cache_files_removed,
                 static_cast<unsigned long long>(legacy_cache_bytes_removed));

            interp_->shared_duration_cache_provider = SupertonicXnnpackWeightCacheProviderCreate();
            interp_->shared_encoder_cache_provider = SupertonicXnnpackWeightCacheProviderCreate();
            interp_->shared_vector_cache_provider = SupertonicXnnpackWeightCacheProviderCreate();
            interp_->shared_vocoder_cache_provider = SupertonicXnnpackWeightCacheProviderCreate();
            if (!interp_->shared_duration_cache_provider || !interp_->shared_encoder_cache_provider ||
                !interp_->shared_vector_cache_provider || !interp_->shared_vocoder_cache_provider) {
                throw std::runtime_error("Supertonic MultiPreset: failed to create shared XNNPACK weight-cache providers");
            }

            auto load_or_start_cache = [](void* provider, const std::string& path,
                                          const char* stage) {
                if (path.empty()) return;
                if (SupertonicXnnpackWeightCacheProviderLoadOrStartBuild(
                        provider, path.c_str()) != kTfLiteOk) {
                    throw std::runtime_error(
                        std::string("Supertonic MultiPreset: failed to load or start shared XNNPACK cache for ") +
                        (stage ? stage : "stage") + ": " + path);
                }
            };
            load_or_start_cache(interp_->shared_duration_cache_provider,
                                interp_->shared_duration_cache_path, "duration");
            load_or_start_cache(interp_->shared_encoder_cache_provider,
                                interp_->shared_encoder_cache_path, "encoder");
            load_or_start_cache(interp_->shared_vector_cache_provider,
                                interp_->shared_vector_cache_path, "vector");
            load_or_start_cache(interp_->shared_vocoder_cache_provider,
                                interp_->shared_vocoder_cache_path, "vocoder");
            LOGI("[AUTO-BUCKET][SHARED-XNN-CACHE] providers=4 scope=stage lifetime=engine bucket_key_removed=1 lifecycle=upstream-load-or-build no_resume_append=1 vector_path=%s",
                 interp_->shared_vector_cache_path.c_str());

            // Do not eagerly load the small tuple in the native constructor.
            // MainActivity already performs a one-step hidden synth with the
            // deliberately tiny text "a" after engine creation. For Multi-P
            // this naturally selects T32/L32 and warms the same stage-shared
            // packed-weight caches, but only after the engine has completed
            // construction. Keeping eager_graphs=0 here avoids the startup
            // regression caused by constructing four delegated graphs inside
            // the constructor and then immediately warming them again.
            pre_generation_ = false;
            backend_report_ = backend_ == Backend::CpuFp16
                ? "CPU/XNNPACK FP16 · Static MultiPreset GELU · selected-subgraph"
                : "CPU/XNNPACK · Static MultiPreset GELU · selected-subgraph";
            LOGI("[AUTO-BUCKET][INIT] model=static-multipreset-gelu dir=%s buckets=32,48,64,80,96,112,128 eager_graphs=0 app_hidden_warm=T32/L32 pregen=off delegate_reuse=0 safe_recovery=1",
                 autobucket_dir_.c_str());
        } else {
            // Soniqo LiteRT remains the verified fixed T128/L64 baseline.
            // No autobucket subdirectory is inspected or inferred.
            autobucket_enabled_ = false;
            interp_->duration.load(duration_path_, duration_threads_, backend_, "duration", 0,
                                   xnn_cache(duration_path_, "duration"), {}, deep_profiler_, deep_profiler_dir_);
            interp_->encoder.load(text_encoder_path_, encoder_threads_, backend_, "encoder", 0,
                                  xnn_cache(text_encoder_path_, "encoder"), {}, deep_profiler_, deep_profiler_dir_);
            interp_->vector.load(vector_estimator_path_, vector_threads_, backend_, "vector_estimator", 0,
                                 xnn_cache(vector_estimator_path_, "vector_estimator"), {}, deep_profiler_, deep_profiler_dir_);
            interp_->vocoder.load(vocoder_path_, vocoder_threads_, backend_, "vocoder", 0,
                                  xnn_cache(vocoder_path_, "vocoder"), {}, deep_profiler_, deep_profiler_dir_);
            backend_report_ = backend_ == Backend::CpuFp16
                ? "CPU/XNNPACK FP16 (Experimental)"
                : "CPU/XNNPACK";
        }
    } else if (backend_ == Backend::Npu && !external_runner_ &&
               static_multipreset_bundle_) {
        // Official LiteRT 2.2 Qualcomm CompiledModel path. Compile only the
        // selected Multi-P root instead of handing all VE signatures to QAIRT.
        // The same path supports FP32 and WI8-AFP32 Multi-P bundles.
        namespace fs = std::filesystem;
        const fs::path bundle_root = fs::path(duration_path_).parent_path();
        const bool has_manifest =
            fs::is_regular_file(bundle_root / "multi_preset_manifest.json") ||
            fs::is_regular_file(bundle_root / "manifest.json");
        if (!has_manifest) {
            throw std::runtime_error(
                "Supertonic GELU MultiPreset manifest is missing from " +
                bundle_root.string());
        }

        constexpr int kProbeT = 64;
        constexpr int kProbeL = 64;
        autobucket_dir_ = bundle_root.string();
        autobucket_enabled_ = true;
        npu_multipreset_t64_l64_probe_ = true;
        interp_ = std::make_unique<InterpreterState>();
        npu_cpu_fallback_ = std::make_unique<InterpreterState>();
        npu_vector_enabled_ = false;
        npu_vector_validated_ = false;
        npu_vector_validation_passes_ = 0;
        npu_rejection_reason_.clear();

        const std::string duration_sig = "T64";
        const std::string encoder_sig = "T64";
        const std::string vector_sig = "T64_L64";
        const std::string vocoder_sig = "L64";

        LOGI("[LITERT-QNN-PROBE] model=Multi-P compiled_model=LiteRT-2.2-QAIRT-2.47 stage=VE signature=%s DP=CPU encoder=CPU vocoder=CPU validation=CPU-shadow",
             vector_sig.c_str());

        // JIT the selected HTP graph before creating the CPU shadow to keep
        // peak preparation memory lower.
        interp_->vector.load(
            vector_estimator_path_, 1, Backend::Npu, "vector_estimator", 0,
            {}, vector_sig, deep_profiler_, deep_profiler_dir_, nullptr,
            nullptr, nullptr, native_library_dir_, accelerator_cache_dir_);

        npu_cpu_fallback_->duration.load(
            duration_path_, duration_threads_, Backend::Cpu, "duration_cpu", 0,
            make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, duration_path_, "duration_T64_cpu", Backend::Cpu),
            duration_sig, deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->encoder.load(
            text_encoder_path_, encoder_threads_, Backend::Cpu, "encoder_cpu", 0,
            make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, text_encoder_path_, "encoder_T64_cpu", Backend::Cpu),
            encoder_sig, deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->vector.load(
            vector_estimator_path_, vector_threads_, Backend::Cpu,
            "vector_estimator_cpu", 0,
            make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, vector_estimator_path_,
                "vector_T64_L64_cpu", Backend::Cpu),
            vector_sig, deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->vocoder.load(
            vocoder_path_, vocoder_threads_, Backend::Cpu, "vocoder_cpu", 0,
            make_xnnpack_weight_cache_path(
                accelerator_cache_dir_, vocoder_path_, "vocoder_L64_cpu", Backend::Cpu),
            vocoder_sig, deep_profiler_, deep_profiler_dir_);

        active_duration_t_ = kProbeT;
        active_encoder_t_ = kProbeT;
        active_vector_t_ = kProbeT;
        active_vector_l_ = kProbeL;
        active_vocoder_l_ = kProbeL;
        npu_vector_enabled_ = true;
        refresh_native_npu_report();
        pre_generation_ = false;
    } else if (backend_ == Backend::Npu && !external_runner_) {
        // NPU-VE-ONLY-v8.2
        // Isolate the dominant Vector Estimator on native QNN HTP.
        // DP is CPU because its NPU output was proven NaN/Inf on-device.
        // Encoder/Vocoder stay CPU for this isolation test so their own QNN
        // numerical behavior cannot hide whether VE acceleration is usable.
        LOGI("Supertonic normal FP32: initializing VE-only native Qualcomm NPU/QNN JIT");

        interp_ = std::make_unique<InterpreterState>();
        npu_vector_enabled_ = false;
        npu_vector_validated_ = false;
        npu_vector_validation_passes_ = 0;
        npu_rejection_reason_.clear();

        // Compile the 244 MB VE before constructing its CPU shadow and the
        // other CPU graphs. QNN JIT temporarily needs model/compiler/context
        // memory; reversing the old order avoids holding every XNNPACK graph
        // resident during that peak.
        interp_->vector.load(
            vector_estimator_path_, num_threads_, Backend::Npu, "vector_estimator", 0, {}, {},
            deep_profiler_, deep_profiler_dir_);

        // The CPU shadow remains mandatory: every first-chunk flow step is
        // compared before NPU audio is accepted, and it is the safe fallback if
        // validation rejects the hardware result.
        npu_cpu_fallback_ = std::make_unique<InterpreterState>();
        npu_cpu_fallback_->duration.load(
            duration_path_, duration_threads_, Backend::Cpu, "duration_cpu", 0,
            make_xnnpack_weight_cache_path(accelerator_cache_dir_, duration_path_, "duration_cpu", Backend::Cpu), {},
            deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->encoder.load(
            text_encoder_path_, encoder_threads_, Backend::Cpu, "encoder_cpu", 0,
            make_xnnpack_weight_cache_path(accelerator_cache_dir_, text_encoder_path_, "encoder_cpu", Backend::Cpu), {},
            deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->vector.load(
            vector_estimator_path_, vector_threads_, Backend::Cpu, "vector_estimator_cpu", 0,
            make_xnnpack_weight_cache_path(accelerator_cache_dir_, vector_estimator_path_, "vector_estimator_cpu", Backend::Cpu), {},
            deep_profiler_, deep_profiler_dir_);
        npu_cpu_fallback_->vocoder.load(
            vocoder_path_, vocoder_threads_, Backend::Cpu, "vocoder_cpu", 0,
            make_xnnpack_weight_cache_path(accelerator_cache_dir_, vocoder_path_, "vocoder_cpu", Backend::Cpu), {},
            deep_profiler_, deep_profiler_dir_);
        npu_vector_enabled_ = true;

        refresh_native_npu_report();
        pre_generation_ = false;
    } else {
        if (!external_runner_) {
            throw std::runtime_error("Supertonic accelerator delegate runner was not supplied");
        }

        const bool cpu_duration = !external_runner_->supports_duration();
        const bool cpu_encoder = !external_runner_->supports_encoder();
        const bool cpu_vector = !external_runner_->supports_vector();
        const bool cpu_vocoder = !external_runner_->supports_vocoder();
        if (cpu_duration || cpu_encoder || cpu_vector || cpu_vocoder) {
            interp_ = std::make_unique<InterpreterState>();
            if (cpu_duration) interp_->duration.load(duration_path_, duration_threads_, Backend::Cpu, "duration", 0,
                make_xnnpack_weight_cache_path(accelerator_cache_dir_, duration_path_, "duration", Backend::Cpu), {}, deep_profiler_, deep_profiler_dir_);
            if (cpu_encoder) interp_->encoder.load(text_encoder_path_, encoder_threads_, Backend::Cpu, "encoder", 0,
                make_xnnpack_weight_cache_path(accelerator_cache_dir_, text_encoder_path_, "encoder", Backend::Cpu), {}, deep_profiler_, deep_profiler_dir_);
            if (cpu_vector) interp_->vector.load(vector_estimator_path_, vector_threads_, Backend::Cpu, "vector_estimator", 0,
                make_xnnpack_weight_cache_path(accelerator_cache_dir_, vector_estimator_path_, "vector_estimator", Backend::Cpu), {}, deep_profiler_, deep_profiler_dir_);
            if (cpu_vocoder) interp_->vocoder.load(vocoder_path_, vocoder_threads_, Backend::Cpu, "vocoder", 0,
                make_xnnpack_weight_cache_path(accelerator_cache_dir_, vocoder_path_, "vocoder", Backend::Cpu), {}, deep_profiler_, deep_profiler_dir_);
        }
        backend_report_ = external_runner_->backend_report();
        // Pre-generation would create additional delegates/interpreters and can
        // increase memory pressure dramatically. Keep it enabled for CPU only.
        pre_generation_ = false;
    }

    namespace fs = std::filesystem;
    tokenizer_ = std::make_unique<SupertonicTokenizer>(
        (fs::path(tokenizer_dir_) / "unicode_indexer.json").string(),
        (fs::path(tokenizer_dir_) / "tts.json").string(),
        kTextT);

    for (const auto& entry : fs::directory_iterator(voice_styles_dir_)) {
        if (entry.path().extension() != ".json") continue;
        VoiceStyle v;
        load_style(entry.path().string(), v.style_ttl, v.style_dp);
        if (v.style_ttl.size() != kStyleTtlFloats || v.style_dp.size() != kStyleDpFloats) continue;
        voices_.emplace(entry.path().stem().string(), std::move(v));
    }
    if (voices_.empty()) throw std::runtime_error("Supertonic: no voice styles in " + voice_styles_dir);
    if (!voices_.count(voice_id_)) voice_id_ = voices_.begin()->first;
}

LiteRTSupertonicTts::~LiteRTSupertonicTts() = default;

int LiteRTSupertonicTts::select_bucket(int real) {
    for (const int bucket : kAutoBuckets) {
        if (real <= bucket) return bucket;
    }
    return 0;
}

int LiteRTSupertonicTts::token_count_from_mask(const std::vector<float>& mask) const {
    int n = 0;
    for (const float v : mask) {
        if (v > 0.5f) ++n;
    }
    return n;
}

int LiteRTSupertonicTts::max_latent_frames() const {
    if (npu_multipreset_t64_l64_probe_) return 64;
    return autobucket_enabled_ ? kMaxLatentL : graph_latent_frames();
}

std::string LiteRTSupertonicTts::preset_duration_path(int /*text_t*/) const {
    if (!autobucket_enabled_) return duration_path_;
    return (std::filesystem::path(autobucket_dir_) / "duration_predictor.tflite").string();
}

std::string LiteRTSupertonicTts::preset_encoder_path(int /*text_t*/) const {
    if (!autobucket_enabled_) return text_encoder_path_;
    return (std::filesystem::path(autobucket_dir_) / "text_encoder.tflite").string();
}

std::string LiteRTSupertonicTts::preset_vector_path(int /*text_t*/, int /*latent_l*/) const {
    if (!autobucket_enabled_) return vector_estimator_path_;
    return (std::filesystem::path(autobucket_dir_) / "vector_estimator.tflite").string();
}

std::string LiteRTSupertonicTts::preset_vocoder_path(int /*latent_l*/) const {
    if (!autobucket_enabled_) return vocoder_path_;
    return (std::filesystem::path(autobucket_dir_) / "vocoder.tflite").string();
}

void LiteRTSupertonicTts::ensure_duration_bucket(int text_t) {
    if (!autobucket_enabled_) return;
    if (npu_multipreset_t64_l64_probe_) {
        if (text_t != 64) {
            throw std::runtime_error(
                "LiteRT Qualcomm NPU preview currently supports T64 only");
        }
        return;
    }
    if (!interp_) throw std::runtime_error("Supertonic AutoBucket: interpreter state missing");
    if (active_duration_t_ == text_t && interp_->duration.interpreter) return;

    const std::string path = preset_duration_path(text_t);
    if (!std::filesystem::is_regular_file(path)) {
        throw std::runtime_error("Supertonic AutoBucket: missing duration preset " + path);
    }
    const auto started = SteadyClock::now();
    const std::string signature_key = "T" + std::to_string(text_t);
    if (interp_->duration.interpreter &&
        interp_->duration.switch_cpu_signature(signature_key)) {
        active_duration_t_ = text_t;
        LOGI("[AUTO-BUCKET][LOAD] graph=duration T=%d mode=signature-switch ms=%.3f active_ve=%d",
             text_t, elapsed_ms(started, SteadyClock::now()),
             interp_->vector.interpreter ? 1 : 0);
        return;
    }
    if (interp_->duration.interpreter) {
        LOGI("[AUTO-BUCKET][SIGNATURE-FALLBACK] graph=duration T=%d action=recreate", text_t);
    }
    interp_->duration.release_resources();
    active_duration_t_ = 0;
    interp_->duration.load(
        path, duration_threads_, backend_, "duration", 0,
        interp_->shared_duration_cache_path,
        signature_key, deep_profiler_, deep_profiler_dir_,
        interp_->shared_duration_model,
        interp_->shared_duration_cache_provider,
        &interp_->shared_duration_cache_mutex);
    active_duration_t_ = text_t;
    LOGI("[AUTO-BUCKET][LOAD] graph=duration T=%d ms=%.3f active_ve=%d",
         text_t, elapsed_ms(started, SteadyClock::now()),
         interp_->vector.interpreter ? 1 : 0);
}

void LiteRTSupertonicTts::ensure_encoder_vector_bucket(int text_t, int latent_l) {
    if (!autobucket_enabled_) return;
    if (npu_multipreset_t64_l64_probe_) {
        if (text_t != 64 || latent_l != 64) {
            throw std::runtime_error(
                "LiteRT Qualcomm NPU preview currently supports T64/L64 only");
        }
        return;
    }
    if (!interp_) throw std::runtime_error("Supertonic AutoBucket: interpreter state missing");

    if (active_encoder_t_ != text_t || !interp_->encoder.interpreter) {
        const std::string path = preset_encoder_path(text_t);
        if (!std::filesystem::is_regular_file(path)) {
            throw std::runtime_error("Supertonic AutoBucket: missing encoder preset " + path);
        }
        const auto started = SteadyClock::now();
        const std::string signature_key = "T" + std::to_string(text_t);
        if (interp_->encoder.interpreter &&
            interp_->encoder.switch_cpu_signature(signature_key)) {
            active_encoder_t_ = text_t;
            LOGI("[AUTO-BUCKET][LOAD] graph=encoder T=%d mode=signature-switch ms=%.3f",
                 text_t, elapsed_ms(started, SteadyClock::now()));
        } else {
            if (interp_->encoder.interpreter) {
                LOGI("[AUTO-BUCKET][SIGNATURE-FALLBACK] graph=encoder T=%d action=recreate", text_t);
            }
            interp_->encoder.release_resources();
            active_encoder_t_ = 0;
            interp_->encoder.load(
                path, encoder_threads_, backend_, "encoder", 0,
                interp_->shared_encoder_cache_path,
                signature_key, deep_profiler_, deep_profiler_dir_,
                interp_->shared_encoder_model,
                interp_->shared_encoder_cache_provider,
                &interp_->shared_encoder_cache_mutex);
            active_encoder_t_ = text_t;
            LOGI("[AUTO-BUCKET][LOAD] graph=encoder T=%d mode=recreate ms=%.3f",
                 text_t, elapsed_ms(started, SteadyClock::now()));
        }
    }

    if (active_vector_t_ != text_t || active_vector_l_ != latent_l ||
        !interp_->vector.interpreter) {
        const std::string path = preset_vector_path(text_t, latent_l);
        if (!std::filesystem::is_regular_file(path)) {
            throw std::runtime_error("Supertonic AutoBucket: missing VE preset " + path);
        }
        const auto started = SteadyClock::now();
        const std::string signature_key =
            "T" + std::to_string(text_t) + "_L" + std::to_string(latent_l);
        if (interp_->vector.interpreter &&
            interp_->vector.switch_cpu_signature(signature_key)) {
            active_vector_t_ = text_t;
            active_vector_l_ = latent_l;
            LOGI("[AUTO-BUCKET][LOAD] graph=vector T=%d L=%d mode=signature-switch ms=%.3f active_ve=1",
                 text_t, latent_l, elapsed_ms(started, SteadyClock::now()));
        } else {
            if (interp_->vector.interpreter) {
                LOGI("[AUTO-BUCKET][SIGNATURE-FALLBACK] graph=vector T=%d L=%d action=recreate",
                     text_t, latent_l);
            }
            interp_->vector.release_resources();
            active_vector_t_ = 0;
            active_vector_l_ = 0;
            LOGI("[AUTO-BUCKET][VE-EVICT] active_ve=0 next=T%d/L%d reason=recreate-fallback",
                 text_t, latent_l);
            interp_->vector.load(
                path, vector_threads_, backend_, "vector_estimator", 0,
                interp_->shared_vector_cache_path,
                signature_key, deep_profiler_, deep_profiler_dir_,
                interp_->shared_vector_model,
                interp_->shared_vector_cache_provider,
                &interp_->shared_vector_cache_mutex);
            active_vector_t_ = text_t;
            active_vector_l_ = latent_l;
            LOGI("[AUTO-BUCKET][LOAD] graph=vector T=%d L=%d mode=recreate ms=%.3f active_ve=1",
                 text_t, latent_l, elapsed_ms(started, SteadyClock::now()));
        }
    }
}

void LiteRTSupertonicTts::ensure_vocoder_bucket(int latent_l) {
    if (!autobucket_enabled_) return;
    if (npu_multipreset_t64_l64_probe_) {
        if (latent_l != 64) {
            throw std::runtime_error(
                "LiteRT Qualcomm NPU preview currently supports L64 only");
        }
        return;
    }
    if (!interp_) throw std::runtime_error("Supertonic AutoBucket: interpreter state missing");
    if (active_vocoder_l_ == latent_l && interp_->vocoder.interpreter) return;

    const std::string path = preset_vocoder_path(latent_l);
    if (!std::filesystem::is_regular_file(path)) {
        throw std::runtime_error("Supertonic AutoBucket: missing vocoder preset " + path);
    }
    const auto started = SteadyClock::now();
    const std::string signature_key = "L" + std::to_string(latent_l);
    if (interp_->vocoder.interpreter &&
        interp_->vocoder.switch_cpu_signature(signature_key)) {
        active_vocoder_l_ = latent_l;
        LOGI("[AUTO-BUCKET][LOAD] graph=vocoder L=%d mode=signature-switch ms=%.3f",
             latent_l, elapsed_ms(started, SteadyClock::now()));
    } else {
        if (interp_->vocoder.interpreter) {
            LOGI("[AUTO-BUCKET][SIGNATURE-FALLBACK] graph=vocoder L=%d action=recreate", latent_l);
        }
        interp_->vocoder.release_resources();
        active_vocoder_l_ = 0;
        interp_->vocoder.load(
            path, vocoder_threads_, backend_, "vocoder", 0,
            interp_->shared_vocoder_cache_path,
            signature_key, deep_profiler_, deep_profiler_dir_,
            interp_->shared_vocoder_model,
            interp_->shared_vocoder_cache_provider,
            &interp_->shared_vocoder_cache_mutex);
        active_vocoder_l_ = latent_l;
        LOGI("[AUTO-BUCKET][LOAD] graph=vocoder L=%d mode=recreate ms=%.3f",
             latent_l, elapsed_ms(started, SteadyClock::now()));
    }
}

void LiteRTSupertonicTts::ensure_inference_bucket(int text_t, int latent_l) {
    if (!autobucket_enabled_) return;
    ensure_encoder_vector_bucket(text_t, latent_l);
    ensure_vocoder_bucket(latent_l);
}

void LiteRTSupertonicTts::refresh_native_npu_report() {
    if (backend_ != Backend::Npu || external_runner_) return;
    const std::string model_name = npu_multipreset_t64_l64_probe_
        ? "Multi-P FP32 T64/L64"
        : "Soniqo FP32";
    const std::string runtime_name = npu_multipreset_t64_l64_probe_
        ? "Qualcomm QNN LiteRT delegate 2.47"
        : "LiteRT Qualcomm CompiledModel";
    if (!npu_vector_enabled_) {
        backend_report_ =
            model_name + " / CPU/XNNPACK (" + runtime_name + " rejected" +
            (npu_rejection_reason_.empty()
                 ? std::string()
                 : std::string(": ") + npu_rejection_reason_) +
            ")";
        return;
    }
    backend_report_ =
        model_name + " / " + runtime_name + " VE " +
        (npu_vector_validated_
             ? "validated"
             : "validating " + std::to_string(npu_vector_validation_passes_) +
                   "/" + std::to_string(std::max(1, total_step_))) +
        " (DP=CPU,Enc=CPU,Voc=CPU)";
}

void LiteRTSupertonicTts::destroy_graphs() noexcept {
    interp_.reset();
    npu_cpu_fallback_.reset();
}

void LiteRTSupertonicTts::cancel() {
    cancelled_.store(true);
    for (auto& engine : pregen_engines_) {
        if (engine) engine->cancel();
    }
}

void LiteRTSupertonicTts::set_pre_generation(bool enabled) {
    // AutoBucket's acceptance gate is exactly one packed VE.  A pre-generation
    // worker owns another full engine/VE, so keep it disabled in this A/B build.
    if (autobucket_enabled_) {
        pre_generation_ = false;
        if (enabled) LOGI("[AUTO-BUCKET] pregen_forced_off=1 reason=single-active-ve");
        return;
    }
    // Accelerator delegates are intentionally single-runner/single-thread. Do
    // not create duplicate 380 MB delegate engines for speculative pre-generation.
    if (!is_cpu_backend(backend_)) { pre_generation_ = false; return; }
    // Settings may be toggled while Android is still synthesizing. Do not destroy
    // in-flight pre-generation engines here; the next synthesis observes the new
    // flag safely after the current request finishes.
    pre_generation_ = enabled;
}

void LiteRTSupertonicTts::set_pre_generation_queue(int depth) {
    // The engine pool is resized only at the start of the next synthesis request,
    // after the current request has released its native synthesis lock.
    pre_generation_queue_ = std::max(1, std::min(3, depth));
}

void LiteRTSupertonicTts::set_chunk_gap_ms(int min_ms, int max_ms) {
    chunk_gap_min_ms_ = std::max(0, std::min(2000, min_ms));
    chunk_gap_max_ms_ = std::max(chunk_gap_min_ms_, std::min(2000, max_ms));
}

void LiteRTSupertonicTts::set_trailing_silence_trim_ms(int trim_ms) {
    trailing_silence_trim_ms_ = std::max(0, std::min(500, trim_ms));
}

void LiteRTSupertonicTts::set_total_step(int total_step) {
    const int next = std::max(1, std::min(64, total_step));
    if (backend_ == Backend::Npu && next != total_step_) {
        npu_vector_validated_ = false;
        npu_vector_validation_passes_ = 0;
        npu_rejection_reason_.clear();
    }
    total_step_ = next;
    refresh_native_npu_report();
    for (auto& engine : pregen_engines_) if (engine) engine->set_total_step(total_step_);
}

void LiteRTSupertonicTts::set_chunk_cap(int chunk_cap) {
    // Public/manual chunk sizing is retired for normal AutoBucket execution.
    // 0 means: let the tokenizer emit the largest T<=128-safe chunks, then
    // split only when duration would overflow the L<=128 graph window.
    // Positive values remain available only for internal diagnostics.
    chunk_cap_ = chunk_cap <= 0 ? 0 : std::max(24, std::min(96, chunk_cap));
    for (auto& engine : pregen_engines_) if (engine) engine->set_chunk_cap(chunk_cap_);
}

void LiteRTSupertonicTts::set_voice(const std::string& voice_id) {
    if (!voices_.count(voice_id)) throw std::invalid_argument("Supertonic: unknown voice '" + voice_id + "'");
    voice_id_ = voice_id;
    for (auto& engine : pregen_engines_) if (engine) engine->set_voice(voice_id);
}

std::vector<std::string> LiteRTSupertonicTts::voices() const {
    std::vector<std::string> ids;
    ids.reserve(voices_.size());
    for (const auto& kv : voices_) ids.push_back(kv.first);
    std::sort(ids.begin(), ids.end());
    return ids;
}

const LiteRTSupertonicTts::VoiceStyle& LiteRTSupertonicTts::current_voice() const {
    const auto it = voices_.find(voice_id_);
    if (it == voices_.end()) throw std::runtime_error("Supertonic: voice not loaded");
    return it->second;
}

float LiteRTSupertonicTts::predict_duration_only(
    const std::string& chunk,
    const std::string& language,
    bool packing_probe) {
    const auto probe_start = SteadyClock::now();
    const VoiceStyle& voice = current_voice();
    const auto tok = tokenizer_->process(chunk, language, kTextT);
    const int t_real = token_count_from_mask(tok.mask);
    const int text_t = npu_multipreset_t64_l64_probe_
        ? (t_real <= 64 ? 64 : 0)
        : (autobucket_enabled_ ? select_bucket(t_real) : kTextT);
    if (text_t <= 0) {
        throw std::runtime_error(
            npu_multipreset_t64_l64_probe_
                ? "LiteRT Qualcomm NPU preview token count exceeds T64"
                : "Supertonic AutoBucket: token count exceeds T128");
    }
    std::vector<int64_t> ids64;
    ids64.reserve(static_cast<size_t>(text_t));
    for (int i = 0; i < text_t; ++i) ids64.push_back(tok.ids[static_cast<size_t>(i)]);
    if (autobucket_enabled_) ensure_duration_bucket(text_t);

    const bool full_external_runner =
        external_runner_ && external_runner_->supports_duration() &&
        external_runner_->supports_encoder() && external_runner_->supports_vector() &&
        external_runner_->supports_vocoder();
    const bool normal_native_npu = backend_ == Backend::Npu && !external_runner_;
    const bool native_cpu_graphs = is_cpu_backend(backend_) && !full_external_runner;

    float duration = 0.0f;
    const auto model_start = SteadyClock::now();
    if (normal_native_npu) {
        auto& g = npu_cpu_fallback_->duration;
        g.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
        g.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
        g.write_input_named("style_dp", {1, 8, 16}, voice.style_dp.data(), voice.style_dp.size() * sizeof(float));
        g.run("duration packing probe CPU fallback Run");
        g.read_output({1}, &duration, sizeof(float));
    } else if (native_cpu_graphs || !external_runner_->supports_duration()) {
        interp_->duration.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
        interp_->duration.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
        interp_->duration.write_input_named("style_dp", {1, 8, 16}, voice.style_dp.data(), voice.style_dp.size() * sizeof(float));
        interp_->duration.run("duration packing probe Run");
        interp_->duration.read_output({1}, &duration, sizeof(float));
    } else {
        external_runner_->run_duration(ids64.data(), ids64.size(),
                                       voice.style_dp.data(), voice.style_dp.size(),
                                       tok.mask.data(), static_cast<size_t>(text_t), &duration);
    }
    const double model_ms = elapsed_ms(model_start, SteadyClock::now());
    profile_.duration_predictor_ms += model_ms;
    if (!(duration > 0.0f) || !std::isfinite(duration)) {
        throw std::runtime_error("Supertonic: duration packing probe returned invalid/non-finite output");
    }
    duration_cache_[chunk] = duration;
    if (packing_probe) {
        ++profile_.packing_probe_count;
        profile_.packing_probe_ms += elapsed_ms(probe_start, SteadyClock::now());
    }
    return duration;
}

std::vector<std::string> LiteRTSupertonicTts::build_duration_aware_chunks(
    const std::string& text,
    const std::string& language,
    int raw_cap) {
    // Keep a comparable baseline count using the user's previous character cap.
    const int baseline_cap = std::max(24, std::min(96, raw_cap));
    const auto baseline = tokenizer_->chunk(text, language, baseline_cap);
    profile_.packing_original_chunks = static_cast<int>(baseline.size());

    // Balanced/long CPU modes may use the full token-safe raw window. The duration
    // predictor, rather than a language-wide characters/second heuristic, becomes
    // the real fixed-L=64 admission control. Conservative <=40-char mode remains
    // conservative by design.
    const int packing_cap = baseline_cap <= 40 ? baseline_cap : 96;
    const auto candidates = tokenizer_->chunk(text, language, packing_cap);
    const double window_s = static_cast<double>(max_latent_frames()) * kChunkSamples / kSampleRateConst;
    const double target_s = window_s * 0.97; // leave a small rounding/model-speed margin

    std::vector<std::string> out;
    std::function<void(const std::string&, int)> admit;
    admit = [&](const std::string& candidate, int depth) {
        if (candidate.empty()) return;
        const float duration = predict_duration_only(candidate, language, true);
        if (duration <= target_s || depth >= 8) {
            out.push_back(candidate);
            return;
        }

        const auto cps = utf8_to_u32(candidate);
        const size_t split = choose_semantic_split(cps);
        if (split == 0 || split >= cps.size()) {
            out.push_back(candidate);
            return;
        }
        std::vector<char32_t> left(cps.begin(), cps.begin() + static_cast<std::ptrdiff_t>(split));
        std::vector<char32_t> right(cps.begin() + static_cast<std::ptrdiff_t>(split), cps.end());
        auto trim_ws = [](std::vector<char32_t>& v) {
            auto ws = [](char32_t c) { return c == U' ' || c == U'\t' || c == U'\n' || c == U'\r'; };
            while (!v.empty() && ws(v.front())) v.erase(v.begin());
            while (!v.empty() && ws(v.back())) v.pop_back();
        };
        trim_ws(left);
        trim_ws(right);
        if (left.empty() || right.empty()) {
            out.push_back(candidate);
            return;
        }
        ++profile_.packing_splits;
        admit(u32_to_utf8(left), depth + 1);
        admit(u32_to_utf8(right), depth + 1);
    };

    for (const auto& candidate : candidates) admit(candidate, 0);
    if (out.empty()) out = baseline;
    profile_.packing_final_chunks = static_cast<int>(out.size());
    LOGI("[DURATION-PACK] baseline_cap=%d packing_cap=%d baseline_chunks=%d candidates=%zu final_chunks=%zu probes=%d splits=%d probe_ms=%.3f target_s=%.4f",
         baseline_cap, packing_cap, profile_.packing_original_chunks, candidates.size(), out.size(),
         profile_.packing_probe_count, profile_.packing_splits, profile_.packing_probe_ms, target_s);
    return out;
}

void LiteRTSupertonicTts::synthesize(const std::string& text,
                                     const std::string& language,
                                     TTSChunkCallback on_chunk) {
    const auto total_start = SteadyClock::now();
    profile_.reset();
    last_pcm_.clear();
    duration_cache_.clear();
    cancelled_.store(false);
    seed_used_ = seed_ == 0 ? std::random_device{}() : seed_;

    // Keep the model itself at 1x and apply user-selected speech rate after synthesis.
    // This prevents high-speed generation from shortening the predicted duration so far
    // that the fixed L=64 vocoder window cuts words off.
    speed_ = 1.0f;

    // AutoBucket no longer uses a user-selected character cap.  The tokenizer
    // already validates the fully preprocessed/NFKD token count, so raw_cap=0
    // gives the largest semantic chunk that still fits T<=128.  Duration
    // overflow is handled below against the real L<=128 window.
    const int dur_cap = chunk_cap_ > 0 ? std::max(24, std::min(96, chunk_cap_)) : 0;
    const auto chunking_start = SteadyClock::now();
    // REV33.1: preserve the tokenizer baseline, but resolve ONLY true fixed-window
    // overflow before expensive encoder/VE/vocoder work starts.  REV33 deferred
    // overflow discovery until synth_chunk(), which meant an overflowing parent was
    // fully synthesized and discarded before its children were synthesized again.
    // It also left chunks.size()==1, disabling pre-generation even though synth_safe()
    // later split that request into multiple playable chunks.
    //
    // Duration prediction is not duplicate work here: predict_duration_only() stores
    // the result in duration_cache_, and synth_chunk() consumes that cached duration.
    // Therefore this moves the normal duration stage earlier and avoids wasted full
    // inference, without restoring REV31's aggressive 0.97-window "packing" policy.
    const auto baseline_chunks = tokenizer_->chunk(text, language, dur_cap);
    profile_.chunking_ms += elapsed_ms(chunking_start, SteadyClock::now());
    profile_.packing_original_chunks = static_cast<int>(baseline_chunks.size());
    const auto overflow_guard_start = SteadyClock::now();
    const double hard_window_s = static_cast<double>(max_latent_frames()) * kChunkSamples / kSampleRateConst;
    int overflow_guard_checks = 0;
    int overflow_guard_splits = 0;
    std::vector<std::string> chunks;
    std::function<void(const std::string&, int)> admit_overflow_safe;
    admit_overflow_safe = [&](const std::string& candidate, int depth) {
        if (candidate.empty()) return;
        ++overflow_guard_checks;
        bool token_overflow = false;
        if (npu_multipreset_t64_l64_probe_) {
            const auto token_probe = tokenizer_->process(candidate, language, kTextT);
            token_overflow = token_count_from_mask(token_probe.mask) > 64;
        }
        const float duration = token_overflow
            ? 0.0f
            : predict_duration_only(candidate, language, false);
        const bool overflow = token_overflow || duration > hard_window_s * 1.001;
        if (!overflow || depth >= 8) {
            chunks.push_back(candidate);
            return;
        }
        const auto cps = utf8_to_u32(candidate);
        if (cps.size() <= 8) {
            chunks.push_back(candidate);
            return;
        }
        const size_t split = choose_semantic_split(cps);
        if (split == 0 || split >= cps.size()) {
            chunks.push_back(candidate);
            return;
        }
        std::vector<char32_t> left(cps.begin(), cps.begin() + static_cast<std::ptrdiff_t>(split));
        std::vector<char32_t> right(cps.begin() + static_cast<std::ptrdiff_t>(split), cps.end());
        auto trim_ws = [](std::vector<char32_t>& v) {
            while (!v.empty() && (v.front() == U' ' || v.front() == U'\t' || v.front() == U'\n' || v.front() == U'\r')) v.erase(v.begin());
            while (!v.empty() && (v.back() == U' ' || v.back() == U'\t' || v.back() == U'\n' || v.back() == U'\r')) v.pop_back();
        };
        trim_ws(left);
        trim_ws(right);
        if (left.empty() || right.empty()) {
            chunks.push_back(candidate);
            return;
        }
        ++overflow_guard_splits;
        admit_overflow_safe(u32_to_utf8(left), depth + 1);
        admit_overflow_safe(u32_to_utf8(right), depth + 1);
    };
    for (const auto& candidate : baseline_chunks) admit_overflow_safe(candidate, 0);
    if (chunks.empty()) chunks = baseline_chunks;
    profile_.packing_final_chunks = static_cast<int>(chunks.size());
    profile_.chunk_count = static_cast<int>(chunks.size());
    const double overflow_guard_ms = elapsed_ms(overflow_guard_start, SteadyClock::now());
    LOGI("[OVERFLOW-GUARD] baseline_chunks=%zu final_chunks=%zu guard_checks=%d splits=%d guard_ms=%.3f hard_window_s=%.4f policy=token-budget-auto+hard-L-overflow",
         baseline_chunks.size(), chunks.size(), overflow_guard_checks, overflow_guard_splits, overflow_guard_ms, hard_window_s);
    LOGI("[DURATION-PACK] mode=disabled baseline_chunks=%zu final_chunks=%zu probes=0 splits=0",
         baseline_chunks.size(), baseline_chunks.size());

    struct BucketHint { int text_t = 0; int latent_l = 0; };
    std::vector<BucketHint> bucket_hints(chunks.size());
    if (autobucket_enabled_ && is_cpu_backend(backend_)) {
        const auto hint_t0 = SteadyClock::now();
        for (size_t i = 0; i < chunks.size(); ++i) {
            const auto tok = tokenizer_->process(chunks[i], language, kTextT);
            const int t_real = token_count_from_mask(tok.mask);
            const int text_t = select_bucket(t_real);
            const auto it = duration_cache_.find(chunks[i]);
            if (text_t <= 0 || it == duration_cache_.end() || !(it->second > 0.0f)) continue;
            const long long wav_len = static_cast<long long>(it->second * kSampleRateConst);
            const int l_true = std::max(1, static_cast<int>((wav_len + kChunkSamples - 1) / kChunkSamples));
            const int latent_l = select_bucket(l_true);
            if (latent_l <= 0) continue;
            bucket_hints[i] = BucketHint{text_t, latent_l};
        }
        profile_.preset_hint_ms += elapsed_ms(hint_t0, SteadyClock::now());
        LOGI("[AUTO-BUCKET][LOOKAHEAD-HINTS] chunks=%zu hint_ms=%.3f policy=token+cached-duration",
             chunks.size(), profile_.preset_hint_ms);
    }
    // Accumulate VE timings across every chunk. Previously synth_chunk() reset this
    // vector for each chunk, so the profiler displayed only the final chunk's VE cost.
    profile_.ve_step_ms.assign(static_cast<size_t>(total_step_), 0.0);

    std::vector<float> full_pcm;
    constexpr size_t kCrossfadeSamples = 220; // 5 ms @ 44.1 kHz; do not trim phonemes at chunk edges.

    auto append_chunk = [&](const std::vector<float>& pcm) {
        if (pcm.empty()) return;
        if (!Graph::all_finite(pcm.data(), pcm.size())) {
            throw std::runtime_error("Supertonic: refusing non-finite chunk PCM after accelerator fallback");
        }
        const auto append_start = SteadyClock::now();
        const auto trimmed = trim_edge_silence(pcm, trailing_silence_trim_ms_);
        const std::vector<float>& source = trimmed.empty() ? pcm : trimmed;
        if (full_pcm.empty()) {
            full_pcm.insert(full_pcm.end(), source.begin(), source.end());
            profile_.append_ms += elapsed_ms(append_start, SteadyClock::now());
            return;
        }
        const size_t n = std::min({kCrossfadeSamples, full_pcm.size(), source.size()});
        const size_t old_start = full_pcm.size() - n;
        for (size_t i = 0; i < n; ++i) {
            const float t = static_cast<float>(i + 1) / static_cast<float>(n);
            full_pcm[old_start + i] = full_pcm[old_start + i] * (1.0f - t) + source[i] * t;
        }
        if (source.size() > n) full_pcm.insert(full_pcm.end(), source.begin() + static_cast<std::ptrdiff_t>(n), source.end());
        profile_.append_ms += elapsed_ms(append_start, SteadyClock::now());
    };

    // Streaming keeps a 5 ms tail pending so the next chunk can crossfade into it.
    // The first playable bytes are delivered immediately after the first chunk is
    // synthesized, while later chunks are generated only after audioAvailable()
    // returns; this gives Android clients a one-chunk look-ahead opportunity.
    std::vector<float> pending_stream_tail;
    bool stream_started = false;

    struct PregenResult {
        std::vector<float> pcm;
        bool truncated = false;
        PerformanceProfile worker_profile;
    };

    const bool use_cpu_pregen =
        is_cpu_backend(backend_) && pre_generation_ && on_chunk && chunks.size() > 1;
    const int requested_pregen_depth = use_cpu_pregen
        ? std::max(1, std::min(3, pre_generation_queue_)) : 0;
    // REV33: one full-speed look-ahead worker is the safe CPU policy.  REV31 split
    // four foreground threads across depth=3, leaving each speculative VE with one
    // thread and ~1 s of work.  More than one simultaneous TTS graph also competes
    // for the same mobile CPU.  Queue depth remains a user preference for future A/B,
    // but the effective concurrent depth is intentionally capped at one until logs
    // prove that parallel speculative runners help on a given device.
    const int effective_pregen_depth = use_cpu_pregen ? 1 : 0;
    const int pregen_worker_threads = use_cpu_pregen ? num_threads_ : 0;
    profile_.pregen_queue_depth = effective_pregen_depth;
    profile_.pregen_worker_threads = pregen_worker_threads;
    LOGI("[PREGEN-BUDGET] enabled=%d requested_depth=%d effective_depth=%d foreground_threads=%d worker_threads=%d aggregate_worker_budget=%d launch_during_first_audio=1",
         use_cpu_pregen ? 1 : 0, requested_pregen_depth, effective_pregen_depth,
         num_threads_, pregen_worker_threads, effective_pregen_depth * pregen_worker_threads);
    bool pregen_pool_ready = false;
    bool pregen_kicked = false;
    bool pregen_pool_reusable = use_cpu_pregen && pregen_engines_.size() == 1 &&
        pregen_engines_[0] && pregen_engines_[0]->num_threads() == pregen_worker_threads &&
        pregen_engines_[0]->backend() == backend_;
    std::function<void()> pregen_kick;
    auto last_audio_emit = SteadyClock::time_point{};
    auto emit_stream = [&](const std::vector<float>& pcm, bool final_chunk) {
        if (!on_chunk) return;
        const auto stream_start = SteadyClock::now();
        auto emit = [&](const std::vector<float>& data) {
            if (data.empty()) return;
            auto push = [&](const float* ptr, size_t count) {
                if (!ptr || count == 0) return;
                if (!stream_started) {
                    stream_started = true;
                    profile_.ttfa_ms = elapsed_ms(total_start, SteadyClock::now());
                }
                if (last_audio_emit != SteadyClock::time_point{}) {
                    auto now = SteadyClock::now();
                    const double current_gap_ms = elapsed_ms(last_audio_emit, now);
                    if (current_gap_ms < static_cast<double>(chunk_gap_min_ms_)) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(chunk_gap_min_ms_) - std::chrono::milliseconds(static_cast<int>(current_gap_ms)));
                    }
                    const double gap_ms = elapsed_ms(last_audio_emit, SteadyClock::now());
                    profile_.max_chunk_gap_ms = std::max(profile_.max_chunk_gap_ms, gap_ms);
                    profile_.avg_chunk_gap_ms =
                        ((profile_.avg_chunk_gap_ms * profile_.chunk_gap_count) + gap_ms) /
                        static_cast<double>(profile_.chunk_gap_count + 1);
                    ++profile_.chunk_gap_count;
                    if (gap_ms > static_cast<double>(chunk_gap_max_ms_)) ++profile_.chunk_gap_over_max_count;
                }
                ++profile_.streamed_chunks;
                on_chunk(ptr, count, false);
                last_audio_emit = SteadyClock::now();
            };

            // Feed enough first-chunk PCM to Android to establish TTFA/playback,
            // then start the one-chunk look-ahead while Android consumes that PCM.
            // This moves expensive pregen setup/VE into playback time instead of
            // starting only after the entire first chunk has been handed off.
            if (use_cpu_pregen && !pregen_kicked && pregen_kick) {
                pregen_kicked = true;
                if (pregen_pool_reusable) {
                    // Warm path: setup is effectively free. Launch BEFORE entering the
                    // blocking Android audio callback so worker inference overlaps the
                    // entire first-chunk handoff/playback window.
                    pregen_kick();
                    push(data.data(), data.size());
                } else {
                    // Cold path: avoid adding delegate/session construction to TTFA.
                    // Prime Android first, then build the persistent look-ahead engine.
                    constexpr size_t kPregenPrimeSamples = static_cast<size_t>(kSampleRateConst * 5 / 4); // 1.25 s
                    const size_t prime = std::min(data.size(), kPregenPrimeSamples);
                    push(data.data(), prime);
                    pregen_kick();
                    if (prime < data.size()) push(data.data() + static_cast<std::ptrdiff_t>(prime), data.size() - prime);
                }
            } else {
                push(data.data(), data.size());
            }
        };

        if (!pcm.empty()) {
            const auto trimmed = trim_edge_silence(pcm, trailing_silence_trim_ms_);
            const std::vector<float>& source = trimmed.empty() ? pcm : trimmed;
            if (pending_stream_tail.empty()) {
                if (source.size() <= kCrossfadeSamples) {
                    pending_stream_tail = source;
                } else {
                    const size_t cut = source.size() - kCrossfadeSamples;
                    std::vector<float> head(source.begin(), source.begin() + static_cast<std::ptrdiff_t>(cut));
                    emit(head);
                    pending_stream_tail.assign(source.begin() + static_cast<std::ptrdiff_t>(cut), source.end());
                }
            } else {
                const size_t n = std::min({kCrossfadeSamples, pending_stream_tail.size(), source.size()});
                std::vector<float> seam(n);
                for (size_t i = 0; i < n; ++i) {
                    const float t = static_cast<float>(i + 1) / static_cast<float>(n);
                    seam[i] = pending_stream_tail[pending_stream_tail.size() - n + i] * (1.0f - t) + source[i] * t;
                }
                emit(seam);
                if (source.size() <= kCrossfadeSamples) {
                    pending_stream_tail = source;
                } else {
                    const size_t keep = std::min(kCrossfadeSamples, source.size());
                    if (source.size() > n + keep) {
                        std::vector<float> middle(source.begin() + static_cast<std::ptrdiff_t>(n),
                                                   source.end() - static_cast<std::ptrdiff_t>(keep));
                        emit(middle);
                    }
                    pending_stream_tail.assign(source.end() - static_cast<std::ptrdiff_t>(keep), source.end());
                }
            }
        }
        if (final_chunk) {
            emit(pending_stream_tail);
            pending_stream_tail.clear();
            on_chunk(nullptr, 0, true);
        }
        profile_.stream_emit_ms += elapsed_ms(stream_start, SteadyClock::now());
    };

    // A duration prediction can still exceed the fixed 64-frame vocoder window even
    // when the text fits the 128-codepoint input tensor. In that case synth_chunk()
    // necessarily truncates the tail. Detect that case and recursively split the text
    // before accepting any audio. This is the important anti-"sentence swallowed"
    // safeguard for long Korean/Chinese sentences.
    std::function<void(const std::string&, size_t, int, int)> synth_safe;
    synth_safe = [&](const std::string& chunk, size_t seed_index, int next_text_t, int next_latent_l) {
        if (cancelled_.load() || chunk.empty()) return;
        const int before = profile_.truncated_chunks;
        auto pcm = synth_chunk(chunk, language, seed_index, next_text_t, next_latent_l);
        const bool truncated = profile_.truncated_chunks > before;
        if (truncated) {
            const auto cps = utf8_to_u32(chunk);
            if (cps.size() > 8) {
                const size_t split = choose_semantic_split(cps);
                if (split > 0 && split < cps.size()) {
                    std::vector<char32_t> left(cps.begin(), cps.begin() + static_cast<std::ptrdiff_t>(split));
                    std::vector<char32_t> right(cps.begin() + static_cast<std::ptrdiff_t>(split), cps.end());

                    // A whitespace boundary is not speech content; remove it from
                    // both children. This is what prevents cases such as
                    // "황금 / 기 시절" after a duration-overflow retry.
                    auto trim_ws = [](std::vector<char32_t>& v) {
                        while (!v.empty() &&
                               (v.front() == U' ' || v.front() == U'\t' ||
                                v.front() == U'\n' || v.front() == U'\r')) {
                            v.erase(v.begin());
                        }
                        while (!v.empty() &&
                               (v.back() == U' ' || v.back() == U'\t' ||
                                v.back() == U'\n' || v.back() == U'\r')) {
                            v.pop_back();
                        }
                    };
                    trim_ws(left);
                    trim_ws(right);

                    auto to_text = [](const std::vector<char32_t>& v) { return u32_to_utf8(v); };
                    profile_.truncated_chunks = before;
                    // Recursive emergency splitting invalidates the precomputed
                    // semantic look-ahead relationship; disable overlap for these
                    // exceptional children and let their buckets resolve normally.
                    if (!left.empty()) synth_safe(to_text(left), seed_index * 2 + 1, 0, 0);
                    if (!right.empty()) synth_safe(to_text(right), seed_index * 2 + 2, 0, 0);
                    return;
                }
            }
        }
        if (!pcm.empty()) {
            append_chunk(pcm);
            emit_stream(pcm, false);
        }
    };

    struct PregenTask {
        size_t chunk_index = 0;
        size_t engine_index = 0;
        std::future<PregenResult> future;
    };
    std::deque<PregenTask> pending;
    size_t next_chunk_to_launch = 1;

    auto ensure_pregen_pool = [&]() -> bool {
        if (!use_cpu_pregen) return false;
        const auto setup_start = SteadyClock::now();
        try {
            bool rebuild = static_cast<int>(pregen_engines_.size()) != effective_pregen_depth;
            if (!rebuild) {
                for (const auto& engine : pregen_engines_) {
                    if (!engine || engine->num_threads() != pregen_worker_threads || engine->backend() != backend_) {
                        rebuild = true;
                        break;
                    }
                }
            }
            if (rebuild) {
                for (auto& engine : pregen_engines_) if (engine) engine->cancel();
                pregen_engines_.clear();
                pregen_engines_.reserve(static_cast<size_t>(effective_pregen_depth));
                for (int i = 0; i < effective_pregen_depth; ++i) {
                    pregen_engines_.push_back(std::make_unique<LiteRTSupertonicTts>(
                        duration_path_, text_encoder_path_, vector_estimator_path_, vocoder_path_,
                        tokenizer_dir_, voice_styles_dir_, false, pregen_worker_threads, backend_,
                        native_library_dir_, accelerator_cache_dir_,
                        false,  // Keep REV34 behavior: Deep Profiler does not recurse into pregen workers.
                        std::shared_ptr<SupertonicExternalRunner>{},
                        static_multipreset_bundle_));
                }
            }
            for (auto& engine : pregen_engines_) {
                engine->set_voice(voice_id_);
                engine->set_total_step(total_step_);
                engine->set_speed(1.0f);
                engine->set_chunk_cap(dur_cap);
                engine->set_pre_generation(false);
                engine->seed_used_ = seed_used_;
                engine->cancelled_.store(false);
                engine->duration_cache_.clear();
            }
            const double setup_ms = elapsed_ms(setup_start, SteadyClock::now());
            profile_.pregen_setup_ms += setup_ms;
            LOGI("[PREGEN-SETUP] depth=%d worker_threads=%d rebuild=%d setup_ms=%.3f after_first_audio=%d",
                 effective_pregen_depth, pregen_worker_threads, rebuild ? 1 : 0, setup_ms,
                 stream_started ? 1 : 0);
            return true;
        } catch (const std::exception& e) {
            profile_.pregen_setup_ms += elapsed_ms(setup_start, SteadyClock::now());
            profile_.pregen_queue_depth = 0;
            profile_.pregen_worker_threads = 0;
            pregen_engines_.clear();
            LOGI("[PREGEN-SETUP-FAIL] fallback=sequential reason=%s", e.what());
            return false;
        }
    };

    auto accumulate_worker_profile = [&](const PerformanceProfile& worker) {
        profile_.duration_predictor_ms += worker.duration_predictor_ms;
        profile_.text_encoder_ms += worker.text_encoder_ms;
        profile_.vocoder_ms += worker.vocoder_ms;
        profile_.tensor_copy_ms += worker.tensor_copy_ms;
        profile_.token_process_ms += worker.token_process_ms;
        profile_.latent_setup_ms += worker.latent_setup_ms;
        profile_.latent_frames_used += worker.latent_frames_used;
        profile_.latent_frames_capacity += worker.latent_frames_capacity;
        if (profile_.ve_step_ms.size() < worker.ve_step_ms.size())
            profile_.ve_step_ms.resize(worker.ve_step_ms.size(), 0.0);
        for (size_t i = 0; i < worker.ve_step_ms.size(); ++i)
            profile_.ve_step_ms[i] += worker.ve_step_ms[i];
    };

    auto launch_one = [&](size_t index) {
        if (!pre_generation_ || pregen_engines_.empty() || index >= chunks.size() || pending.size() >= pregen_engines_.size()) return;
        const auto launch_start = SteadyClock::now();
        const size_t engine_index = (index - 1) % pregen_engines_.size();
        auto& engine = pregen_engines_[engine_index];
        const std::string chunk_text = chunks[index];
        const std::string chunk_lang = language;
        if (const auto it = duration_cache_.find(chunk_text); it != duration_cache_.end()) {
            engine->duration_cache_[chunk_text] = it->second;
        }
        pending.push_back(PregenTask{index, engine_index, std::async(std::launch::async, [engine = engine.get(), chunk_text, chunk_lang, index]() {
            engine->cancelled_.store(false);
            engine->profile_.reset();
            const int before = engine->profile_.truncated_chunks;
            auto pcm = engine->synth_chunk(chunk_text, chunk_lang, index, 0, 0);
            return PregenResult{std::move(pcm), engine->profile_.truncated_chunks > before, engine->profile_};
        })});
        profile_.pregen_launch_ms += elapsed_ms(launch_start, SteadyClock::now());
        LOGI("[PREGEN-LAUNCH] chunk=%zu worker=%zu worker_threads=%d pending=%zu",
             index, engine_index, pregen_worker_threads, pending.size());
    };

    // The foreground model gets the CPU to itself until playable PCM exists.
    // emit_stream() sends ~1.25 s of that PCM to Android, then invokes this hook.
    // Pregen therefore overlaps playback instead of foreground VE or a completed
    // first-chunk handoff.  With one full-thread worker, the expected wait at the
    // next semantic chunk should approach zero on CPUs that can synthesize faster
    // than the buffered playback headroom.
    pregen_kick = [&]() {
        if (!use_cpu_pregen || pregen_pool_ready) return;
        pregen_pool_ready = ensure_pregen_pool();
        if (pregen_pool_ready) pregen_pool_reusable = true;
        if (pregen_pool_ready && next_chunk_to_launch < chunks.size()) {
            launch_one(next_chunk_to_launch++);
        }
    };

    if (!chunks.empty()) {
        const int nt = chunks.size() > 1 ? bucket_hints[1].text_t : 0;
        const int nl = chunks.size() > 1 ? bucket_hints[1].latent_l : 0;
        synth_safe(chunks[0], 0, nt, nl);
    }
    // Extremely short/fully-trimmed first output may not have invoked the streaming
    // hook.  Fall back to starting look-ahead here rather than disabling pregen.
    if (use_cpu_pregen && !pregen_kicked) {
        pregen_kicked = true;
        pregen_kick();
    }

    for (size_t ci = 1; ci < chunks.size(); ++ci) {
        if (cancelled_.load()) return;

        if (!pregen_pool_ready || pending.empty()) {
            const int nt = (ci + 1 < chunks.size()) ? bucket_hints[ci + 1].text_t : 0;
            const int nl = (ci + 1 < chunks.size()) ? bucket_hints[ci + 1].latent_l : 0;
            synth_safe(chunks[ci], ci, nt, nl);
        } else {
            PregenTask task = std::move(pending.front());
            pending.pop_front();
            const auto wait_start = SteadyClock::now();
            PregenResult ready = task.future.get();
            const double wait_ms = elapsed_ms(wait_start, SteadyClock::now());
            profile_.pregen_wait_ms += wait_ms;
            accumulate_worker_profile(ready.worker_profile);
            LOGI("[PREGEN-WAIT] chunk=%zu worker=%zu wait_ms=%.3f ready=%d truncated=%d",
                 ci, task.engine_index, wait_ms, ready.pcm.empty() ? 0 : 1, ready.truncated ? 1 : 0);
            if (next_chunk_to_launch < chunks.size()) launch_one(next_chunk_to_launch++);
            if (!ready.truncated && !ready.pcm.empty()) {
                ++profile_.pregen_used_chunks;
                append_chunk(ready.pcm);
                emit_stream(ready.pcm, false);
            } else {
                ++profile_.pregen_discarded_chunks;
                const int nt = (ci + 1 < chunks.size()) ? bucket_hints[ci + 1].text_t : 0;
            const int nl = (ci + 1 < chunks.size()) ? bucket_hints[ci + 1].latent_l : 0;
            synth_safe(chunks[ci], ci, nt, nl);
            }
        }
    }
    // Signal that the last playable PCM has already been emitted before cleaning up
    // speculative pre-generation work. Android TTS can therefore advance its own
    // utterance/chapter state while native pre-generation workers perform their
    // cooperative cancellation and teardown in the background of this call.
    // The JNI layer records this final marker and sends callback.done() immediately.
    if (on_chunk) emit_stream({}, true);

    // Never leave speculative work running against an engine that the next utterance
    // may reuse. Cancel first, then join only the already-started workers. Because the
    // final marker above is sent before this wait, UI/navigation code is no longer
    // forced to wait for speculative cleanup before it can start the next chapter.
    const auto pregen_cleanup_start = SteadyClock::now();
    for (auto& task : pending) {
        if (task.engine_index < pregen_engines_.size() && pregen_engines_[task.engine_index]) {
            pregen_engines_[task.engine_index]->cancel();
        }
    }
    for (auto& task : pending) task.future.wait();
    pending.clear();
    profile_.pregen_cleanup_ms += elapsed_ms(pregen_cleanup_start, SteadyClock::now());

    const auto final_postprocess_start = SteadyClock::now();
    if (!full_pcm.empty()) {
        if (!Graph::all_finite(full_pcm.data(), full_pcm.size())) {
            throw std::runtime_error("Supertonic: non-finite PCM detected before final postprocess; audio was not emitted");
        }
        double sum_sq = 0.0;
        float peak = 0.0f;
        for (float x : full_pcm) {
            peak = std::max(peak, std::fabs(x));
            sum_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        // One final conservative global normalization keeps the overall loudness stable.
        const double rms = std::sqrt(sum_sq / static_cast<double>(full_pcm.size()));
        if (rms > 1.0e-5) {
            float gain = static_cast<float>(0.060 / rms);
            gain = std::max(0.90f, std::min(1.10f, gain));
            if (peak > 1.0e-5f) gain = std::min(gain, 0.94f / peak);
            for (float& x : full_pcm) x = std::max(-1.0f, std::min(1.0f, x * gain));
        }

        profile_.peak = 0.0;
        double post_sq = 0.0;
        for (float x : full_pcm) {
            profile_.peak = std::max(profile_.peak, static_cast<double>(std::fabs(x)));
            post_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        profile_.rms = std::sqrt(post_sq / static_cast<double>(full_pcm.size()));
        // Apply the user-selected maximum final trailing-silence trim after all chunk
        // joins and normalization. This controls the actual end-of-output tail.
        if (trailing_silence_trim_ms_ > 0 && full_pcm.size() >= 4096) {
            const float peak_now = static_cast<float>(profile_.peak);
            const float threshold = std::max(5.0e-4f, peak_now * 0.003f);
            const size_t min_run = static_cast<size_t>(0.035 * kSampleRateConst);
            const size_t max_trim = static_cast<size_t>(trailing_silence_trim_ms_) * kSampleRateConst / 1000;
            size_t low = 0;
            for (size_t i = full_pcm.size(); i-- > 0 && (full_pcm.size() - i) <= max_trim + min_run;) {
                if (std::fabs(full_pcm[i]) < threshold) ++low;
                else break;
            }
            if (low >= min_run) {
                const size_t remove = std::min(low, max_trim);
                if (remove > 0 && remove < full_pcm.size() - 2048) full_pcm.resize(full_pcm.size() - remove);
            }
        }
        // Recompute RMS after the optional final tail trim so the profile matches
        // the exact PCM that will be returned to the caller.
        double final_sq = 0.0;
        profile_.peak = 0.0;
        for (float x : full_pcm) {
            profile_.peak = std::max(profile_.peak, static_cast<double>(std::fabs(x)));
            final_sq += static_cast<double>(x) * static_cast<double>(x);
        }
        profile_.rms = std::sqrt(final_sq / static_cast<double>(full_pcm.size()));
        const auto final_active = active_range(full_pcm, 0, full_pcm.size());
        profile_.leading_silence_ms = (static_cast<double>(final_active.first) / kSampleRate) * 1000.0;
        profile_.trailing_silence_ms = (static_cast<double>(full_pcm.size() - final_active.second) / kSampleRate) * 1000.0;
    }

    profile_.final_postprocess_ms += elapsed_ms(final_postprocess_start, SteadyClock::now());

    if (!full_pcm.empty()) {
        // Never destructively trim the final waveform. Low-energy leading/trailing
        // phonemes are legitimate speech, especially in Korean.
        last_pcm_ = full_pcm;
    }

    // Keep the backend report chosen during construction. Java GPU/NNAPI modes
    // report their selected stages; the native Qualcomm path updates this report
    // only after every first-chunk VE validation step has passed.
    profile_.total_ms = elapsed_ms(total_start, SteadyClock::now());
    if (deep_profiler_ && !deep_profiler_dir_.empty()) {
        const auto stamp = std::to_string(
            std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count());
        auto dump_state = [&](const InterpreterState* state, const char* prefix) {
            if (!state) return;
            const std::filesystem::path dir(deep_profiler_dir_);
            const std::string base = std::string("litert_") + stamp + "_" + prefix + "_";
            state->duration.write_deep_profile_csv((dir / (base + "duration.csv")).string());
            state->encoder.write_deep_profile_csv((dir / (base + "encoder.csv")).string());
            state->vector.write_deep_profile_csv((dir / (base + "vector_estimator.csv")).string());
            state->vocoder.write_deep_profile_csv((dir / (base + "vocoder.csv")).string());
        };
        dump_state(interp_.get(), "primary");
        dump_state(npu_cpu_fallback_.get(), "cpu_fallback");
    }
    // One end-of-utterance marker makes native LiteRT timing observable in both
    // the standalone Generate UI and Android's TTS service. Logging occurs only
    // after profile_.total_ms is finalized, so it is outside the measured total.
    LOGI("[LITERT-SYNTH-END] %s", performance_profile().c_str());
}

std::string LiteRTSupertonicTts::performance_profile() const {
    std::ostringstream oss;
    oss.setf(std::ios::fixed);
    oss.precision(3);
    oss << "dp=" << profile_.duration_predictor_ms
        << ";encoder=" << profile_.text_encoder_ms
        << ";vocoder=" << profile_.vocoder_ms
        << ";tensor_copy=" << profile_.tensor_copy_ms
        << ";chunking=" << profile_.chunking_ms
        << ";token_process=" << profile_.token_process_ms
        << ";latent_setup=" << profile_.latent_setup_ms
        << ";append=" << profile_.append_ms
        << ";stream_emit=" << profile_.stream_emit_ms
        << ";pregen_setup=" << profile_.pregen_setup_ms
        << ";pregen_launch=" << profile_.pregen_launch_ms
        << ";pregen_wait=" << profile_.pregen_wait_ms
        << ";pregen_cleanup=" << profile_.pregen_cleanup_ms
        << ";packing_probe=" << profile_.packing_probe_ms
        << ";preset_hint=" << profile_.preset_hint_ms
        << ";preset_overlap_prepare=" << profile_.preset_overlap_prepare_ms
        << ";preset_overlap_wait=" << profile_.preset_overlap_wait_ms
        << ";preset_overlap_hidden=" << profile_.preset_overlap_hidden_ms
        << ";preset_overlap_launches=" << profile_.preset_overlap_launches
        << ";preset_overlap_failures=" << profile_.preset_overlap_failures
        << ";final_postprocess=" << profile_.final_postprocess_ms
        << ";total=" << profile_.total_ms
        << ";voice=" << voice_id_
        << ";model_speed=1.0"
        << ";steps=" << total_step_
        << ";threads=" << num_threads_
        << ";threads_duration=" << duration_threads_
        << ";threads_encoder=" << encoder_threads_
        << ";threads_vector=" << vector_threads_
        << ";threads_vocoder=" << vocoder_threads_
        << ";chunk_silence_ms=" << (chunk_silence_s_ * 1000.0f)
        << ";chunks=" << profile_.chunk_count
        << ";truncated_chunks=" << profile_.truncated_chunks
        << ";peak=" << profile_.peak
        << ";rms=" << profile_.rms
        << ";lead_silence_ms=" << profile_.leading_silence_ms
        << ";trail_silence_ms=" << profile_.trailing_silence_ms
        << ";ttfa_ms=" << profile_.ttfa_ms
        << ";streamed_chunks=" << profile_.streamed_chunks
        << ";max_chunk_gap_ms=" << profile_.max_chunk_gap_ms
        << ";avg_chunk_gap_ms=" << profile_.avg_chunk_gap_ms
        << ";chunk_gap_min_ms=" << chunk_gap_min_ms_
        << ";chunk_gap_max_ms=" << chunk_gap_max_ms_
        << ";chunk_gap_over_max_count=" << profile_.chunk_gap_over_max_count
        << ";pregen=" << (pre_generation_ ? "on" : "off")
        << ";pregen_queue_depth=" << profile_.pregen_queue_depth
        << ";pregen_worker_threads=" << profile_.pregen_worker_threads
        << ";pregen_used_chunks=" << profile_.pregen_used_chunks
        << ";pregen_discarded_chunks=" << profile_.pregen_discarded_chunks
        << ";packing_original_chunks=" << profile_.packing_original_chunks
        << ";packing_final_chunks=" << profile_.packing_final_chunks
        << ";packing_probe_count=" << profile_.packing_probe_count
        << ";packing_splits=" << profile_.packing_splits
        << ";latent_frames_used=" << profile_.latent_frames_used
        << ";latent_frames_capacity=" << profile_.latent_frames_capacity
        << ";latent_fill_pct="
        << (profile_.latent_frames_capacity > 0
                ? (100.0 * static_cast<double>(profile_.latent_frames_used) /
                   static_cast<double>(profile_.latent_frames_capacity))
                : 0.0)
        << ";duration_cache_entries=" << duration_cache_.size()
        << ";xnnpack_weight_cache=" << (is_cpu_backend(backend_) ? "file-backed" : "n/a")
        << ";cpu_affinity=" << (is_cpu_backend(backend_) ? "topology-aware-if-supported" : "n/a")
        << ";trailing_trim_setting_ms=" << trailing_silence_trim_ms_
        << ";chunk_cap=" << chunk_cap_
        << ";backend=" << backend_report_
        << ";deep_profiler=" << (deep_profiler_ ? 1 : 0)
        << ";deep_profiler_granularity=" << (deep_profiler_ ? "graph-invoke" : "off")
        << ";ve_steps=";
    for (size_t i = 0; i < profile_.ve_step_ms.size(); ++i) {
        if (i) oss << ',';
        oss << profile_.ve_step_ms[i];
    }
    return oss.str();
}

std::vector<float> LiteRTSupertonicTts::synth_chunk(const std::string& chunk,
                                                     const std::string& language,
                                                     size_t chunk_index,
                                                     int next_text_t,
                                                     int next_latent_l) {
    const bool full_external_runner =
        external_runner_ && external_runner_->supports_duration() &&
        external_runner_->supports_encoder() && external_runner_->supports_vector() &&
        external_runner_->supports_vocoder();
    const bool normal_native_npu =
        backend_ == Backend::Npu && !external_runner_;
    const bool native_cpu_graphs = is_cpu_backend(backend_) && !full_external_runner;

    if (normal_native_npu) {
        if (!interp_ || !npu_cpu_fallback_)
            throw std::runtime_error(
                "Supertonic: Qualcomm NPU VE state is not initialized");
    } else {
        if (native_cpu_graphs && !interp_)
            throw std::runtime_error("Supertonic: CPU interpreter state is not initialized");
        if (!native_cpu_graphs && !external_runner_)
            throw std::runtime_error("Supertonic: external runner is not initialized");
    }
    const VoiceStyle& voice = current_voice();
    const auto token_start = SteadyClock::now();
    const auto tok = tokenizer_->process(chunk, language, kTextT);
    const int t_real = token_count_from_mask(tok.mask);
    const int text_t = npu_multipreset_t64_l64_probe_
        ? (t_real <= 64 ? 64 : 0)
        : (autobucket_enabled_ ? select_bucket(t_real) : kTextT);
    if (text_t <= 0) {
        throw std::runtime_error(
            npu_multipreset_t64_l64_probe_
                ? "LiteRT Qualcomm NPU preview token count exceeds T64"
                : "Supertonic AutoBucket: token count exceeds T128");
    }
    profile_.token_process_ms += elapsed_ms(token_start, SteadyClock::now());

    ids64_scratch_.clear();
    ids64_scratch_.reserve(static_cast<size_t>(text_t));
    for (int i = 0; i < text_t; ++i) {
        ids64_scratch_.push_back(tok.ids[static_cast<size_t>(i)]);
    }
    const auto& ids64 = ids64_scratch_;
    float duration = 0.0f;
    bool duration_cache_hit = false;
    const auto cached_duration = duration_cache_.find(chunk);
    if (cached_duration != duration_cache_.end()) {
        duration = cached_duration->second;
        duration_cache_hit = true;
    } else {
        const auto t0 = SteadyClock::now();
        if (autobucket_enabled_) ensure_duration_bucket(text_t);
        if (normal_native_npu) {
            auto& g = npu_cpu_fallback_->duration;
            g.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
            g.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
            g.write_input_named("style_dp", {1, 8, 16}, voice.style_dp.data(), voice.style_dp.size() * sizeof(float));
            g.run("duration CPU fallback Run");
            g.read_output({1}, &duration, sizeof(float));
        } else if (native_cpu_graphs || !external_runner_->supports_duration()) {
            interp_->duration.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
            interp_->duration.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
            interp_->duration.write_input_named("style_dp", {1, 8, 16}, voice.style_dp.data(), voice.style_dp.size() * sizeof(float));
            interp_->duration.run("duration Run");
            interp_->duration.read_output({1}, &duration, sizeof(float));
        } else {
            external_runner_->run_duration(ids64.data(), ids64.size(),
                                           voice.style_dp.data(), voice.style_dp.size(),
                                           tok.mask.data(), static_cast<size_t>(text_t), &duration);
        }
        profile_.duration_predictor_ms += elapsed_ms(t0, SteadyClock::now());
        duration_cache_[chunk] = duration;
    }
    if (!(duration > 0.0f) || !std::isfinite(duration)) {
        throw std::runtime_error("Supertonic: duration predictor returned invalid/non-finite output");
    }

    const int chunk_size = kChunkSamples;
    const long long wav_len = static_cast<long long>(duration * kSampleRateConst);
    const int l_true = std::max(1, static_cast<int>((wav_len + chunk_size - 1) / chunk_size));
    const int L = npu_multipreset_t64_l64_probe_
        ? (l_true <= 64 ? 64 : 0)
        : (autobucket_enabled_ ? select_bucket(l_true) : graph_latent_frames());
    if (L <= 0) {
        throw std::runtime_error(
            npu_multipreset_t64_l64_probe_
                ? "LiteRT Qualcomm NPU preview predicted latent length " +
                    std::to_string(l_true) + " exceeds L64; chunk must be split"
                : "Supertonic AutoBucket: predicted latent length " +
                    std::to_string(l_true) + " exceeds L128; chunk must be split");
    }
    if (autobucket_enabled_) {
        ensure_inference_bucket(text_t, L);
        LOGI("[AUTO-BUCKET][SELECT] chunk=%zu Treal=%d T=%d Lreal=%d L=%d duration=%.6f active_ve=1",
             chunk_index, t_real, text_t, l_true, L, static_cast<double>(duration));
    }

    const double max_graph_duration_s = static_cast<double>(L) * kChunkSamples / kSampleRateConst;
    LOGI("[DURATION-PROBE] strict=%d backend=%d text_bytes=%zu duration=%.9g max_window=%.9g overflow=%d cache_hit=%d",
         0,
         static_cast<int>(backend_),
         chunk.size(),
         static_cast<double>(duration),
         max_graph_duration_s,
         duration > max_graph_duration_s * 1.001 ? 1 : 0,
         duration_cache_hit ? 1 : 0);
    if (duration > max_graph_duration_s * 1.001) ++profile_.truncated_chunks;

    text_emb_scratch_.resize(static_cast<size_t>(256) * static_cast<size_t>(text_t));
    auto& text_emb = text_emb_scratch_;
    {
        const auto t0 = SteadyClock::now();
        if (normal_native_npu) {
            auto& g = npu_cpu_fallback_->encoder;
            g.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
            g.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
            g.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
            g.run("text_encoder CPU fixed Run");
            g.read_output({1, 256, text_t}, text_emb.data(), text_emb.size() * sizeof(float));
        } else if (native_cpu_graphs || !external_runner_->supports_encoder()) {
            interp_->encoder.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
            interp_->encoder.write_input_named("text_ids", {1, text_t}, ids64.data(), ids64.size() * sizeof(int64_t));
            interp_->encoder.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
            interp_->encoder.run("text_encoder Run");
            interp_->encoder.read_output({1, 256, text_t}, text_emb.data(), text_emb.size() * sizeof(float));
        } else {
            external_runner_->run_encoder(ids64.data(), ids64.size(),
                                          voice.style_ttl.data(), voice.style_ttl.size(),
                                          tok.mask.data(), static_cast<size_t>(text_t),
                                          text_emb.data(), text_emb.size());
        }
        if (!Graph::all_finite(text_emb.data(), text_emb.size()))
            throw std::runtime_error("Supertonic: text encoder returned non-finite output");
        profile_.text_encoder_ms += elapsed_ms(t0, SteadyClock::now());
    }

    const int L_fill = std::min(l_true, L);
    profile_.latent_frames_used += L_fill;
    profile_.latent_frames_capacity += L;
    latent_mask_scratch_.assign(static_cast<size_t>(L), 0.0f);
    auto& latent_mask = latent_mask_scratch_;
    for (int t = 0; t < L_fill; ++t) latent_mask[t] = 1.0f;

    const auto latent_start = SteadyClock::now();
    std::mt19937 rng(seed_used_ + 0x9E3779B9u * static_cast<uint32_t>(chunk_index + 1));
    std::normal_distribution<float> nd(0.0f, 1.0f);
    latent_scratch_.resize(static_cast<size_t>(kLatentChannels) * L);
    auto& xt = latent_scratch_;
    for (int c = 0; c < kLatentChannels; ++c)
        for (int t = 0; t < L; ++t) xt[static_cast<size_t>(c) * L + t] = nd(rng) * latent_mask[t];
    profile_.latent_setup_ms += elapsed_ms(latent_start, SteadyClock::now());

    const float total_step_f = static_cast<float>(total_step_);
    if (profile_.ve_step_ms.size() != static_cast<size_t>(total_step_))
        profile_.ve_step_ms.assign(static_cast<size_t>(total_step_), 0.0);

    const bool vector_on_cpu =
        !normal_native_npu &&
        (native_cpu_graphs || !external_runner_->supports_vector());

    if (normal_native_npu) {
        auto& cpu_vec = npu_cpu_fallback_->vector;
        cpu_vec.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
        cpu_vec.write_input_named("latent_mask", {1, 1, L}, latent_mask.data(), latent_mask.size() * sizeof(float));
        cpu_vec.write_input_named("total_step", {1}, &total_step_f, sizeof(float), 1);
        cpu_vec.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
        cpu_vec.write_input_named("text_emb", {1, 256, text_t}, text_emb.data(), text_emb.size() * sizeof(float));

        if (npu_vector_enabled_) {
            interp_->vector.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
            interp_->vector.write_input_named("latent_mask", {1, 1, L}, latent_mask.data(), latent_mask.size() * sizeof(float));
            interp_->vector.write_input_named("total_step", {1}, &total_step_f, sizeof(float), 1);
            interp_->vector.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
            interp_->vector.write_input_named("text_emb", {1, 256, text_t}, text_emb.data(), text_emb.size() * sizeof(float));
        }
    } else if (vector_on_cpu) {
        interp_->vector.write_input_named("style_ttl", {1, 50, 256}, voice.style_ttl.data(), voice.style_ttl.size() * sizeof(float));
        interp_->vector.write_input_named("latent_mask", {1, 1, L}, latent_mask.data(), latent_mask.size() * sizeof(float));
        interp_->vector.write_input_named("total_step", {1}, &total_step_f, sizeof(float), 1);
        interp_->vector.write_input_named("text_mask", {1, 1, text_t}, tok.mask.data(), static_cast<size_t>(text_t) * sizeof(float));
        interp_->vector.write_input_named("text_emb", {1, 256, text_t}, text_emb.data(), text_emb.size() * sizeof(float));
    }

    for (int step = 0; step < total_step_; ++step) {
        if (cancelled_.load()) return {};
        const auto t0 = SteadyClock::now();
        const float cur_step_f = static_cast<float>(step);
        if (normal_native_npu) {
            auto run_cpu_vector = [&](float* dst, const float* src) {
                auto& g = npu_cpu_fallback_->vector;
                std::memcpy(dst, src, xt.size() * sizeof(float));
                g.write_input_named("current_step", {1}, &cur_step_f, sizeof(float), 0);
                g.write_input_named("noisy_latent", {1, kLatentChannels, L}, dst, xt.size() * sizeof(float));
                g.run("vector_estimator CPU fallback Run");
                g.read_output({1, kLatentChannels, L}, dst, xt.size() * sizeof(float));
            };

            if (npu_vector_enabled_) {
                // Qualcomm NPU validation. The first VE step runs
                // a mandatory CPU-vs-NPU diagnostic even when the NPU output is
                // non-finite, so we can distinguish bad HTP math from bad I/O.
                npu_input_scratch_ = xt;
                try {
                    const bool detailed_diag = !npu_vector_validated_ && step == 0;
                    if (detailed_diag) {
                        LOGI("[NPU-DIAG][BEGIN] VE step=1 same-input CPU-vs-NPU diagnostic");
                        interp_->vector.log_accel_io_metadata();
                        Graph::log_float_stats("source.style_ttl", voice.style_ttl.data(), voice.style_ttl.size());
                        Graph::log_float_stats("source.latent_mask", latent_mask.data(), latent_mask.size());
                        Graph::log_float_stats("source.total_step", &total_step_f, 1);
                        Graph::log_float_stats("source.text_mask", tok.mask.data(), tok.mask.size());
                        Graph::log_float_stats("source.text_emb", text_emb.data(), text_emb.size());
                        Graph::log_float_stats("source.current_step", &cur_step_f, 1);
                        Graph::log_float_stats("source.noisy_latent", npu_input_scratch_.data(), npu_input_scratch_.size());
                    }

                    interp_->vector.write_input_named(
                        "current_step", {1}, &cur_step_f, sizeof(float), 0);
                    interp_->vector.write_input_named(
                        "noisy_latent", {1, kLatentChannels, L},
                        npu_input_scratch_.data(), npu_input_scratch_.size() * sizeof(float));

                    if (detailed_diag) {
                        // Read back the actual managed buffers after any FP32->FP16
                        // conversion. These are the values handed to QNN, not just
                        // the original C++ vectors.
                        interp_->vector.log_accel_input_buffer_stats("style_ttl", {1, 50, 256});
                        interp_->vector.log_accel_input_buffer_stats("latent_mask", {1, 1, L});
                        interp_->vector.log_accel_input_buffer_stats("total_step", {1}, 1);
                        interp_->vector.log_accel_input_buffer_stats("text_mask", {1, 1, text_t});
                        interp_->vector.log_accel_input_buffer_stats("text_emb", {1, 256, text_t});
                        interp_->vector.log_accel_input_buffer_stats("current_step", {1}, 0);
                        interp_->vector.log_accel_input_buffer_stats("noisy_latent", {1, kLatentChannels, L});
                    }

                    interp_->vector.run("vector_estimator Qualcomm QNN HTP Run");

                    if (!npu_vector_validated_) {
                        // Do NOT let read_output() reject NaN/Inf before the CPU
                        // reference has run. Capture the NPU bytes/results first.
                        interp_->vector.read_output_unchecked(
                            {1, kLatentChannels, L},
                            xt.data(), xt.size() * sizeof(float));

                        if (detailed_diag) {
                            interp_->vector.log_output_raw_prefix({1, kLatentChannels, L});
                            Graph::log_float_stats("npu.output", xt.data(), xt.size());
                        }

                        npu_reference_scratch_ = npu_input_scratch_;
                        run_cpu_vector(
                            npu_reference_scratch_.data(),
                            npu_input_scratch_.data());

                        if (detailed_diag) {
                            Graph::log_float_stats(
                                "cpu.output",
                                npu_reference_scratch_.data(),
                                npu_reference_scratch_.size());
                            LOGI("[NPU-DIAG][COMPARE] step=1 npu_finite=%d cpu_finite=%d",
                                 Graph::all_finite(xt.data(), xt.size()) ? 1 : 0,
                                 Graph::all_finite(npu_reference_scratch_.data(), npu_reference_scratch_.size()) ? 1 : 0);
                        }

                        if (!Graph::all_finite(npu_reference_scratch_.data(), npu_reference_scratch_.size())) {
                            throw std::runtime_error(
                                "CPU reference also returned NaN/Inf at VE step " +
                                std::to_string(step + 1) +
                                "; input/model path is suspect, see [NPU-DIAG]");
                        }
                        if (!Graph::all_finite(xt.data(), xt.size())) {
                            throw std::runtime_error(
                                "QNN HTP returned NaN/Inf at VE step " +
                                std::to_string(step + 1) +
                                " while same-input CPU reference is finite; see [NPU-DIAG]");
                        }

                        constexpr double kMaxRelativeRmse = 0.08;
                        const double rel_rmse = Graph::relative_rmse(
                            xt.data(),
                            npu_reference_scratch_.data(),
                            xt.size());
                        if (detailed_diag) {
                            LOGI("[NPU-DIAG][COMPARE] step=1 rel_rmse=%.9g threshold=%.9g",
                                 rel_rmse, kMaxRelativeRmse);
                        }
                        if (!std::isfinite(rel_rmse) ||
                            rel_rmse > kMaxRelativeRmse) {
                            std::ostringstream reason;
                            reason.setf(std::ios::fixed);
                            reason << std::setprecision(6)
                                   << "QNN HTP/CPU relative RMSE "
                                   << rel_rmse << " exceeds "
                                   << kMaxRelativeRmse << " at VE step "
                                   << (step + 1);
                            throw std::runtime_error(reason.str());
                        }

                        ++npu_vector_validation_passes_;
                        if (npu_vector_validation_passes_ >= total_step_) {
                            npu_vector_validated_ = true;
                        }
                        refresh_native_npu_report();
                        LOGI(
                            "[LITERT-QNN-VALIDATION] step=%d/%d rel_rmse=%.9g accepted=%d",
                            step + 1, total_step_, rel_rmse,
                            npu_vector_validated_ ? 1 : 0);
                        if (detailed_diag) {
                            LOGI("[NPU-DIAG][END] VE step=1 diagnostic complete");
                        }
                    } else {
                        interp_->vector.read_output(
                            {1, kLatentChannels, L},
                            xt.data(), xt.size() * sizeof(float));
                    }
                } catch (const std::exception& e) {
                    npu_vector_enabled_ = false;
                    npu_vector_validated_ = false;
                    npu_rejection_reason_ = e.what();
                    refresh_native_npu_report();
                    LOGE(
                        "[LITERT-QNN-VALIDATION] rejected at step=%d: %s",
                        step + 1, npu_rejection_reason_.c_str());

                    // No first-chunk audio has been emitted yet. Propagate the
                    // failure so the Android wrapper recreates the full request
                    // on CPU and reports the active backend honestly.
                    throw std::runtime_error(
                        "Supertonic Qualcomm NPU validation failed: " +
                        npu_rejection_reason_);
                }
            } else {
                const std::vector<float> before = xt;
                run_cpu_vector(xt.data(), before.data());
            }
        } else if (vector_on_cpu) {
            interp_->vector.write_input_named("current_step", {1}, &cur_step_f, sizeof(float), 0);
            interp_->vector.write_input_named("noisy_latent", {1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
            interp_->vector.run("vector_estimator Run");
            interp_->vector.read_output({1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
        } else {
            external_runner_->run_vector(xt.data(), xt.size(),
                                         text_emb.data(), text_emb.size(),
                                         voice.style_ttl.data(), voice.style_ttl.size(),
                                         latent_mask.data(), latent_mask.size(),
                                         tok.mask.data(), tok.mask.size(),
                                         cur_step_f, total_step_f);
        }
        // FIX9.5 behavior: avoid a full latent memory scan on every CPU flow
        // step. Keep every-step validation only while native NPU is still being
        // numerically proven; CPU/external paths validate first and final steps.
        const bool validate_this_step =
            (normal_native_npu && !npu_vector_validated_) ||
            step == 0 || step + 1 == total_step_;
        if (validate_this_step && !Graph::all_finite(xt.data(), xt.size()))
            throw std::runtime_error("Supertonic: vector estimator returned non-finite output at step " + std::to_string(step + 1));
        profile_.ve_step_ms[static_cast<size_t>(step)] += elapsed_ms(t0, SteadyClock::now());
    }

    struct PresetOverlapResult {
        bool ok = true;
        double prepare_ms = 0.0;
        std::string error;
    };
    std::future<PresetOverlapResult> preset_future;
    const bool can_overlap_next_preset =
        autobucket_enabled_ && native_cpu_graphs &&
        next_text_t > 0 && next_latent_l > 0 &&
        (active_encoder_t_ != next_text_t ||
         active_vector_t_ != next_text_t || active_vector_l_ != next_latent_l);
    if (can_overlap_next_preset) {
        ++profile_.preset_overlap_launches;
        LOGI("[AUTO-BUCKET][OVERLAP-LAUNCH] current=T%d/L%d next=T%d/L%d phase=vocoder prep=encoder+vector switch_mode=safe-signature-reuse",
             text_t, L, next_text_t, next_latent_l);
        preset_future = std::async(std::launch::async, [this, next_text_t, next_latent_l]() {
            PresetOverlapResult result;
            const auto t0 = SteadyClock::now();
            try {
                ensure_encoder_vector_bucket(next_text_t, next_latent_l);
            } catch (const std::exception& e) {
                result.ok = false;
                result.error = e.what();
            }
            result.prepare_ms = elapsed_ms(t0, SteadyClock::now());
            return result;
        });
    }

    std::vector<float> wav(static_cast<size_t>(chunk_size) * L);
    double current_vocoder_ms = 0.0;
    {
        const auto t0 = SteadyClock::now();
        if (normal_native_npu) {
            auto& g = npu_cpu_fallback_->vocoder;
            g.write_input_named("latent", {1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
            g.run("vocoder CPU fixed Run");
            g.read_output({1, chunk_size * L}, wav.data(), wav.size() * sizeof(float));
        } else if (native_cpu_graphs || !external_runner_->supports_vocoder()) {
            interp_->vocoder.write_input_named("latent", {1, kLatentChannels, L}, xt.data(), xt.size() * sizeof(float));
            interp_->vocoder.run("vocoder Run");
            interp_->vocoder.read_output({1, chunk_size * L}, wav.data(), wav.size() * sizeof(float));
        } else {
            external_runner_->run_vocoder(xt.data(), xt.size(), wav.data(), wav.size());
        }
        if (!Graph::all_finite(wav.data(), wav.size()))
            throw std::runtime_error("Supertonic: vocoder returned non-finite output");
        current_vocoder_ms = elapsed_ms(t0, SteadyClock::now());
        profile_.vocoder_ms += current_vocoder_ms;
    }

    if (preset_future.valid()) {
        const auto wait_t0 = SteadyClock::now();
        PresetOverlapResult ready = preset_future.get();
        const double wait_ms = elapsed_ms(wait_t0, SteadyClock::now());
        const double hidden_ms = std::max(0.0, ready.prepare_ms - wait_ms);
        profile_.preset_overlap_prepare_ms += ready.prepare_ms;
        profile_.preset_overlap_wait_ms += wait_ms;
        profile_.preset_overlap_hidden_ms += hidden_ms;
        if (!ready.ok) ++profile_.preset_overlap_failures;
        LOGI("[AUTO-BUCKET][OVERLAP-DONE] next=T%d/L%d ok=%d prepare_ms=%.3f vocoder_ms=%.3f post_vocoder_wait_ms=%.3f hidden_ms=%.3f error=%s",
             next_text_t, next_latent_l, ready.ok ? 1 : 0, ready.prepare_ms,
             current_vocoder_ms, wait_ms, hidden_ms,
             ready.error.empty() ? "none" : ready.error.c_str());
    }

    size_t n = static_cast<size_t>(std::floor(kSampleRateConst * duration));
    n = std::min(n, static_cast<size_t>(chunk_size) * static_cast<size_t>(L_fill));
    n = std::min(n, wav.size());

    // Some vendor delegates return finite tensors that are nevertheless
    // numerically wrong. The observed Helio G99 NNAPI result passed every
    // all-finite check but produced only wind-like near-silence (peak 0.029,
    // RMS 0.002). Reject only accelerator output and use deliberately
    // conservative two-metric bounds so valid quiet CPU speech is untouched.
    if (!is_cpu_backend(backend_) && n >= 4096) {
        double peak = 0.0;
        double sum_sq = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double sample = static_cast<double>(wav[i]);
            peak = std::max(peak, std::fabs(sample));
            sum_sq += sample * sample;
        }
        const double rms = std::sqrt(sum_sq / static_cast<double>(n));
        const bool implausibly_quiet = peak < 0.040 && rms < 0.0040;
        const bool implausibly_large = peak > 4.0 || rms > 1.0;
        if (implausibly_quiet || implausibly_large) {
            std::ostringstream reason;
            reason.setf(std::ios::fixed);
            reason << std::setprecision(6)
                   << "Supertonic: accelerator audio quality guard rejected output"
                   << " (peak=" << peak << ", rms=" << rms << ')';
            throw std::runtime_error(reason.str());
        }
    }
    wav.resize(n);
    return wav;
}



} // namespace speech_core
