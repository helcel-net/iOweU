package net.helcel.owu.activity

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.helcel.owu.R
import net.helcel.owu.crypto.Identity
import net.helcel.owu.crypto.Keys
import net.helcel.owu.helper.toast
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.store.Backup
import net.helcel.owu.store.BackupException
import net.helcel.owu.store.Repo
import java.time.LocalDate
import java.util.Base64

/**
 * Settings' backup corner: write everything this phone is to a file, and read
 * one back - on this phone or the next one.
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var passphraseFor by remember { mutableStateOf<Uri?>(null) }      // writing
    var restoreFrom by remember { mutableStateOf<Uri?>(null) }        // reading
    var takeOver by remember { mutableStateOf<Backup.Contents?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        passphraseFor = uri
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreFrom = uri
    }

    fun write(uri: Uri, passphrase: String) {
        scope.launch {
            val trouble = withContext(Dispatchers.IO) {
                runCatching {
                    val (priv, pub) = Repo.signer.export()
                    val contents = Backup.Contents(
                        privateKey = Base64.getEncoder().encodeToString(priv),
                        publicKey = Base64.getEncoder().encodeToString(pub),
                        name = Repo.myName(context),
                        ious = Repo.store.ious.value.values.toList(),
                        templates = Repo.store.templates.value.values.toList(),
                        contacts = Repo.store.contacts.value,
                    )
                    val bytes = Backup.seal(contents, passphrase.toCharArray())
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("could not write there")
                }.exceptionOrNull()
            }
            context.toast(
                if (trouble == null) context.getString(R.string.backup_written)
                else trouble.message ?: context.getString(R.string.backup_failed)
            )
        }
    }

    fun read(uri: Uri, passphrase: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("could not read that file")
                    Backup.open(bytes, passphrase.toCharArray())
                }
            }
            result.fold(
                onSuccess = { contents ->
                    // OwUs, templates and names come back whatever happens;
                    // the identity is a separate question, since taking it on
                    // means giving up the one this phone has.
                    restore(contents)
                    val mine = Keys.decode(contents.publicKey)?.let { Base64.getEncoder().encodeToString(it.encoded) }
                    if (mine == Repo.me) context.toast(context.getString(R.string.backup_restored))
                    else takeOver = contents
                },
                onFailure = {
                    context.toast(
                        (it as? BackupException)?.message ?: context.getString(R.string.backup_failed)
                    )
                },
            )
        }
    }

    // --- dialogs -----------------------------------------------------------

    passphraseFor?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_export),
            hint = stringResource(R.string.backup_passphrase_hint),
            confirmTwice = true,
            onDismiss = { passphraseFor = null },
        ) { passphrase ->
            passphraseFor = null
            write(uri, passphrase)
        }
    }
    restoreFrom?.let { uri ->
        PassphraseDialog(
            title = stringResource(R.string.backup_restore),
            hint = stringResource(R.string.backup_passphrase_ask),
            confirmTwice = false,
            onDismiss = { restoreFrom = null },
        ) { passphrase ->
            restoreFrom = null
            read(uri, passphrase)
        }
    }
    takeOver?.let { contents ->
        AlertDialog(
            onDismissRequest = { takeOver = null },
            title = { Text(stringResource(R.string.backup_identity_title)) },
            text = { Text(stringResource(R.string.backup_identity_text, Keys.fingerprint(contents.publicKey))) },
            confirmButton = {
                TextButton(onClick = {
                    takeOver = null
                    runCatching {
                        PeerManager.stop()
                        Repo.adopt(
                            Identity.replace(
                                context,
                                Base64.getDecoder().decode(contents.privateKey),
                                Base64.getDecoder().decode(contents.publicKey),
                            )
                        )
                        Repo.setMyName(context, contents.name)
                    }.fold(
                        onSuccess = { context.toast(context.getString(R.string.backup_identity_taken)) },
                        onFailure = { context.toast(it.message ?: context.getString(R.string.backup_failed)) },
                    )
                }) { Text(stringResource(R.string.backup_identity_take)) }
            },
            dismissButton = {
                TextButton(onClick = { takeOver = null }) { Text(stringResource(R.string.backup_identity_keep)) }
            },
        )
    }

    // --- the two buttons, side by side -------------------------------------

    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(
            onClick = { create.launch("owu-backup-${LocalDate.now()}.json") },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.backup_export)) }
        Spacer(Modifier.size(12.dp))
        OutlinedButton(
            onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.backup_restore)) }
    }
}

/** Puts back everything but the identity, through the ordinary merge rules. */
private fun restore(contents: Backup.Contents) {
    contents.ious.forEach { Repo.store.merge(it) }
    contents.templates.forEach { Repo.store.putTemplate(it) }
    contents.contacts.forEach { Repo.store.putContact(it) }
}

/** Asks for a passphrase, twice when one is being chosen rather than recalled. */
@Composable
private fun PassphraseDialog(
    title: String,
    hint: String,
    confirmTwice: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val ready = first.length >= 8 && (!confirmTwice || first == again)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    hint, style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = first, onValueChange = { first = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = again, onValueChange = { again = it },
                        label = { Text(stringResource(R.string.backup_passphrase_again)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConfirm(first) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
