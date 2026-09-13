package io.github.nutea.anylisten.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.nutea.anylisten.R
import io.github.nutea.anylisten.ui.ConnectUiState

@Composable
fun ConnectScreen(
    state: ConnectUiState,
    onUrl: (String) -> Unit,
    onPassword: (String) -> Unit,
    onTest: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = state.url,
            onValueChange = onUrl,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server URL" },
            label = { Text(stringResource(R.string.connect_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Password" },
            label = { Text(stringResource(R.string.connect_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        state.helloOk?.let { ok ->
            Text(
                if (ok) stringResource(R.string.hello_ok) else stringResource(R.string.hello_failed),
                color = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onTest, enabled = !state.busy && state.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_test))
        }
        Button(onClick = onLogin, enabled = !state.busy && state.url.isNotBlank() && state.password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connect_action))
        }
    }
}
