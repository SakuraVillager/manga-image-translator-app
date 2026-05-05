package com.sakuravillager.manga_translator.translation.onnx

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OnnxSessionManager {

    val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessions = mutableListOf<OrtSession>()

    suspend fun createSession(
        modelBytes: ByteArray,
        options: OrtSession.SessionOptions? = null,
    ): OrtSession = withContext(Dispatchers.Default) {
        val opts = options ?: createDefaultSessionOptions()
        val session = environment.createSession(modelBytes, opts)
        addSession(session)
        session
    }

    @Synchronized
    private fun addSession(session: OrtSession) {
        sessions.add(session)
    }

    @Synchronized
    fun closeSession(session: OrtSession) {
        session.close()
        sessions.remove(session)
    }

    @Synchronized
    fun closeAll() {
        sessions.forEach { it.close() }
        sessions.clear()
    }
}
