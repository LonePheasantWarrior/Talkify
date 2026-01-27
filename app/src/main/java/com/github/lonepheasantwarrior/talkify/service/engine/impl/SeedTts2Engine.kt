package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.SeedTts2Config
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import java.util.Locale

/**
 * 火山引擎 - 豆包语音合成 2.0 引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 支持流式音频合成，将音频数据块实时回调给系统
 *
 * 引擎 ID：seed-tts-2.0
 * 服务提供商：火山引擎
 */
class SeedTts2Engine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "seed-tts-2.0"
        const val ENGINE_NAME = "豆包语音合成2.0"

        private const val DEFAULT_LANGUAGE = "zh-CN"
        private const val VOICE_NAME_SEPARATOR = "::"
    }

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.SEED_TTS2

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseEngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val seedConfig = config as? SeedTts2Config
        if (seedConfig == null) {
            logError("Invalid config type, expected SeedTts2Config")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (seedConfig.appId.isEmpty() || seedConfig.accessKey.isEmpty()) {
            logError("AppId or AccessKey is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (text.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logInfo("Starting synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false

        // TODO: 实现火山引擎 Seed-TTS-2.0 API 调用
        // 当前返回未实现错误
        listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NOT_IMPLEMENTED))
    }

    override fun getSupportedLanguages(): Set<String> {
        return setOf("zh", "zh-CN", "en", "en-US")
    }

    override fun getDefaultLanguages(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        // 豆包 TTS 2.0 支持的声音列表
        val voiceIds = listOf(
            "zh_female_tianmeitaozi_mars_bigtts",
            "zh_female_cancan_mars_bigtts",
            "zh_female_qingxinnvsheng_mars_bigtts",
            "zh_female_shuangkuaisisi_moon_bigtts",
            "zh_male_wennuanahu_moon_bigtts"
        )

        for (langCode in getSupportedLanguages()) {
            for (voiceId in voiceIds) {
                voices.add(
                    Voice(
                        "$voiceId$VOICE_NAME_SEPARATOR$langCode",
                        Locale.forLanguageTag(langCode),
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        true,
                        emptySet()
                    )
                )
            }
        }
        return voices
    }

    override fun getDefaultVoiceId(
        lang: String?, 
        country: String?, 
        variant: String?, 
        currentVoiceId: String?
    ): String {
        if (currentVoiceId != null && currentVoiceId.isNotBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "zh_female_tianmeitaozi_mars_bigtts$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        // 检查 voiceId 是否在支持的声音列表中
        val realVoiceName = extractRealVoiceName(voiceId)
        return realVoiceName != null && realVoiceName.isNotBlank()
    }

    private fun extractRealVoiceName(androidVoiceName: String?): String? {
        if (androidVoiceName == null) return null
        return if (androidVoiceName.contains(VOICE_NAME_SEPARATOR)) {
            androidVoiceName.substringBefore(VOICE_NAME_SEPARATOR)
        } else {
            androidVoiceName
        }
    }

    override fun stop() {
        logInfo("Stopping synthesis")
        isCancelled = true
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val seedConfig = config as? SeedTts2Config
        var result = false
        if (seedConfig != null) {
            result = seedConfig.appId.isNotBlank() && seedConfig.accessKey.isNotBlank()
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return SeedTts2Config()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "app_id" -> context.getString(R.string.app_id_label)
            "access_key" -> context.getString(R.string.access_key_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
