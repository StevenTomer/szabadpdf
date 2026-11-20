package org.szabadpdf.feature.organize

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Asks for a protected file's password (to merge or insert it). */
@Composable
internal fun UnlockDialog(request: UnlockRequest, onUnlock: (String) -> Unit, onSkip: () -> Unit) {
    var password by rememberSaveable(request.name) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${request.name} is protected. Enter its password to use its pages.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = request.wrongPassword,
                    supportingText = { if (request.wrongPassword) Text("Wrong password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onUnlock(password) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUnlock(password) }, enabled = password.isNotEmpty()) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip this file") } },
    )
}
