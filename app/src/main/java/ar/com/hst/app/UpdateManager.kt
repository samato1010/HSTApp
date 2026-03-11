package ar.com.hst.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val VERSION_URL = "https://hst.ar/app/version.json"
    private const val PREFS_NAME = "hst_update"
    private const val KEY_DECLINED_VERSION = "declined_version"
    private const val KEY_DECLINED_TIME = "declined_time"
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours

    fun checkForUpdate(activity: androidx.appcompat.app.AppCompatActivity) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(VERSION_URL).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Silently fail - no internet or server down
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: return)
                    val serverVersion = json.getString("version")
                    val apkUrl = json.getString("apk_url")
                    val currentVersion = BuildConfig.VERSION_NAME

                    if (isNewer(serverVersion, currentVersion) && !isDeclined(activity, serverVersion)) {
                        activity.runOnUiThread {
                            showUpdateDialog(activity, apkUrl, serverVersion)
                        }
                    }
                } catch (_: Exception) {}
            }
        })
    }

    private fun isNewer(server: String, current: String): Boolean {
        val s = server.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(s.size, c.size)) {
            val sv = s.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (sv > cv) return true
            if (sv < cv) return false
        }
        return false
    }

    private fun isDeclined(context: Context, version: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val declinedVersion = prefs.getString(KEY_DECLINED_VERSION, "") ?: ""
        val declinedTime = prefs.getLong(KEY_DECLINED_TIME, 0)
        if (declinedVersion != version) return false
        return System.currentTimeMillis() - declinedTime < COOLDOWN_MS
    }

    private fun setDeclined(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DECLINED_VERSION, version)
            .putLong(KEY_DECLINED_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun showUpdateDialog(activity: androidx.appcompat.app.AppCompatActivity, apkUrl: String, version: String) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title))
            .setMessage("Versión $version disponible.\n${activity.getString(R.string.update_message)}")
            .setPositiveButton(activity.getString(R.string.update_yes)) { _, _ ->
                downloadAndInstall(activity, apkUrl)
            }
            .setNegativeButton(activity.getString(R.string.update_no)) { _, _ ->
                setDeclined(activity, version)
            }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstall(context: Context, apkUrl: String) {
        Toast.makeText(context, "Descargando actualización…", Toast.LENGTH_SHORT).show()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("HST Actualización")
            .setDescription("Descargando nueva versión...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "hst-update.apk")

        // Delete old file if exists
        val oldFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hst-update.apk")
        if (oldFile.exists()) oldFile.delete()

        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    try {
                        ctx.unregisterReceiver(this)
                    } catch (_: Exception) {}
                    installApk(ctx)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun installApk(context: Context) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "hst-update.apk")
            if (!file.exists()) {
                Toast.makeText(context, "Error: archivo de actualización no encontrado", Toast.LENGTH_LONG).show()
                return
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error al instalar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
