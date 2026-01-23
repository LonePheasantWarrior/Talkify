package com.github.lonepheasantwarrior.talkify.infrastructure.app.permission

import android.content.Context
import com.github.lonepheasantwarrior.talkify.service.TtsLogger

/**
 * 网络连接检测工具类
 *
 * 提供统一的网络连接检测入口，整合权限检查和网络状态检查。
 *
 * 检测流程：
 * 1. 检查联网权限（由 PermissionChecker 负责）
 * 2. 检查网络可用性（由 ConnectivityMonitor 负责）
 * 3. 测试实际网络连接（检测 Android 16 "允许网络访问"开关）
 *
 * @see PermissionChecker
 * @see ConnectivityMonitor
 */
object NetworkConnectivityChecker {

    private const val TAG = "TalkifyNetwork"

    /**
     * 检查是否可以通过网络访问互联网
     *
     * 完整的网络访问能力检查，包括权限和实际连接能力。
     * 这是最严格的检查，考虑到 Android 16 的"允许网络访问"开关限制。
     *
     * @param context 上下文
     * @return 是否可以访问互联网
     */
    suspend fun canAccessInternet(context: Context): Boolean {
        TtsLogger.d(TAG) { "canAccessInternet: 开始检查网络访问能力..." }

        val hasPermission = PermissionChecker.hasInternetPermission(context)
        TtsLogger.d(TAG) { "canAccessInternet: hasPermission = $hasPermission" }

        if (!hasPermission) {
            TtsLogger.w(TAG) { "canAccessInternet: 无联网权限" }
            return false
        }

        val canConnect = ConnectivityMonitor.canAccessInternet(context)
        TtsLogger.d(TAG) { "canAccessInternet: canConnect = $canConnect" }

        val result = canConnect
        TtsLogger.d(TAG) { "canAccessInternet: 最终结果 = $result" }
        return result
    }

    /**
     * 获取网络不可用的原因
     *
     * @param context 上下文
     * @return 不可用原因描述
     */
    fun getNetworkUnavailableReason(context: Context): NetworkUnavailableReason {
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: 开始检查网络不可用原因..." }

        val hasPermission = PermissionChecker.hasInternetPermission(context)
        TtsLogger.d(TAG) { "getNetworkUnavailableReason: hasPermission = $hasPermission" }

        if (!hasPermission) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_PERMISSION" }
            return NetworkUnavailableReason.NO_PERMISSION
        }

        val status = ConnectivityMonitor.getCurrentNetworkStatus(context)
        TtsLogger.d(TAG) {
            "getNetworkUnavailableReason: status = " +
                "hasNetwork=${status.hasNetwork}, " +
                "hasInternetCapability=${status.hasInternetCapability}, " +
                "isBlockedBySystem=${status.isBlockedBySystem}"
        }

        if (!status.hasNetwork) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_NETWORK" }
            return NetworkUnavailableReason.NO_NETWORK
        }

        if (status.isBlockedBySystem) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = BLOCKED_BY_SYSTEM" }
            return NetworkUnavailableReason.BLOCKED_BY_SYSTEM
        }

        if (!status.isValidated) {
            TtsLogger.w(TAG) { "getNetworkUnavailableReason: 原因 = NO_INTERNET_ACCESS" }
            return NetworkUnavailableReason.NO_INTERNET_ACCESS
        }

        TtsLogger.d(TAG) { "getNetworkUnavailableReason: 原因 = NONE (网络可用)" }
        return NetworkUnavailableReason.NONE
    }

    /**
     * 网络不可用原因枚举
     */
    enum class NetworkUnavailableReason {
        NONE,
        NO_PERMISSION,
        NO_NETWORK,
        BLOCKED_BY_SYSTEM,
        NO_INTERNET_ACCESS
    }
}
