package com.github.lonepheasantwarrior.talkify.infrastructure.engine.repo

import android.content.Context
import android.content.SharedPreferences
import com.github.lonepheasantwarrior.talkify.domain.model.EngineConfig
import com.github.lonepheasantwarrior.talkify.domain.model.TtsEngine
import com.github.lonepheasantwarrior.talkify.domain.repository.EngineConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository

/**
 * 通义千问3语音合成引擎 - 配置仓储实现
 *
 * 使用 Android SharedPreferences 持久化存储引擎配置
 * 遵循 [EngineConfigRepository] 接口，便于后续扩展其他存储方式
 *
 * 注意：全局配置（如"选择的引擎"）由 [SharedPreferencesAppConfigRepository] 管理
 */
class Qwen3TtsConfigRepository(
    context: Context
) : EngineConfigRepository {

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getConfig(engine: TtsEngine): EngineConfig {
        val prefsKey = getPrefsKey(engine)
        return EngineConfig(
            apiKey = sharedPreferences.getString("${prefsKey}_$KEY_API_KEY", "") ?: "",
            voiceId = sharedPreferences.getString("${prefsKey}_$KEY_VOICE_ID", "") ?: ""
        )
    }

    override fun saveConfig(engine: TtsEngine, config: EngineConfig) {
        val prefsKey = getPrefsKey(engine)
        sharedPreferences.edit()
            .putString("${prefsKey}_$KEY_API_KEY", config.apiKey)
            .putString("${prefsKey}_$KEY_VOICE_ID", config.voiceId)
            .apply()
    }

    override fun hasConfig(engine: TtsEngine): Boolean {
        val prefsKey = getPrefsKey(engine)
        return sharedPreferences.contains("${prefsKey}_$KEY_API_KEY") ||
                sharedPreferences.contains("${prefsKey}_$KEY_VOICE_ID")
    }

    private fun getPrefsKey(engine: TtsEngine): String {
        return "engine_${engine.id}"
    }

    companion object {
        private const val PREFS_NAME = "talkify_engine_configs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_VOICE_ID = "voice_id"
    }
}
