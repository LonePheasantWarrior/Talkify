package com.github.lonepheasantwarrior.talkify.infrastructure.repository

import android.content.Context
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.VoiceRepository

class AlibabaCloudVoiceRepository(
    private val context: Context
) : VoiceRepository {

    private val engineVoiceMap = mapOf(
        "ali_bailian_tongyi" to VoiceConfig(
            voiceIdsResId = R.array.bailian_qwen3_tts_voices,
            displayNamesResId = R.array.bailian_qwen3_tts_voice_display_names
        )
    )

    override suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo> {
        val config = engineVoiceMap[engine.id] ?: return emptyList()

        val voiceIds = context.resources.getStringArray(config.voiceIdsResId)
        val displayNames = context.resources.getStringArray(config.displayNamesResId)

        if (voiceIds.size != displayNames.size) {
            return emptyList()
        }

        return voiceIds.mapIndexed { index, voiceId ->
            VoiceInfo(
                voiceId = voiceId,
                displayName = displayNames[index]
            )
        }
    }

    private data class VoiceConfig(
        val voiceIdsResId: Int,
        val displayNamesResId: Int
    )
}
