package com.reelcut.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.reelcut.app.ui.theme.Palette
import com.reelcut.app.ui.theme.Type

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val valid = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkRaised,
        title = { Text("Rename project", style = Type.Title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                singleLine = true,
                textStyle = Type.Body,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    cursorColor = Palette.Accent,
                    unfocusedBorderColor = Palette.Stone,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(text.trim()) }, enabled = valid) {
                Text("Save", color = if (valid) Palette.Accent else Palette.Muted, style = Type.Label)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.White, style = Type.Label) }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismiss: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.InkRaised,
        title = { Text(title, style = Type.Title) },
        text = { Text(message, style = Type.Body, color = Palette.Muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = Palette.Accent, style = Type.Label) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismiss, color = Palette.White, style = Type.Label) }
        },
    )
}
