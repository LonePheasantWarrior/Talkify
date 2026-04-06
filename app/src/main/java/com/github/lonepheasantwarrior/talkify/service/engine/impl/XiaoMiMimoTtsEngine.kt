package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.Voice
import android.util.Base64
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.XiaoMiMimoConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 小米 - MiMo 语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 基于 OkHttp 实现 HTTP 流式音频合成，支持连接复用
 * 将音频数据块实时回调给系统
 *
 * 引擎 ID：xiaomi-mimo-tts
 * 服务提供商：小米
 * API 文档：https://api.xiaomimimo.com/doc
 */
class XiaoMiMimoTtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "xiaomi-mimo-tts"
        const val ENGINE_NAME = "小米MiMo语音合成"
        private const val VOICE_NAME_SEPARATOR = "::"
        private const val API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
        private const val MODEL_NAME = "mimo-v2-tts"

        // 文本分块配置
        private const val MAX_TEXT_LENGTH = 300

        // OkHttp 连接池配置（复用连接，空闲超时 45 秒）
        private val connectionPool = ConnectionPool(5, 45, TimeUnit.SECONDS)

        // 共享的 OkHttpClient 实例（支持连接复用）
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 支持的语言列表（ISO 639-2 三字母代码）
         */
        val SUPPORTED_LANGUAGES = arrayOf("zho", "eng")
    }

    // 协程作用域用于异步处理
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isCancelled = false

    @Volatile
    private var hasCompleted = false

    @Volatile
    private var currentCall: Call? = null

    @Volatile
    private var isFirstChunk = true

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.XIAOMI_MIMO_TTS

    /**
     * 缓存的声音ID列表，从资源文件加载
     */
    private val voiceIds: List<String> by lazy {
        loadVoiceIdsFromResource()
    }

    /**
     * 从资源文件加载声音ID列表
     */
    private fun loadVoiceIdsFromResource(): List<String> {
        val context = TalkifyAppHolder.getContext()
        return if (context != null) {
            try {
                context.resources.getStringArray(R.array.xiaomi_mimo_voices).toList()
            } catch (e: Exception) {
                TtsLogger.e("Failed to load voice IDs from resource", throwable = e)
                emptyList()
            }
        } else {
            TtsLogger.w("Context not available, voice IDs will be empty")
            emptyList()
        }
    }

    override fun getEngineId(): String = ENGINE_ID

    override fun getEngineName(): String = ENGINE_NAME

    override fun getAudioConfig(): AudioConfig = audioConfig

    override fun synthesize(
        text: String, params: SynthesisParams, config: BaseEngineConfig, listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val mimoConfig = config as? XiaoMiMimoConfig
        if (mimoConfig == null) {
            logError("Invalid config type, expected XiaoMiMimoConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (mimoConfig.apiKey.isEmpty()) {
            logError("API Key is not configured")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (text.isEmpty()) {
            logWarning("待朗读文本内容为空")
            listener.onSynthesisCompleted()
            return
        }

        if (!containsReadableText(text)) {
            logWarning("文本不包含可朗读的文字内容")
            listener.onSynthesisCompleted()
            return
        }

        logInfo("Starting synthesis: textLength=${text.length}, pitch=${params.pitch}, speechRate=${params.speechRate}")
        logDebug("Audio config: ${audioConfig.getFormatDescription()}")

        isCancelled = false
        hasCompleted = false
        isFirstChunk = true

        // 将文本分块处理
        val textChunks = splitTextIntoChunks(text, MAX_TEXT_LENGTH)
        if (textChunks.isEmpty()) {
            listener.onError("文本为空")
            return
        }

        logDebug("Text split into ${textChunks.size} chunks")

        // 使用协程顺序处理所有文本块
        engineScope.launch {
            processChunksSequentially(textChunks, mimoConfig, params, listener)
        }
    }

    /**
     * 顺序处理所有文本块
     */
    private suspend fun processChunksSequentially(
        chunks: List<String>,
        config: XiaoMiMimoConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ) {
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled || hasCompleted) {
                logDebug("Synthesis cancelled or completed, stopping chunk processing")
                return
            }

            logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

            val success = processSingleChunk(chunk, index, chunks.size, config, params, listener)
            if (!success) {
                logError("Failed to process chunk $index")
                return
            }
        }

        // 所有块处理完成
        if (!isCancelled && !hasCompleted) {
            hasCompleted = true
            withContext(Dispatchers.Main) {
                listener.onSynthesisCompleted()
            }
            logInfo("Synthesis completed successfully")
        }
    }

    /**
     * 处理单个文本块
     * @return 是否成功处理
     */
    private suspend fun processSingleChunk(
        text: String,
        chunkIndex: Int,
        totalChunks: Int,
        config: XiaoMiMimoConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = buildHttpRequest(text, config, params)

            // 使用同步调用便于顺序处理
            currentCall = sharedClient.newCall(request)

            val response = currentCall?.execute()
            if (response == null) {
                logError("Failed to execute HTTP request")
                withContext(Dispatchers.Main) {
                    listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_UNAVAILABLE))
                }
                return@withContext false
            }

            // 打印响应详情
            logDebug("HTTP Response Code: ${response.code}")
            logDebug("HTTP Response Headers: ${response.headers}")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "No error body"
                logError("HTTP error: ${response.code}, body: $errorBody")

                val errorMessage = parseError(errorBody)

                withContext(Dispatchers.Main) {
                    listener.onError(errorMessage)
                }
                response.close()
                return@withContext false
            }

            // 处理流式响应
            processStreamResponse(response, chunkIndex, listener)

        } catch (e: SocketTimeoutException) {
            logError("Network timeout", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_TIMEOUT))
            }
            false
        } catch (e: IOException) {
            logError("Network error", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_NETWORK_UNAVAILABLE))
            }
            false
        } catch (e: Exception) {
            logError("Unexpected error during synthesis", e)
            withContext(Dispatchers.Main) {
                listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED))
            }
            false
        } finally {
            currentCall = null
        }
    }

    /**
     * 处理流式响应（SSE 格式）
     */
    private suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        listener: TtsSynthesisListener
    ): Boolean = withContext(Dispatchers.IO) {
        val body = response.body
        if (body == null) {
            logError("Response body is null")
            return@withContext false
        }

        var hasError = false

        try {
            body.source().use { source ->
                while (!source.exhausted() && !isCancelled) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue

                    // SSE 格式: data: {...}
                    if (!line.startsWith("data:")) continue

                    val data = line.removePrefix("data:").trim()
                    if (data.isBlank()) continue

                    // [DONE] 表示流结束
                    if (data == "[DONE]") {
                        logDebug("Stream completed for chunk $chunkIndex")
                        break
                    }

                    try {
                        val json = JSONObject(data)

                        // 检查是否有错误
                        if (json.has("error")) {
                            val errorObj = json.getJSONObject("error")
                            val errMsg = errorObj.optString("message", "Unknown error")
                            logError("API error: $errMsg")
                            hasError = true
                            withContext(Dispatchers.Main) {
                                listener.onError(errMsg)
                            }
                            break
                        }

                        // 提取音频数据
                        // SSE 格式中，音频数据在 choices[0].delta.content 或 audio 字段中
                        val audioData = extractAudioFromSSE(json)
                        if (audioData != null && audioData.isNotEmpty()) {
                            // 首个音频块，触发开始回调
                            if (isFirstChunk) {
                                isFirstChunk = false
                                withContext(Dispatchers.Main) {
                                    listener.onSynthesisStarted()
                                }
                            }

                            listener.onAudioAvailable(
                                audioData,
                                audioConfig.sampleRate,
                                audioConfig.audioFormat,
                                audioConfig.channelCount
                            )
                            logDebug("Received audio data: ${audioData.size} bytes")
                        }

                    } catch (e: Exception) {
                        logError("Failed to parse SSE data: $data", e)
                        // 继续处理下一行，不中断
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error reading response stream", e)
            hasError = true
        } finally {
            response.close()
        }

        !hasError
    }

    /**
     * 从 SSE JSON 数据中提取音频数据
     * 格式参考 Python SDK: delta.audio["data"]
     */
    private fun extractAudioFromSSE(json: JSONObject): ByteArray? {
        return try {
            // 方式1: choices[0].delta.audio.data (base64 encoded) - 与 Python SDK 一致
            if (json.has("choices")) {
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    if (choice.has("delta")) {
                        val delta = choice.getJSONObject("delta")
                        if (delta.has("audio")) {
                            val audioObj = delta.get("audio")
                            if (audioObj is JSONObject) {
                                val audioData = audioObj.optString("data")
                                if (audioData.isNotBlank()) {
                                    return Base64.decode(audioData, Base64.DEFAULT)
                                }
                            }
                        }
                    }
                }
            }

            // 方式2: audio 字段直接包含 base64 数据
            if (json.has("audio")) {
                val audioObj = json.get("audio")
                if (audioObj is String) {
                    return Base64.decode(audioObj, Base64.DEFAULT)
                } else if (audioObj is JSONObject) {
                    val audioData = audioObj.optString("data")
                    if (audioData.isNotBlank()) {
                        return Base64.decode(audioData, Base64.DEFAULT)
                    }
                }
            }

            null
        } catch (e: Exception) {
            logError("Failed to extract audio data", e)
            null
        }
    }

    /**
     * 构建 HTTP 请求
     */
    private fun buildHttpRequest(
        text: String,
        config: XiaoMiMimoConfig,
        params: SynthesisParams
    ): Request {
        val voiceId = if (config.voiceId.isNotEmpty()) {
            extractRealVoiceName(config.voiceId) ?: config.voiceId
        } else {
            // 默认声音
            voiceIds.firstOrNull() ?: "default_en"
        }

        // 根据语言选择合适的默认声音
        val effectiveVoice = resolveVoiceForLanguage(voiceId, params.language)

        // 构建请求体 - OpenAI Chat Completions 格式
        val requestBody = JSONObject().apply {
            put("model", MODEL_NAME)
            put("messages", org.json.JSONArray().put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                }
            ))
            put("audio", JSONObject().apply {
                put("format", "pcm16")
                put("voice", effectiveVoice)
            })
            put("stream", true)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .header("api-key", config.apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Connection", "keep-alive")
            .build()

        // 打印请求详情（Headers 脱敏处理仅用于日志显示，实际发送的是原始值）
        logDebug("HTTP Request URL: ${request.url}")
        logDebug("HTTP Request Headers (masked for log): ${request.headers.toMaskedString()}")
        logDebug("HTTP Request Body: ${requestBody.toString(2)}")

        return request
    }

    /**
     * 根据语言解析合适的声音
     */
    private fun resolveVoiceForLanguage(voiceId: String, language: String?): String {
        // 如果已指定有效声音，直接返回
        if (voiceId.isNotBlank() && voiceIds.contains(voiceId)) {
            return voiceId
        }

        // 根据语言返回默认声音
        return when (language?.lowercase()) {
            "zh", "zho", "chi", "cn" -> "default_zh"
            "en", "eng" -> "default_en"
            else -> voiceId.ifBlank { "default_en" }
        }
    }

    /**
     * 将 Headers 转换为脱敏字符串用于日志
     */
    private fun okhttp3.Headers.toMaskedString(): String {
        val sb = StringBuilder("{")
        for (i in 0 until this.size) {
            val name = this.name(i)
            val value = this.value(i)
            val maskedValue = when (name.lowercase()) {
                "api-key" -> "${value.take(4)}****${value.takeLast(4)}"
                else -> value
            }
            sb.append("$name=$maskedValue")
            if (i < this.size - 1) sb.append(", ")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 解析错误响应
     */
    private fun parseError(errorBody: String): String {
        return try {
            val json = JSONObject(errorBody)
            val message = json.optString("error", "")
            if (message.isNotBlank()) {
                return message
            }
            // 尝试从 detail 或 message 获取
            json.optString("detail", json.optString("message", TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)))
        } catch (_: Exception) {
            TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
        }
    }

    /**
     * 将文本分割为块
     */
    private fun splitTextIntoChunks(text: String, maxLength: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var lastSplitPos = 0

        var i = 0
        while (i < text.length) {
            val remainingLength = text.length - lastSplitPos

            if (remainingLength <= maxLength) {
                chunks.add(text.substring(lastSplitPos))
                break
            }

            val isSentenceEnd = checkSentenceEnd(text, i)
            val isMidPause = checkMidPause(text, i)

            if (isSentenceEnd || isMidPause) {
                val chunkLength = i - lastSplitPos + 1
                if (chunkLength <= maxLength) {
                    chunks.add(text.substring(lastSplitPos, i + 1))
                    lastSplitPos = i + 1
                    i++
                    continue
                }
            }

            val splitPos = findBestSplitPos(text, lastSplitPos, maxLength)
            if (splitPos > lastSplitPos) {
                chunks.add(text.substring(lastSplitPos, splitPos))
                lastSplitPos = splitPos
            } else {
                chunks.add(text.substring(lastSplitPos, lastSplitPos + maxLength))
                lastSplitPos += maxLength
            }
            i = lastSplitPos
        }

        return chunks
    }

    private fun checkSentenceEnd(text: String, index: Int): Boolean {
        if (index < 0) return false
        val sentenceEnds = listOf("。", "！", "？", ".", "!", "?")
        for (ender in sentenceEnds) {
            if (text.regionMatches(index, ender, 0, ender.length)) {
                return true
            }
        }
        return false
    }

    private fun checkMidPause(text: String, index: Int): Boolean {
        if (index < 0) return false
        val midPauses = listOf("，", "、", ",", ";", "；", "：", ":")
        for (pause in midPauses) {
            if (text.regionMatches(index, pause, 0, pause.length)) {
                return true
            }
        }
        return false
    }

    private fun findBestSplitPos(text: String, startPos: Int, maxLength: Int): Int {
        val searchEnd = minOf(startPos + maxLength, text.length)

        for (i in searchEnd - 1 downTo startPos + 1) {
            if (checkMidPause(text, i)) {
                return i + 1
            }
        }

        for (i in searchEnd - 1 downTo startPos + 1) {
            val char = text[i]
            if (char == ' ' || char == '\n' || char == '\t') {
                return i + 1
            }
        }

        return searchEnd
    }

    override fun getSupportedLanguages(): Set<String> {
        return SUPPORTED_LANGUAGES.toSet()
    }

    override fun getDefaultLanguages(): Array<String> {
        return arrayOf(Locale.SIMPLIFIED_CHINESE.language, Locale.SIMPLIFIED_CHINESE.country, "")
    }

    override fun getSupportedVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()

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
        val defaultVoice = voiceIds.firstOrNull() ?: "default_en"
        if (currentVoiceId != null && currentVoiceId.isNotBlank()) {
            return "$currentVoiceId$VOICE_NAME_SEPARATOR$lang"
        }
        return "$defaultVoice$VOICE_NAME_SEPARATOR$lang"
    }

    override fun isVoiceIdCorrect(voiceId: String?): Boolean {
        if (voiceId == null) {
            return false
        }
        val realVoiceName = extractRealVoiceName(voiceId)
        return realVoiceName != null && voiceIds.contains(realVoiceName)
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
        currentCall?.cancel()
        currentCall = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        currentCall?.cancel()
        currentCall = null
        engineScope.cancel()
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val mimoConfig = config as? XiaoMiMimoConfig
        var result = false
        if (mimoConfig != null) {
            result = mimoConfig.apiKey.isNotBlank()
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return XiaoMiMimoConfig()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "api_key" -> context.getString(R.string.api_key_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
