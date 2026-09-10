#pragma once

#include "speech_core/interfaces.h"
#include "speech_core/models/supertonic_tokenizer.h"
#include <atomic>
#include <array>
#include <chrono>
#include <cstdint>
#include <future>
#include <thread>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>


namespace speech_core {

class SupertonicExternalRunner {
public:
    virtual ~SupertonicExternalRunner() = default;
    virtual bool supports_duration() const = 0;
    virtual bool supports_encoder() const = 0;
    virtual bool supports_vector() const = 0;
    virtual bool supports_vocoder() const = 0;
    virtual std::string backend_report() const = 0;
    virtual std::shared_ptr<SupertonicExternalRunner> clone_for_pregeneration() {
        return {};
    }
    virtual void run_duration(const int64_t* text_ids, size_t text_ids_count,
                              const float* style_dp, size_t style_dp_count,
                              const float* text_mask, size_t text_mask_count,
                              float* output) = 0;
    virtual void run_encoder(const int64_t* text_ids, size_t text_ids_count,
                             const float* style_ttl, size_t style_ttl_count,
                             const float* text_mask, size_t text_mask_count,
                             float* output, size_t output_count) = 0;
    virtual void run_vector(float* noisy_latent, size_t noisy_latent_count,
                            const float* text_emb, size_t text_emb_count,
                            const float* style_ttl, size_t style_ttl_count,
                            const float* latent_mask, size_t latent_mask_count,
                            const float* text_mask, size_t text_mask_count,
                            float current_step, float total_step) = 0;
    virtual void run_vocoder(const float* latent, size_t latent_count,
                             float* output, size_t output_count) = 0;
};

/// Supertonic-3 — 99M non-autoregressive flow-matching multilingual TTS via LiteRT.
/// Android build uses the four Soniqo LiteRT graphs: duration predictor, text encoder,
/// vector estimator, and vocoder. Output is 44.1 kHz and G2P-free.
class LiteRTSupertonicTts : public TTSInterface {
public:
    enum class Backend { Cpu = 0, Gpu = 1, Npu = 2, CpuFp16 = 3 };
    LiteRTSupertonicTts(const std::string& duration_path,
                        const std::string& text_encoder_path,
                        const std::string& vector_estimator_path,
                        const std::string& vocoder_path,
                        const std::string& tokenizer_dir,
                        const std::string& voice_styles_dir,
                        bool hw_accel = false,
                        int num_threads = 4,
                        Backend backend = Backend::Cpu,
                        std::string native_library_dir = {},
                        std::string accelerator_cache_dir = {},
                        bool enable_deep_profiler = false,
                        std::shared_ptr<SupertonicExternalRunner> external_runner = {},
                        bool static_multipreset_bundle = false);
    ~LiteRTSupertonicTts() override;

    void synthesize(const std::string& text,
                    const std::string& language,
                    TTSChunkCallback on_chunk) override;
    int output_sample_rate() const override { return 44100; }
    void cancel() override;

    void set_voice(const std::string& voice_id);
    void set_total_step(int total_step);
    void set_num_threads(int num_threads) { num_threads_ = std::max(1, std::min(64, num_threads)); }
    void set_chunk_cap(int chunk_cap);
    void set_pre_generation(bool enabled);
    bool pre_generation() const { return pre_generation_; }
    void set_pre_generation_queue(int depth);
    int pre_generation_queue() const { return pre_generation_queue_; }
    void set_chunk_gap_ms(int min_ms, int max_ms);
    void set_trailing_silence_trim_ms(int trim_ms);
    int num_threads() const { return num_threads_; }
    Backend backend() const { return backend_; }
    const std::string& backend_report() const { return backend_report_; }
    void set_speed(float speed) { speed_ = std::max(0.25f, std::min(3.0f, speed)); }
    void set_seed(uint32_t seed) { seed_ = seed; }
    uint32_t seed_used() const { return seed_used_; }
    void set_chunk_silence(float seconds) { chunk_silence_s_ = seconds; }
    std::vector<std::string> voices() const;
    const std::vector<float>& last_pcm() const { return last_pcm_; }

    /// Timing breakdown for the last synthesis. Values are milliseconds.
    std::string performance_profile() const;

private:
    struct VoiceStyle {
        std::vector<float> style_ttl;
        std::vector<float> style_dp;
    };

