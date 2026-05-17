package com.sakuravillager.manga_translator.translation.inpaint

import android.content.Context
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager

class LamaLargeInpainter(
    modelDownloadManager: ModelDownloadManager,
    sessionManager: OnnxSessionManager,
    context: Context,
) : LamaMPEInpainter(modelDownloadManager, sessionManager, context) {
    override val name: String = "LamaLargeInpainter"
    override val logTag: String = "LamaLargeInpainter"
}