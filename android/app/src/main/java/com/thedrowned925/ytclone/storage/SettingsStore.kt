package com.thedrowned925.ytclone.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.thedrowned925.ytclone.ingest.IngestOptions
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ytclone_settings", Context.MODE_PRIVATE)

    /**
     * YTClone currently stores both the Android app and its media Releases in the
     * same repository. Keep this automatic so the only secret the user needs to
     * enter is the GitHub token.
     */
    fun mediaRepo(): String = DEFAULT_MEDIA_REPO

    /** Kept for compatibility with older UI/call sites; repo is intentionally fixed. */
    fun saveMediaRepo(@Suppress("UNUSED_PARAMETER") repo: String) = Unit

    fun saveGitHubToken(token: String) {
        if (token.isBlank()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun gitHubToken(): String? {
        val encoded = prefs.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = packed.copyOfRange(0, IV_LENGTH)
            val encrypted = packed.copyOfRange(IV_LENGTH, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    fun saveLastIngestWorkId(workId: String) {
        prefs.edit().putString(KEY_LAST_INGEST_WORK_ID, workId).apply()
    }

    fun lastIngestWorkId(): String? = prefs.getString(KEY_LAST_INGEST_WORK_ID, null)

    fun saveIngestOptions(options: IngestOptions) {
        prefs.edit()
            .putBoolean(KEY_ALL_AUDIO, options.allAudioTracks)
            .putBoolean(KEY_SUBTITLES, options.subtitles)
            .putBoolean(KEY_KEEP_ORIGINAL, options.keepOriginal)
            .putBoolean(KEY_RENDITIONS, options.createRenditions)
            .apply()
    }

    fun ingestOptions(): IngestOptions = IngestOptions(
        allAudioTracks = prefs.getBoolean(KEY_ALL_AUDIO, true),
        subtitles = prefs.getBoolean(KEY_SUBTITLES, true),
        keepOriginal = prefs.getBoolean(KEY_KEEP_ORIGINAL, true),
        createRenditions = prefs.getBoolean(KEY_RENDITIONS, true),
    )

    fun isConfigured(): Boolean = !gitHubToken().isNullOrBlank()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val DEFAULT_MEDIA_REPO = "thedrowned925/ytclone"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ytclone-github-token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val KEY_TOKEN = "github_token"
        private const val KEY_LAST_INGEST_WORK_ID = "last_ingest_work_id"
        private const val KEY_ALL_AUDIO = "ingest_all_audio"
        private const val KEY_SUBTITLES = "ingest_subtitles"
        private const val KEY_KEEP_ORIGINAL = "ingest_keep_original"
        private const val KEY_RENDITIONS = "ingest_renditions"
    }
}
