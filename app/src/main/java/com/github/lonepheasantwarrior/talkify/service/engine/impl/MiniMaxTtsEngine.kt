package com.github.lonepheasantwarrior.talkify.service.engine.impl

import android.speech.tts.Voice
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.TalkifyAppHolder
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.MiniMaxTtsConfig
import com.github.lonepheasantwarrior.talkify.service.TtsErrorCode
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.engine.AbstractTtsEngine
import com.github.lonepheasantwarrior.talkify.service.engine.AudioConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.Header
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * MiniMax - 语音合成引擎实现
 *
 * 继承 [AbstractTtsEngine]，实现 TTS 引擎接口
 * 基于 OkHttp 实现 HTTP 流式音频合成，支持连接复用
 * 将音频数据块实时回调给系统
 *
 * 引擎 ID：minimax-tts
 * 服务提供商：MiniMax
 * API 文档：https://platform.minimaxi.com/docs/llms.txt
 */
class MiniMaxTtsEngine : AbstractTtsEngine() {

    companion object {
        const val ENGINE_ID = "minimax-tts"
        const val ENGINE_NAME = "MiniMax语音合成"
        private const val VOICE_NAME_SEPARATOR = "::"
        private const val API_URL = "https://api.minimaxi.com/v1/t2a_v2"
        private const val BACKUP_API_URL = "https://api-bj.minimaxi.com/v1/t2a_v2"
        private const val DEFAULT_MODEL = "speech-2.8-hd"

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

        // 管道缓冲区大小，64KB 扩容管道，防止写入线程阻塞
        private const val PIPE_BUFFER_SIZE = 65536
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

    @Volatile
    private var decodeJob: Job? = null

    val audioConfig: AudioConfig
        @JvmName("getAudioConfigProperty") get() = AudioConfig.MINI_MAX_TTS

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
                context.resources.getStringArray(R.array.minimax_voices).toList()
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
        text: String,
        params: SynthesisParams,
        config: BaseEngineConfig,
        listener: TtsSynthesisListener
    ) {
        checkNotReleased()

        val miniMaxConfig = config as? MiniMaxTtsConfig
        if (miniMaxConfig == null) {
            logError("Invalid config type, expected MiniMaxTtsConfig")
            listener.onError(TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_ENGINE_NOT_CONFIGURED))
            return
        }

