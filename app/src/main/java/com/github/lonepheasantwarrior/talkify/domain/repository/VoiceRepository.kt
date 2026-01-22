package com.github.lonepheasantwarrior.talkify.domain.repository

import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine

data class VoiceInfo(
    val voiceId: String,
    val displayName: String
)

interface VoiceRepository {
    suspend fun getVoicesForEngine(engine: TtsEngine): List<VoiceInfo>
}
