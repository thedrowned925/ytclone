package com.thedrowned925.ytclone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.thedrowned925.ytclone.ingest.IngestQueue
import com.thedrowned925.ytclone.storage.SettingsStore
import com.thedrowned925.ytclone.ui.YTCloneApp

class MainActivity : ComponentActivity() {
    private val sharedUrl = mutableStateOf<String?>(null)
    private val mediaRepo = mutableStateOf("")
    private val tokenConfigured = mutableStateOf(false)
    private lateinit var settingsStore: SettingsStore

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val legacyStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val allFilesAccessSettings = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        mediaRepo.value = settingsStore.mediaRepo()
        tokenConfigured.value = !settingsStore.gitHubToken().isNullOrBlank()
        sharedUrl.value = extractSharedUrl(intent)
        requestNotificationPermissionIfNeeded()
        requestDownloadsAccessIfNeeded()

        setContent {
            YTCloneApp(
                incomingUrl = sharedUrl.value,
                onIncomingUrlConsumed = { sharedUrl.value = null },
                onArchive = { url, options ->
                    requestDownloadsAccessIfNeeded()
                    IngestQueue.enqueue(this, url, options)
                },
                mediaRepo = mediaRepo.value,
                tokenConfigured = tokenConfigured.value,
                onSaveStorageSettings = { repo, newToken ->
                    settingsStore.saveMediaRepo(repo)
                    if (!newToken.isNullOrBlank()) settingsStore.saveGitHubToken(newToken)
                    mediaRepo.value = settingsStore.mediaRepo()
                    tokenConfigured.value = !settingsStore.gitHubToken().isNullOrBlank()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { sharedUrl.value = it }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestDownloadsAccessIfNeeded() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                val appIntent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                val target = if (appIntent.resolveActivity(packageManager) != null) appIntent else fallback
                allFilesAccessSettings.launch(target)
            }
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '}')
    }

    companion object {
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}
