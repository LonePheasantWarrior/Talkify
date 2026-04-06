package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * MiniMax 语音合成引擎配置
 *
 * 继承 [BaseEngineConfig]，封装 MiniMax 引擎所需的配置信息
 * 使用 MiniMax 服务的 API Key 进行认证
 *
 * @property voiceId 声音 ID，如 "male-qn-qingse"
 * @property apiKey MiniMax 平台的 API Key，用于认证
 *                  从 MiniMax 开放平台获取
 */
data class MiniMaxTtsConfig(
    override val voiceId: String = "",
    val apiKey: String = ""
) : BaseEngineConfig(voiceId)
