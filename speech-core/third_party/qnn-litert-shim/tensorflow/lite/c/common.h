#pragma once

#include <stdint.h>

// The Qualcomm qnn-litert-delegate AAR ships its public C header but expects
// TensorFlow Lite's large common.h include tree. Supertonic only consumes the
// delegate as an opaque classic C handle, so these forward declarations are
// the complete ABI surface required by QnnTFLiteDelegate.h.

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TfLiteDelegate TfLiteDelegate;
typedef struct TfLiteRegistration TfLiteRegistration;

#ifdef __cplusplus
}  // extern "C"
#endif
