package com.github.lonepheasantwarrior.talkify.domain.model

data class SynthesisParams(
    val text: String,
    val engine: TtsEngine,
    val voice: Voice
)