    struct PerformanceProfile {
        double duration_predictor_ms = 0.0;
        double text_encoder_ms = 0.0;
        double vocoder_ms = 0.0;
        double tensor_copy_ms = 0.0;
        double chunking_ms = 0.0;
        double token_process_ms = 0.0;
        double latent_setup_ms = 0.0;
        double append_ms = 0.0;
        double stream_emit_ms = 0.0;
        double pregen_setup_ms = 0.0;
        double pregen_launch_ms = 0.0;
        double pregen_wait_ms = 0.0;
        double pregen_cleanup_ms = 0.0;
        double packing_probe_ms = 0.0;
        double preset_hint_ms = 0.0;
        double preset_overlap_prepare_ms = 0.0;
        double preset_overlap_wait_ms = 0.0;
        double preset_overlap_hidden_ms = 0.0;
        int preset_overlap_launches = 0;
        int preset_overlap_failures = 0;
        double final_postprocess_ms = 0.0;
        double total_ms = 0.0;
        int chunk_count = 0;
        int truncated_chunks = 0;
        double peak = 0.0;
        double rms = 0.0;
        double leading_silence_ms = 0.0;
        double trailing_silence_ms = 0.0;
        double ttfa_ms = 0.0;
        int streamed_chunks = 0;
        double max_chunk_gap_ms = 0.0;
        double avg_chunk_gap_ms = 0.0;
        int chunk_gap_count = 0;
        int pregen_used_chunks = 0;
        int pregen_discarded_chunks = 0;
        int pregen_queue_depth = 0;
        int pregen_worker_threads = 0;
        int packing_probe_count = 0;
        int packing_original_chunks = 0;
        int packing_final_chunks = 0;
        int packing_splits = 0;
        long long latent_frames_used = 0;
        long long latent_frames_capacity = 0;
        int chunk_gap_over_max_count = 0;
        std::vector<double> ve_step_ms;
        void reset() {
            duration_predictor_ms = 0.0;
            text_encoder_ms = 0.0;
            vocoder_ms = 0.0;
            tensor_copy_ms = 0.0;
            chunking_ms = 0.0;
            token_process_ms = 0.0;
            latent_setup_ms = 0.0;
            append_ms = 0.0;
            stream_emit_ms = 0.0;
            pregen_setup_ms = 0.0;
            pregen_launch_ms = 0.0;
            pregen_wait_ms = 0.0;
            pregen_cleanup_ms = 0.0;
            packing_probe_ms = 0.0;
            preset_hint_ms = 0.0;
            preset_overlap_prepare_ms = 0.0;
            preset_overlap_wait_ms = 0.0;
            preset_overlap_hidden_ms = 0.0;
            preset_overlap_launches = 0;
            preset_overlap_failures = 0;
            final_postprocess_ms = 0.0;
            total_ms = 0.0;
            chunk_count = 0;
            truncated_chunks = 0;
            peak = 0.0;
            rms = 0.0;
            leading_silence_ms = 0.0;
            trailing_silence_ms = 0.0;
            ttfa_ms = 0.0;
            streamed_chunks = 0;
            max_chunk_gap_ms = 0.0;
            avg_chunk_gap_ms = 0.0;
            chunk_gap_count = 0;
            pregen_used_chunks = 0;
            pregen_discarded_chunks = 0;
            pregen_queue_depth = 0;
            pregen_worker_threads = 0;
            packing_probe_count = 0;
            packing_original_chunks = 0;
            packing_final_chunks = 0;
            packing_splits = 0;
            latent_frames_used = 0;
            latent_frames_capacity = 0;
            chunk_gap_over_max_count = 0;
            ve_step_ms.clear();
        }
    };

    std::vector<float> synth_chunk(const std::string& chunk,
                                   const std::string& language,
                                   size_t chunk_index,
                                   int next_text_t = 0,
                                   int next_latent_l = 0);
    float predict_duration_only(const std::string& chunk,
                                const std::string& language,
                                bool packing_probe);
    std::vector<std::string> build_duration_aware_chunks(
        const std::string& text,
        const std::string& language,
        int raw_cap);
    const VoiceStyle& current_voice() const;
    void destroy_graphs() noexcept;

    // REV34 FIX9.5 AutoBucket A/B: fixed-shape Soniqo-derived presets.
    // The user-facing selector is intentionally absent: every chunk chooses
    // the smallest data-derived T/L bucket automatically.
    static int select_bucket(int real);
    int token_count_from_mask(const std::vector<float>& mask) const;
    int max_latent_frames() const;
    std::string preset_duration_path(int text_t) const;
    std::string preset_encoder_path(int text_t) const;
    std::string preset_vector_path(int text_t, int latent_l) const;
    std::string preset_vocoder_path(int latent_l) const;
    void ensure_duration_bucket(int text_t);
    void ensure_encoder_vector_bucket(int text_t, int latent_l);
    void ensure_vocoder_bucket(int latent_l);
    void ensure_inference_bucket(int text_t, int latent_l);

