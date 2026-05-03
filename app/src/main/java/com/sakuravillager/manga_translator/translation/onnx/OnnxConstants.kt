package com.sakuravillager.manga_translator.translation.onnx

import ai.onnxruntime.OrtSession

const val ORT_TAG = "OnnxRuntime"

fun createDefaultSessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    setIntraOpNumThreads(4)
}
