package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.ui.ConnectUiState

@Composable
fun ConnectScreen(state: ConnectUiState, onUrl: (String) -> Unit, onPassword: (String) -> Unit, onTest: () -> Unit, onLogin: () -> Unit) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val canLogin = !state.busy && state.url.isNotBlank() && state.password.isNotBlank()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.GraphicEq, null, Modifier.padding(10.dp).size(25.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Text("Any Listen", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.connect_headline), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.connect_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = state.url, onValueChange = onUrl, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server URL" },
                        label = { Text(stringResource(R.string.connect_url)) }, singleLine = true,
                        placeholder = { Text("https://music.example.com") },
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    OutlinedTextField(value = state.password, onValueChange = onPassword, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password" },
                        label = { Text(stringResource(R.string.connect_password)) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                        } },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canLogin) { keyboard?.hide(); onLogin() } }))
                    state.error?.let { Notice(it, error = true) }
                    state.helloOk?.let { Notice(stringResource(if (it) R.string.hello_ok else R.string.hello_failed), error = !it) }
                    Button(onClick = { keyboard?.hide(); onLogin() }, enabled = canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.connect_action))
                    }
                    TextButton(onClick = { keyboard?.hide(); onTest() }, enabled = !state.busy && state.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connect_test))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.VerifiedUser, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.connect_security), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
