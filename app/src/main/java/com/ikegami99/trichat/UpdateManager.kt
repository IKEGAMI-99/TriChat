package com.ikegami99.trichat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val activity: Activity, private val logs: LogStore) {
    private val scope = CoroutineScope(Dispatchers.Main)

    fun check(silent: Boolean = false) {
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetchLatest() }
                if (isNewer(info.version, BuildConfig.VERSION_NAME)) {
                    AlertDialog.Builder(activity)
                        .setTitle("TriChat ${info.version}")
                        .setMessage("新しいReleaseがあります。APKをダウンロードして更新します。")
                        .setNegativeButton("後で", null)
                        .setPositiveButton("更新") { _, _ -> downloadAndInstall(info.url, info.version) }
                        .show()
                } else if (!silent) Toast.makeText(activity, "最新版です", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                logs.i("UPDATE", t.stackTraceToString())
                if (!silent) Toast.makeText(activity, "更新確認に失敗: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class Release(val version: String, val url: String)

    private fun fetchLatest(): Release {
        val conn = URL("https://api.github.com/repos/IKEGAMI-99/TriChat/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = conn.inputStream.bufferedReader().use { it.readText() }
        val obj = JSONObject(json)
        val version = obj.getString("tag_name").removePrefix("v")
        val assets = obj.getJSONArray("assets")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
        }
        return Release(version, requireNotNull(apk) { "Release APK not found" })
    }

    private fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun downloadAndInstall(url: String, version: String) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Toast.makeText(activity, "TriChatからのインストールを許可してから、もう一度更新を押してください", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch {
            try {
                Toast.makeText(activity, "APKをダウンロード中…", Toast.LENGTH_SHORT).show()
                val apk = withContext(Dispatchers.IO) {
                    val out = File(activity.externalCacheDir ?: activity.cacheDir, "TriChat-$version.apk")
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                    c.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
                    require(out.length() > 100_000L) { "Downloaded APK is unexpectedly small" }
                    out
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
                activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (t: Throwable) {
                logs.i("UPDATE", t.stackTraceToString())
                Toast.makeText(activity, "APK更新に失敗: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
