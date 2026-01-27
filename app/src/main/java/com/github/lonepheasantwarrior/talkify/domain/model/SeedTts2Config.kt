package com.github.lonepheasantwarrior.talkify.domain.model

/**
 * 豆包语音合成 2.0 引擎配置
 *
 * 继承 [BaseEngineConfig]，封装豆包语音合成引擎所需的配置信息
 * 包含火山引擎服务的 API Key 和语音模型配置
 *
 * 配置项说明：
 * - appId：火山引擎平台的 appId,用于标识产品
 * - accessKey：火山引擎平台的 accessToken,用于认证
 *
 *
 * @property voiceId 声音 ID，格式为 "声音名称::语言代码"
 *                   如 "CHERRY::zh-CN"、"EMMA::en-US" 等
 *                   可用声音列表参考 [AudioParameters.Voice]
 * @property appId 火山引擎平台的 appId,用于标识产品
 *                  从火山引擎平台控制台获取
 * @property accessKey 火山引擎平台的 accessToken,用于认证
 *  *                  从火山引擎平台控制台获取
 */
data class SeedTts2Config(
    override val voiceId: String = "",
    val appId: String = "",
    val accessKey: String = ""
) : BaseEngineConfig(voiceId)