        if (miniMaxConfig.apiKey.isEmpty()) {
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
            processChunksSequentially(textChunks, miniMaxConfig, params, listener)
        }
    }

    /**
     * 顺序处理所有文本块
     */
    private suspend fun processChunksSequentially(
        chunks: List<String>,
        config: MiniMaxTtsConfig,
        params: SynthesisParams,
        listener: TtsSynthesisListener
    ) {
        // 创建管道用于 MP3 流式解码
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = withContext(Dispatchers.IO) {
            PipedInputStream(pipedOutputStream, PIPE_BUFFER_SIZE)
        }

        // 通知开始合成
        withContext(Dispatchers.Main) {
            listener.onSynthesisStarted()
        }

        // 启动解码协程（解码是 CPU 密集型操作，调度至 Default）
        decodeJob = engineScope.launch(Dispatchers.Default) {
            decodeMp3Stream(pipedInputStream, listener)
        }

        try {
            for ((index, chunk) in chunks.withIndex()) {
                if (isCancelled || hasCompleted) {
                    logDebug("Synthesis cancelled or completed, stopping chunk processing")
                    break
                }

                logDebug("Processing chunk $index/${chunks.size}, length=${chunk.length}")

                val success = processSingleChunk(
                    chunk,
                    index,
                    chunks.size,
                    config,
                    params,
                    pipedOutputStream,
                    listener
                )
                if (!success) {
                    logError("Failed to process chunk $index")
                    break
                }
            }
        } finally {
            // 关闭管道以通知解码器流已结束
            try {
                pipedOutputStream.flush()
                pipedOutputStream.close()
            } catch (e: Exception) {
                logDebug("Error closing pipe: ${e.message}")
            }
        }

        // 等待解码完成
        decodeJob?.join()

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
        config: MiniMaxTtsConfig,
        params: SynthesisParams,
        pipedOutputStream: PipedOutputStream,
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

            // 处理流式响应，将 MP3 数据写入管道
            processStreamResponse(response, chunkIndex, pipedOutputStream, listener)

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
     * 处理流式响应（SSE 格式），将 MP3 数据写入管道
     */
    private suspend fun processStreamResponse(
        response: Response,
        chunkIndex: Int,
        pipedOutputStream: PipedOutputStream,
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

                    try {
                        val json = JSONObject(data)

                        // 检查 base_resp 是否有错误
                        val baseResp = json.optJSONObject("base_resp")
                        if (baseResp != null) {
                            val statusCode = baseResp.optInt("status_code", 0)
                            val statusMsg = baseResp.optString("status_msg", "")
                            if (statusCode != 0) {
                                logError("API error: status_code=$statusCode, status_msg=$statusMsg")
                                hasError = true
                                withContext(Dispatchers.Main) {
                                    listener.onError(statusMsg.ifBlank { "API error: $statusCode" })
                                }
                                break
                            }
                        }

                        // 提取音频数据并写入管道
                        val dataObj = json.optJSONObject("data")
                        if (dataObj != null) {
                            val audioHex = dataObj.optString("audio")
                            val status = dataObj.optInt("status", 0)

                            if (audioHex.isNotBlank()) {
                                val mp3Bytes = hexToBytes(audioHex)
                                if (mp3Bytes.isNotEmpty()) {
                                    // 写入管道，由解码协程处理
                                    pipedOutputStream.write(mp3Bytes)
                                    logDebug("Wrote ${mp3Bytes.size} bytes to decode pipe, status=$status")
                                }
                            }

                            // status=2 表示该 chunk 合成结束
                            if (status == 2) {
                                logDebug("Chunk $chunkIndex finished")
                            }
                        }

                        // 打印 extra_info（最后的汇总信息）
                        val extraInfo = json.optJSONObject("extra_info")
                        if (extraInfo != null) {
                            logDebug(
                                "Extra info: audio_length=${extraInfo.optInt("audio_length")}, " +
                                        "usage_characters=${extraInfo.optInt("usage_characters")}"
                            )
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
     * 将十六进制字符串转换为字节数组
     */
    private fun hexToBytes(hex: String): ByteArray {
        return try {
            // MiniMax 返回的是 hex 编码的音频数据
            // 需要转换为字节数组
            val cleanHex = hex.replace("\\s".toRegex(), "")
            if (cleanHex.length % 2 != 0) {
                logWarning("Invalid hex string length: ${cleanHex.length}")
                return ByteArray(0)
            }
            val bytes = ByteArray(cleanHex.length / 2)
            for (i in bytes.indices) {
                val index = i * 2
                bytes[i] = cleanHex.substring(index, index + 2).toInt(16).toByte()
            }
            bytes
        } catch (e: Exception) {
            logError("Failed to decode hex audio data", e)
            ByteArray(0)
        }
    }

    /**
     * 流式解码 MP3 数据
     * 参考 MicrosoftTtsEngine 的实现，使用 JavaZoom 库进行流式 MP3 解码
     */
    private fun decodeMp3Stream(inputStream: PipedInputStream, listener: TtsSynthesisListener) {
        val bitstream = Bitstream(inputStream)
        val decoder = Decoder()
        var sampleRate: Int

        try {
            while (!isCancelled) {
                val header: Header = bitstream.readFrame() ?: break

                sampleRate = header.frequency()

                val sampleBuffer = decoder.decodeFrame(header, bitstream) as SampleBuffer
                val samples = sampleBuffer.buffer
                val sampleCount = sampleBuffer.bufferLength

                if (sampleCount > 0) {
                    val pcmBytes = shortArrayToByteArray(samples, sampleCount)
                    listener.onAudioAvailable(
                        pcmBytes,
                        sampleRate,
                        AudioConfig.DEFAULT_AUDIO_FORMAT,
                        AudioConfig.DEFAULT_CHANNEL_COUNT
                    )
                    logDebug("Decoded ${pcmBytes.size} bytes, sampleRate=$sampleRate")
                }

                bitstream.closeFrame()
            }
        } catch (e: Exception) {
            // 当管道关闭时，Bitstream 可能会抛出流结束异常，只需记录 Debug
            logDebug("MP3 decoding finished or interrupted: ${e.message}")
        } finally {
            try {
                bitstream.close()
            } catch (_: Exception) {
            }
            try {
                inputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 高性能 PCM 转换方案：利用 NIO ByteBuffer 内存块直接复制机制
     */
    private fun shortArrayToByteArray(shortArray: ShortArray, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(shortArray, 0, length)
        return buffer.array()
    }

    /**
     * 构建 HTTP 请求
     */
    private fun buildHttpRequest(
        text: String,
        config: MiniMaxTtsConfig,
        params: SynthesisParams
    ): Request {
        val voiceId = if (config.voiceId.isNotEmpty()) {
            extractRealVoiceName(config.voiceId) ?: config.voiceId
        } else {
            // 默认声音
            voiceIds.firstOrNull() ?: "male-qn-qingse"
        }

        val speed = convertSpeechRate(params.speechRate)
        val vol = convertVolume(params.volume)
        // MiniMax pitch: [-12, 12], 0 = original pitch
        // Android pitch: [0, 200], 100 = default (normal)
        // Conversion: (androidPitch - 100) * 12 / 100
        val pitch = ((params.pitch - 100f) * 12f / 100f).roundToInt().coerceIn(-12, 12)
        val emotion = resolveEmotion(params)

        logDebug("ttsSpeechRate: ${params.speechRate}, minimaxSpeed: $speed")
        logDebug("ttsVolume: ${params.volume}, minimaxVol: $vol")
        logDebug("ttsPitch: ${params.pitch}, minimaxPitch: $pitch")

        // 构建请求体 - MiniMax T2A V2 API 格式
        val requestBody = JSONObject().apply {
            put("model", DEFAULT_MODEL)
            put("text", text)
            put("stream", true)
            put("voice_setting", JSONObject().apply {
                put("voice_id", voiceId)
                put("speed", speed)
                put("vol", vol)
                put("pitch", pitch)
                if (emotion.isNotBlank()) {
                    put("emotion", emotion)
                }
            })
            put("audio_setting", JSONObject().apply {
                put("sample_rate", audioConfig.sampleRate)
                put("bitrate", 128000)
                put("format", "mp3")
                put("channel", audioConfig.channelCount)
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestBody.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(API_URL)
            .post(body)
            .header("Authorization", "Bearer ${config.apiKey}")
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
            "zh", "zho", "chi", "cn" -> "male-qn-qingse"
            "en", "eng" -> "English_Graceful_Lady"
            else -> voiceId.ifBlank { "male-qn-qingse" }
        }
    }

    /**
     * 解析情绪参数
     */
    private fun resolveEmotion(params: SynthesisParams): String {
        // MiniMax 支持的情绪: happy, sad, angry, fearful, disgusted, surprised, calm, fluent, whisper
        // Android TTS 通常没有明确的情绪映射，这里返回空字符串使用默认
        // 未来可以通过 pitch 或其他参数推导出情绪
        return ""
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
                "authorization" -> "Bearer ****"
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
            val baseResp = json.optJSONObject("base_resp")
            if (baseResp != null) {
                val statusCode = baseResp.optInt("status_code", 0)
                val statusMsg = baseResp.optString("status_msg", "")
                if (statusCode != 0) {
                    return parseMiniMaxError(statusCode, statusMsg)
                }
            }
            json.optString(
                "message",
                TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
            )
        } catch (_: Exception) {
            TtsErrorCode.getErrorMessage(TtsErrorCode.ERROR_SYNTHESIS_FAILED)
        }
    }

    /**
     * 解析 MiniMax 错误码
     */
    private fun parseMiniMaxError(statusCode: Int, statusMsg: String): String {
        return when (statusCode) {
            1000 -> "未知错误: $statusMsg"
            1001 -> "请求超时，请稍后重试"
            1002 -> "触发限流，请稍后重试"
            1004 -> "鉴权失败，请检查 API Key"
            1039 -> "触发 TPM 限流，请稍后重试"
            1042 -> "非法字符超过 10%，请检查文本内容"
            2013 -> "输入参数错误: $statusMsg"
            else -> "语音合成失败: $statusMsg (code: $statusCode)"
        }
    }

    /**
     * 转换语速参数
     * Android: [0, 200]，100 为默认值（表示 1.0x 倍速）
     * MiniMax: [0.5, 2]，1 为默认值
     */
    private fun convertSpeechRate(androidRate: Float): Float {
        return when {
            androidRate <= 50f -> 0.5f
            androidRate >= 200f -> 2.0f
            else -> androidRate / 100f
        }
    }

    /**
     * 转换音量参数
     * Android: [0.0, 1.0]
     * MiniMax: (0, 10]，1 为默认值
     */
    private fun convertVolume(androidVolume: Float): Float {
//        return when {
//            androidVolume <= 0f -> 0.1f
//            androidVolume >= 1.0f -> 10.0f
//            else -> androidVolume * 10f
//        }
        return androidVolume;
    }

    /**
     * 将文本分割为块
     * MiniMax 支持最长 10000 字符，但建议超过 3000 字符使用流式
     * 这里限制为 300 字符以保证合成质量
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

            // 在句号、逗号等标点处分割
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
        val defaultVoice = voiceIds.firstOrNull() ?: "male-qn-qingse"
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
        decodeJob?.cancel()
        decodeJob = null
    }

    override fun release() {
        logInfo("Releasing engine")
        isCancelled = true
        currentCall?.cancel()
        currentCall = null
        decodeJob?.cancel()
        decodeJob = null
        engineScope.cancel()
        super.release()
    }

    override fun isConfigured(config: BaseEngineConfig?): Boolean {
        val miniMaxConfig = config as? MiniMaxTtsConfig
        var result = false
        if (miniMaxConfig != null) {
            result = miniMaxConfig.apiKey.isNotBlank()
        }
        TtsLogger.d("$tag: isConfigured = $result")
        return result
    }

    override fun createDefaultConfig(): BaseEngineConfig {
        return MiniMaxTtsConfig()
    }

    override fun getConfigLabel(configKey: String, context: android.content.Context): String? {
        return when (configKey) {
            "api_key" -> context.getString(R.string.api_key_label)
            "voice_id" -> context.getString(R.string.voice_select_label)
            else -> null
        }
    }
}
