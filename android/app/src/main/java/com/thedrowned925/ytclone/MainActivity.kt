package com.thedrowned925.ytclone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        mediaRepo.value = settingsStore.mediaRepo()
        tokenConfigured.value = !settingsStore.gitHubToken().isNullOrBlank()
        sharedUrl.value = extractSharedUrl(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            YTCloneApp(
                incomingUrl = sharedUrl.value,
                onIncomingUrlConsumed = { sharedUrl.value = null },
                onArchive = { url, options -> IngestQueue.enqueue(this, url, options) },
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

    private fun extractSharedUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', '}')
    }

    companion object {
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}
