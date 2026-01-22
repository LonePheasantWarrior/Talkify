package com.github.lonepheasantwarrior.talkify.domain.model

data class TtsEngine(
    val id: String,
    val name: String,
    val provider: String
)

data class Voice(
    val id: String,
    val name: String,
    val gender: VoiceGender,
    val language: String
)

enum class VoiceGender {
    MALE, FEMALE, NEUTRAL
}
