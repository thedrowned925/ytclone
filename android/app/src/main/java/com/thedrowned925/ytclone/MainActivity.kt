package com.thedrowned925.ytclone

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Rational
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
    private val pipMode = mutableStateOf(false)
    private var pipEligible = false
    private lateinit var settingsStore: SettingsStore

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val legacyStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val allFilesAccessSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        mediaRepo.value = settingsStore.mediaRepo()
        tokenConfigured.value = !settingsStore.gitHubToken().isNullOrBlank()
        sharedUrl.value = extractSharedUrl(intent)
        requestNotificationPermissionIfNeeded()
        requestDownloadsAccessIfNeeded()
        updatePictureInPictureParams()

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
                isPipMode = pipMode.value,
                onPlaybackActiveChanged = { eligible ->
                    pipEligible = eligible
                    updatePictureInPictureParams()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractSharedUrl(intent)?.let { sharedUrl.value = it }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Keep auto-enter for Android 12+, but also call enterPictureInPictureMode
        // explicitly as a Samsung/OEM fallback. runCatching makes the double path safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipEligible && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(buildPipParams(autoEnter = false)) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }

    private fun updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { setPictureInPictureParams(buildPipParams(autoEnter = pipEligible)) }
        }
    }

    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
        return builder.build()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestDownloadsAccessIfNeeded() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager() -> {
                val appIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                allFilesAccessSettings.launch(if (appIntent.resolveActivity(packageManager) != null) appIntent else fallback)
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
