package com.engcamera.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 远程最新版本信息 */
data class UpdateInfo(val version: String, val url: String)

/**
 * 检查 GitHub Release 最新版本：应用启动不再联网核对，只有设置抽屉的「检查更新」
 * 按钮被主动点击时才请求 API（用户需求 2026-11-08）。点一次查一次，不做
 * 「每天最多一次」节流、不读写本地缓存，结果只保留在当前会话的状态里。
 */
object UpdateChecker {
    private const val RELEASE_API = "https://api.github.com/repos/zy8270/engCamera/releases/latest"

    /** 主动核对结果：Success(info=null 表示已是最新；非空=有新版本)，Failure=网络/解析失败 */
    sealed interface CheckResult {
        data class Success(val info: UpdateInfo?) : CheckResult
        data object Failure : CheckResult
    }

    /**
     * 主动核对：立即联网查询最新 Release（不读缓存、不受日期限制）。
     * 失败返回 Failure，调用方据此提示"核对失败"，不改动已有提示。
     */
    suspend fun checkNow(currentVersion: String): CheckResult = withContext(Dispatchers.IO) {
        val info = fetchLatest() ?: return@withContext CheckResult.Failure
        CheckResult.Success(if (isNewer(info.version, currentVersion)) info else null)
    }

    /** 请求 GitHub Release API，取 tag_name / html_url；任何失败返回 null（静默降级） */
    private fun fetchLatest(): UpdateInfo? {
        return runCatching {
            val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "engCamera-update-check")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val tag = json.optString("tag_name").trim()
                val url = json.optString("html_url").trim()
                if (tag.isBlank()) {
                    null
                } else {
                    UpdateInfo(
                        version = tag.removePrefix("v").removePrefix("V"),
                        url = url.ifBlank { "https://github.com/zy8270/engCamera/releases" },
                    )
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** 版本号比较（按数字段逐位比，忽略前缀 v 与非数字后缀）：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size, 1)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Long> =
        Regex("\\d+").findAll(v).mapNotNull { it.value.toLongOrNull() }.toList()
}
