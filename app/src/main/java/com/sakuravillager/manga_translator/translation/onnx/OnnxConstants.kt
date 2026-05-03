package com.sakuravillager.manga_translator.translation.onnx

import ai.onnxruntime.GraphOptimizationLevel
import ai.onnxruntime.OrtSession

const val ORT_TAG = "OnnxRuntime"

fun createDefaultSessionOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
    setSessionGraphOptimizationLevel(GraphOptimizationLevel.ORT_ENABLE_ALL)
    setIntraOpNumThreads(4)
}
