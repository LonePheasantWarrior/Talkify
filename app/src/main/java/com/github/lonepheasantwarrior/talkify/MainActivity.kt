package com.github.lonepheasantwarrior.talkify

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateCheckResult
import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.domain.repository.AppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.NetworkConnectivityChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.permission.PermissionChecker
import com.github.lonepheasantwarrior.talkify.infrastructure.app.repo.SharedPreferencesAppConfigRepository
import com.github.lonepheasantwarrior.talkify.infrastructure.app.update.UpdateChecker
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.components.NotificationPermissionDialog
import com.github.lonepheasantwarrior.talkify.ui.components.UpdateDialog
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"
        private const val PENDING_DIALOG_KEY = "pending_network_dialog"
    }

    private val activityScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    private var pendingDialog: AlertDialog? = null
    private var hasShownNetworkBlockedDialog by mutableStateOf(false)
    private var isReturningFromSettings by mutableStateOf(false)
    private var isCheckingNetwork by mutableStateOf(false)
    private var isActivityDestroyed by mutableStateOf(false)

    private var pendingUpdateInfo by mutableStateOf<UpdateInfo?>(null)

    private val updateChecker by lazy { UpdateChecker() }

    private val appConfigRepository: AppConfigRepository by lazy {
        SharedPreferencesAppConfigRepository(this)
    }

    private var showNotificationPermissionDialog by mutableStateOf(false)

    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            TtsLogger.d(TAG) { "notificationPermissionLauncher: 权限结果 = $isGranted" }
            if (isGranted) {
                TtsLogger.i(TAG) { "notificationPermissionLauncher: 用户授予了通知权限" }
            } else {
                TtsLogger.w(TAG) { "notificationPermissionLauncher: 用户拒绝了通知权限" }
            }
        }

    private val settingsLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isActivityDestroyed) return@registerForActivityResult
        TtsLogger.d(TAG) { "settingsLauncher: 用户从系统设置返回" }
        hasShownNetworkBlockedDialog = false
        isReturningFromSettings = true
        checkNetworkStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        modifier = Modifier.fillMaxSize(),
                        isCheckingNetwork = isCheckingNetwork
                    )

                    pendingUpdateInfo?.let { updateInfo ->
                        UpdateDialog(
                            updateInfo = updateInfo,
                            onDismiss = { pendingUpdateInfo = null },
                            onRemindLater = { pendingUpdateInfo = null }
                        )
                    }

                    if (showNotificationPermissionDialog) {
                        NotificationPermissionDialog(
                            onConfirm = { requestNotificationPermission() },
                            onDismiss = { skipNotificationPermission() }
                        )
                    }
                }
            }
        }

        checkNetworkStatus()
        checkNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        TtsLogger.d(TAG) { "MainActivity.onResume: isReturningFromSettings=$isReturningFromSettings" }
        if (!hasShownNetworkBlockedDialog && !isReturningFromSettings) {
            checkNetworkStatus()
        }
        isReturningFromSettings = false
    }

    override fun onPause() {
        super.onPause()
        TtsLogger.d(TAG) { "MainActivity.onPause" }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(PENDING_DIALOG_KEY, hasShownNetworkBlockedDialog)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        hasShownNetworkBlockedDialog = savedInstanceState.getBoolean(PENDING_DIALOG_KEY, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        isActivityDestroyed = true
        TtsLogger.d(TAG) { "MainActivity.onDestroy" }
        activityScope.cancel()
        pendingDialog?.dismiss()
        pendingDialog = null
    }

    private fun checkNetworkStatus() {
        if (isCheckingNetwork) {
            TtsLogger.d(TAG) { "checkNetworkStatus: 检查已在进行中，跳过" }
            return
        }

        TtsLogger.d(TAG) { "checkNetworkStatus: 开始检查网络状态..." }
        isCheckingNetwork = true

        activityScope.launch {
            try {
                val hasPermission = PermissionChecker.hasInternetPermission(this@MainActivity)
                TtsLogger.d(TAG) { "checkNetworkStatus: hasPermission = $hasPermission" }

                if (!hasPermission) {
                    TtsLogger.w(TAG) { "checkNetworkStatus: 无联网权限" }
                    showPermissionDeniedDialog()
                    return@launch
                }

                val canAccess = withContext(Dispatchers.IO) {
                    NetworkConnectivityChecker.canAccessInternet(this@MainActivity)
                }
                TtsLogger.d(TAG) { "checkNetworkStatus: canAccess = $canAccess" }

                if (canAccess) {
                    TtsLogger.i(TAG) { "checkNetworkStatus: 网络可用，继续启动" }
                    dismissDialog()
                    checkForUpdates()
                } else {
                    val reason = NetworkConnectivityChecker.getNetworkUnavailableReason(this@MainActivity)
                    TtsLogger.w(TAG) { "checkNetworkStatus: 网络不可用，原因为: $reason" }
                    showNetworkBlockedDialog()
                }
            } finally {
                isCheckingNetwork = false
            }
        }
    }

    private fun checkNotificationPermission() {
        TtsLogger.d(TAG) { "checkNotificationPermission: 开始检查通知权限..." }

        if (PermissionChecker.hasNotificationPermission(this)) {
            TtsLogger.d(TAG) { "checkNotificationPermission: 已拥有通知权限，无需弹窗" }
            return
        }

        if (appConfigRepository.hasSkippedNotificationPermission()) {
            TtsLogger.d(TAG) { "checkNotificationPermission: 用户之前选择跳过，不再弹窗" }
            return
        }

        TtsLogger.d(TAG) { "checkNotificationPermission: 需要显示通知权限请求弹窗" }
        showNotificationPermissionDialog = true
    }

    private fun requestNotificationPermission() {
        TtsLogger.d(TAG) { "requestNotificationPermission: 请求通知权限" }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        showNotificationPermissionDialog = false
    }

    private fun skipNotificationPermission() {
        TtsLogger.d(TAG) { "skipNotificationPermission: 用户选择以后再说" }
        appConfigRepository.setSkippedNotificationPermission(true)
        showNotificationPermissionDialog = false
    }

    private fun checkForUpdates() {
        TtsLogger.d(TAG) { "checkForUpdates: 开始检查更新..." }

        activityScope.launch {
            try {
                val currentVersion = getCurrentAppVersion()
                TtsLogger.d(TAG) { "checkForUpdates: 当前版本 = $currentVersion" }

                val result = withContext(Dispatchers.IO) {
                    updateChecker.checkForUpdates(currentVersion)
                }

                when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        TtsLogger.i(TAG) { "checkForUpdates: 发现新版本 ${result.updateInfo.versionName}" }
                        if (!isActivityDestroyed) {
                            pendingUpdateInfo = result.updateInfo
                        }
                    }
                    is UpdateCheckResult.NoUpdateAvailable -> {
                        TtsLogger.d(TAG) { "checkForUpdates: 已是最新版本或暂无 Release" }
                    }
                    is UpdateCheckResult.NetworkTimeout -> {
                        TtsLogger.w(TAG) { "checkForUpdates: 网络超时（国内网络无法访问 GitHub），静默放弃" }
                    }
                    is UpdateCheckResult.NetworkError -> {
                        TtsLogger.w(TAG) { "checkForUpdates: 网络错误: ${result.message}" }
                        showUpdateCheckError(getString(R.string.update_check_failed_network))
                    }
                    is UpdateCheckResult.ServerError -> {
                        TtsLogger.w(TAG) { "checkForUpdates: GitHub 服务端错误: ${result.httpCode}" }
                        showUpdateCheckError(getString(R.string.update_check_failed_server))
                    }
                    is UpdateCheckResult.ParseError -> {
                        TtsLogger.e(TAG) { "checkForUpdates: 解析错误: ${result.message}" }
                        showUpdateCheckError(getString(R.string.update_check_failed))
                    }
                }
            } catch (e: Exception) {
                TtsLogger.e(TAG) { "checkForUpdates: 检查更新时发生异常: ${e.message}" }
            }
        }
    }

    private fun showUpdateCheckError(message: String) {
        if (isActivityDestroyed) return
        TtsLogger.d(TAG) { "showUpdateCheckError: 显示错误提示" }
    }

    private fun getCurrentAppVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: PackageManager.NameNotFoundException) {
            TtsLogger.e(TAG) { "getCurrentAppVersion: 无法获取版本信息: ${e.message}" }
            "1.0.0"
        }
    }

    private fun showPermissionDeniedDialog() {
        if (isActivityDestroyed) return
        if (pendingDialog?.isShowing == true) return

        val title = getString(R.string.permission_required_title)
        val message = getString(R.string.permission_required_message)
        val positiveButton = getString(R.string.permission_grant)
        val negativeButton = getString(R.string.permission_exit)

        pendingDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ ->
                TtsLogger.d(TAG) { "showPermissionDeniedDialog: 用户选择授予权限" }
                openAppSettings()
            }
            .setNegativeButton(negativeButton) { _, _ ->
                TtsLogger.w(TAG) { "showPermissionDeniedDialog: 用户选择退出应用" }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showNetworkBlockedDialog() {
        if (isActivityDestroyed) return
        if (hasShownNetworkBlockedDialog) {
            TtsLogger.d(TAG) { "showNetworkBlockedDialog: 弹窗已显示过，跳过" }
            return
        }
        if (pendingDialog?.isShowing == true) {
            TtsLogger.d(TAG) { "showNetworkBlockedDialog: 弹窗已在显示中，跳过" }
            return
        }

        val title = getString(R.string.network_blocked_title)
        val message = getString(R.string.network_blocked_message)
        val positiveButton = getString(R.string.network_open_settings)
        val negativeButton = getString(R.string.permission_exit)

        pendingDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveButton) { _, _ ->
                TtsLogger.d(TAG) { "showNetworkBlockedDialog: 用户点击打开系统设置" }
                openAppSettings()
            }
            .setNegativeButton(negativeButton) { _, _ ->
                TtsLogger.w(TAG) { "showNetworkBlockedDialog: 用户选择退出应用" }
                finish()
            }
            .setCancelable(false)
            .show()

        hasShownNetworkBlockedDialog = true
    }

    private fun dismissDialog() {
        pendingDialog?.dismiss()
        pendingDialog = null
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            settingsLauncher.launch(intent)
        } catch (e: Exception) {
            TtsLogger.e(TAG) { "openAppSettings: 打开设置失败: ${e.message}" }
            finish()
        }
    }
}
