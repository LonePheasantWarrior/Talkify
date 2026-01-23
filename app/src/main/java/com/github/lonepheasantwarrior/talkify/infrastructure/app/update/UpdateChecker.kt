package com.github.lonepheasantwarrior.talkify.infrastructure.app.update

import com.github.lonepheasantwarrior.talkify.domain.model.UpdateInfo
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(
    private val owner: String = "LonePheasantWarrior",
    private val repo: String = "Talkify"
) {
    companion object {
        private const val TAG = "TalkifyUpdate"
        private const val API_URL = "https://api.github.com/repos/%s/%s/releases/latest"
        private const val GITHUB_RELEASE_URL = "https://github.com/%s/%s/releases/tag/%s"
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 10000
    }

    fun checkForUpdates(currentVersion: String): UpdateInfo? {
        TtsLogger.i(TAG) { "开始检查更新，当前版本: $currentVersion" }

        return try {
            val apiUrl = String.format(API_URL, owner, repo)
            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "Talkify-Android-App")
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            TtsLogger.d(TAG) { "GitHub API 响应码: $responseCode" }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                val reader = inputStream.bufferedReader()
                val response = reader.readText()
                reader.close()
                inputStream.close()

                val updateInfo = parseReleaseResponse(response, currentVersion)

                if (updateInfo != null && isVersionNewer(updateInfo.versionName, currentVersion)) {
                    TtsLogger.i(TAG) { "发现新版本: ${updateInfo.versionName}" }
                    updateInfo
                } else {
                    TtsLogger.i(TAG) { "当前已是最新版本" }
                    null
                }
            } else {
                TtsLogger.w(TAG) { "检查更新失败，响应码: $responseCode" }
                null
            }
        } catch (e: Exception) {
            TtsLogger.e(TAG) { "检查更新时发生异常: ${e.message}" }
            null
        }
    }

    private fun parseReleaseResponse(jsonResponse: String, currentVersion: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonResponse)

            val versionName = json.optString("tag_name", "")
            if (versionName.isEmpty()) {
                TtsLogger.w(TAG) { "无法获取版本名称" }
                return null
            }

            val releaseNotes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "")
            val publishedAt = json.optString("published_at", "")

            val downloadUrl = findApkAssetUrl(json, versionName)

            UpdateInfo(
                versionName = versionName,
                releaseNotes = releaseNotes,
                releaseUrl = htmlUrl,
                downloadUrl = downloadUrl,
                publishedAt = formatDate(publishedAt)
            )
        } catch (e: Exception) {
            TtsLogger.e(TAG) { "解析 Release 响应失败: ${e.message}" }
            null
        }
    }

    private fun findApkAssetUrl(json: JSONObject, versionName: String): String? {
        val assets = json.optJSONArray("assets") ?: return null

        val assetCount = assets.length()
        for (i in 0.until(assetCount).toList()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "").lowercase()

            if (name.endsWith(".apk")) {
                val downloadUrl = asset.optString("browser_download_url", "")
                return downloadUrl.ifEmpty { null }
            }
        }

        val releaseUrl = String.format(GITHUB_RELEASE_URL, owner, repo, versionName)
        return releaseUrl
    }

    private fun formatDate(isoDate: String): String {
        if (isoDate.isEmpty()) return ""

        return try {
            val datePart = isoDate.split("T")[0]
            datePart
        } catch (e: Exception) {
            isoDate
        }
    }

    private fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        var latest = latestVersion
        var current = currentVersion

        if (latest.startsWith("v") || latest.startsWith("V")) {
            latest = latest.substring(1)
        }
        if (current.startsWith("v") || current.startsWith("V")) {
            current = current.substring(1)
        }

        val latestParts = latest.split(".")
        val currentParts = current.split(".")

        val maxLength = Math.max(latestParts.size, currentParts.size)

        for (i in 0.until(maxLength).toList()) {
            val latestNum = try {
                latestParts.getOrNull(i)?.toInt() ?: 0
            } catch (e: Exception) {
                0
            }
            val currentNum = try {
                currentParts.getOrNull(i)?.toInt() ?: 0
            } catch (e: Exception) {
                0
            }

            when {
                latestNum > currentNum -> return true
                latestNum < currentNum -> return false
            }
        }

        return false
    }
}
