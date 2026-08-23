package com.thedrowned925.ytclone.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thedrowned925.ytclone.storage.SettingsStore

@Composable
fun SettingsDialog(
    initialRepo: String,
    tokenConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (repo: String, newToken: String?) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val repo = SettingsStore.DEFAULT_MEDIA_REPO

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YTClone GitHub bağlantısı") },
        text = {
            Column {
                Text("Medya deposu otomatik ayarlı: $repo")
                Spacer(Modifier.height(6.dp))
                Text("Token uygulamaya gömülmez; Android Keystore ile bu cihazda şifreli korunur.")
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (tokenConfigured) "Yeni token (boş bırakırsan değişmez)" else "GitHub token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (tokenConfigured) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Bu cihazda kayıtlı bir token var.")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(repo, token.trim().takeIf { it.isNotBlank() })
                    onDismiss()
                },
                enabled = tokenConfigured || token.isNotBlank(),
            ) { Text("Kaydet") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Vazgeç") }
        },
    )
}
