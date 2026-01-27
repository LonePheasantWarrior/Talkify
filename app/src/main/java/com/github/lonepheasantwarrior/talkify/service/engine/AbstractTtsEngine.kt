package com.github.lonepheasantwarrior.talkify.service.engine

import com.github.lonepheasantwarrior.talkify.service.TtsLogger

abstract class AbstractTtsEngine : TtsEngineApi {

    protected var isReleased: Boolean = false
        private set

    protected open val tag: String
        get() = javaClass.simpleName

    override fun stop() {
        TtsLogger.d("$tag: stop called")
    }

    override fun release() {
        TtsLogger.i("$tag: release called")
        isReleased = true
    }

    override fun getAudioConfig(): AudioConfig {
        return AudioConfig()
    }

    protected fun checkNotReleased() {
        if (isReleased) {
            val message = "Engine has been released"
            TtsLogger.e("$tag: $message")
            throw IllegalStateException(message)
        }
    }

    protected fun logDebug(message: String) {
        TtsLogger.d("$tag: $message")
    }

    protected fun logInfo(message: String) {
        TtsLogger.i("$tag: $message")
    }

    protected fun logWarning(message: String) {
        TtsLogger.w("$tag: $message")
    }

    protected fun logError(message: String, throwable: Throwable? = null) {
        TtsLogger.e("$tag: $message", throwable)
    }
}