    struct InterpreterState;
    std::unique_ptr<InterpreterState> interp_;

    // Normal FP32 Qualcomm path: Duration/Encoder/Vocoder and the VE reference
    // use CPU/XNNPACK; only VE execution uses native LiteRT CompiledModel NPU.
    std::unique_ptr<InterpreterState> npu_cpu_fallback_;
    bool npu_vector_enabled_ = false;
    bool npu_vector_validated_ = false;
    int npu_vector_validation_passes_ = 0;
    std::string npu_rejection_reason_;
    void refresh_native_npu_report();

    std::unique_ptr<SupertonicTokenizer> tokenizer_;
    std::unordered_map<std::string, VoiceStyle> voices_;
    std::string voice_id_ = "F1";

    static constexpr int kTextT = 128;
    static constexpr int kMaxLatentL = 128;
    static constexpr std::array<int, 7> kAutoBuckets{{32, 48, 64, 80, 96, 112, 128}};
    static constexpr int kLatentChannels = 144;
    static constexpr int kChunkSamples = 512 * 6;
    static constexpr int kVecEstLMin = 17;
    static constexpr int kStyleTtlFloats = 50 * 256;
    static constexpr int kStyleDpFloats = 8 * 16;
    static constexpr int kSampleRate = 44100;

    int total_step_ = 4;
    int num_threads_ = 4;
    int duration_threads_ = 1;
    int encoder_threads_ = 1;
    int vector_threads_ = 1;
    int vocoder_threads_ = 1;
    Backend backend_ = Backend::Cpu;
    std::string backend_report_ = "CPU/XNNPACK";
    std::string native_library_dir_;
    std::string accelerator_cache_dir_;
    bool deep_profiler_ = false;
    std::string deep_profiler_dir_;
    std::shared_ptr<SupertonicExternalRunner> external_runner_;
    int chunk_cap_ = 0;  // 0 = fully automatic token/L-window chunking
    float speed_ = 1.0f;
    uint32_t seed_ = 0;
    uint32_t seed_used_ = 0;
    float chunk_silence_s_ = 0.0f;
    int pre_generation_queue_ = 1;
    int chunk_gap_min_ms_ = 0;
    int chunk_gap_max_ms_ = 250;
    int trailing_silence_trim_ms_ = 220;
    bool pre_generation_ = true;
    std::vector<std::unique_ptr<LiteRTSupertonicTts>> pregen_engines_;
    std::string duration_path_;
    std::string text_encoder_path_;
    std::string vector_estimator_path_;
    std::string vocoder_path_;
    std::string tokenizer_dir_;
    std::string voice_styles_dir_;

    // Direct static multi-signature AutoBucket bundle. This is enabled only
    // by the dedicated GELU MultiPreset model identity; it is never inferred
    // from files placed under the Soniqo model directory.
    bool static_multipreset_bundle_ = false;
    bool autobucket_enabled_ = false;
    std::string autobucket_dir_;
    int active_duration_t_ = 0;
    int active_encoder_t_ = 0;
    int active_vector_t_ = 0;
    int active_vector_l_ = 0;
    int active_vocoder_l_ = 0;

    std::atomic<bool> cancelled_{false};
    PerformanceProfile profile_{};

    // Fixed-shape scratch storage reused across sequential chunks. The engine
    // is already stateful/non-reentrant (profile_, last_pcm_, cancellation),
    // so reusing these buffers removes allocator churn without changing model
    // inputs, output values, T=128, or L=64.
    std::vector<int64_t> ids64_scratch_;
    std::vector<float> text_emb_scratch_;
    std::vector<float> latent_mask_scratch_;
    std::vector<float> latent_scratch_;
    std::unordered_map<std::string, float> duration_cache_;

    // Native Qualcomm CompiledModel validation buffers. The NPU output for
    // every VE step in the first chunk is compared with an exact XNNPACK
    // reference before any audio is emitted.
    std::vector<float> npu_input_scratch_;
    std::vector<float> npu_reference_scratch_;

    std::vector<float> last_pcm_;
};

} // namespace speech_core
