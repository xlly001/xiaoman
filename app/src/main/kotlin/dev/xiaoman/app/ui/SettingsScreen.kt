package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.xiaoman.api.ModelRole
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.settings.ModelSettings
import dev.xiaoman.app.settings.SettingsForm
import dev.xiaoman.llm.ProbeResult
import kotlinx.coroutines.launch

private data class ModelTestState(
    val testing: Boolean = false,
    val result: ProbeResult? = null,
    val role: ModelRole? = null,
)

@Composable
fun SettingsScreen(graph: AppGraph) {
    val store = graph.settings
    var form by remember { mutableStateOf(SettingsForm.of(store.current)) }
    var saved by remember { mutableStateOf(false) }
    val parsed = form.toSettings()
    val scope = rememberCoroutineScope()
    var chatTest by remember { mutableStateOf(ModelTestState()) }
    var operatorTest by remember { mutableStateOf(ModelTestState()) }

    fun probe(role: ModelRole) {
        val model = (if (role == ModelRole.CHAT) form.chat else form.operator).toSettings()
        if (model == null) {
            val state = ModelTestState(result = ProbeResult.Failed(message = "invalid"), role = role)
            if (role == ModelRole.CHAT) chatTest = state else operatorTest = state
            return
        }
        val testing = ModelTestState(testing = true, role = role)
        if (role == ModelRole.CHAT) chatTest = testing else operatorTest = testing
        scope.launch {
            val result = graph.probeModel(model, role)
            val state = ModelTestState(result = result, role = role)
            if (role == ModelRole.CHAT) chatTest = state else operatorTest = state
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.settings_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsCard(title = stringResource(R.string.settings_chat_model)) {
            ModelFields(form.chat) {
                form = form.copy(chat = it)
                saved = false
                chatTest = ModelTestState()
            }
            TestRow(
                state = chatTest,
                enabled = form.chat.model.isNotBlank() && form.chat.apiKey.isNotBlank(),
                onTest = { probe(ModelRole.CHAT) },
            )
        }

        SettingsCard(title = stringResource(R.string.settings_operator_model)) {
            ModelFields(form.operator) {
                form = form.copy(operator = it)
                saved = false
                operatorTest = ModelTestState()
            }
            TextButton(onClick = {
                form = form.copy(operator = form.operator.copy(baseUrl = form.chat.baseUrl, apiKey = form.chat.apiKey))
                saved = false
                operatorTest = ModelTestState()
            }) { Text(stringResource(R.string.settings_copy_chat)) }
            TestRow(
                state = operatorTest,
                enabled = form.operator.model.isNotBlank() && form.operator.apiKey.isNotBlank(),
                onTest = { probe(ModelRole.OPERATOR) },
            )
        }

        SettingsCard(title = stringResource(R.string.settings_agent)) {
            OutlinedTextField(
                value = form.maxSteps,
                onValueChange = {
                    form = form.copy(maxSteps = it)
                    saved = false
                },
                label = { Text(stringResource(R.string.settings_max_steps)) },
                isError = !form.maxStepsValid,
                supportingText = { if (!form.maxStepsValid) Text(stringResource(R.string.settings_invalid_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_learn), modifier = Modifier.weight(1f))
                Switch(checked = form.learnFromSuccess, onCheckedChange = {
                    form = form.copy(learnFromSuccess = it)
                    saved = false
                })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = parsed != null, onClick = {
                parsed?.let {
                    store.save(it)
                    saved = true
                }
            }, shape = RoundedCornerShape(18.dp)) {
                Text(stringResource(R.string.settings_save))
            }
            if (saved) {
                Text(
                    stringResource(R.string.settings_saved),
                    modifier = Modifier.padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SettingsCard(title = stringResource(R.string.settings_folders)) {
            FolderLine(stringResource(R.string.settings_folder_skills_label), graph.skillsDir.path)
            FolderLine(stringResource(R.string.settings_folder_memory_label), graph.memoryDir.path)
            FolderLine(stringResource(R.string.settings_folder_traces_label), graph.tracesDir.path)
            FolderLine(stringResource(R.string.settings_folder_chat_label), graph.externalRoot.resolve("chat").path)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun ModelFields(model: SettingsForm.ModelForm, onChange: (SettingsForm.ModelForm) -> Unit) {
    var showKey by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = model.baseUrl,
        onValueChange = { onChange(model.copy(baseUrl = it)) },
        label = { Text(stringResource(R.string.settings_base_url)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.model,
        onValueChange = { onChange(model.copy(model = it)) },
        label = { Text(stringResource(R.string.settings_model)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.apiKey,
        onValueChange = { onChange(model.copy(apiKey = it)) },
        label = { Text(stringResource(R.string.settings_api_key)) },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showKey) R.string.settings_hide_key else R.string.settings_show_key),
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model.temperature,
        onValueChange = { onChange(model.copy(temperature = it)) },
        label = { Text(stringResource(R.string.settings_temperature)) },
        isError = !model.temperatureValid,
        supportingText = { if (!model.temperatureValid) Text(stringResource(R.string.settings_invalid_number)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TestRow(state: ModelTestState, enabled: Boolean, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onTest, enabled = enabled && !state.testing, shape = RoundedCornerShape(16.dp)) {
            if (state.testing) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), strokeWidth = 2.dp)
            }
            Text(stringResource(if (state.testing) R.string.settings_testing else R.string.settings_test))
        }
        state.result?.let { result -> TestResultText(result, state.role) }
    }
}

@Composable
private fun TestResultText(result: ProbeResult, role: ModelRole?) {
    when (result) {
        is ProbeResult.Ok -> Text(
            stringResource(R.string.settings_test_ok, result.latencyMillis),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ProbeResult.Failed -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                diagnosis(result, role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            val detail = result.message.takeIf { it.isNotBlank() && it != "invalid" }
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun diagnosis(result: ProbeResult.Failed, role: ModelRole?): String = when {
    result.message == "invalid" -> stringResource(R.string.settings_test_invalid)
    result.statusCode == 401 || result.statusCode == 403 -> stringResource(R.string.settings_test_auth)
    result.statusCode == 404 -> stringResource(R.string.settings_test_not_found)
    result.statusCode == 429 || result.providerCode == "SetLimitExceeded" -> stringResource(R.string.settings_test_limit)
    result.statusCode == 400 && role == ModelRole.OPERATOR -> stringResource(R.string.settings_test_vision)
    result.message.contains("timeout", ignoreCase = true) ||
        result.message.contains("failed", ignoreCase = true) ||
        result.message.contains("Unable to resolve host", ignoreCase = true) -> stringResource(R.string.settings_test_network)
    else -> stringResource(
        R.string.settings_test_failed,
        listOfNotNull(result.statusCode?.toString(), result.providerCode).joinToString(" / ").ifEmpty { "?" },
    )
}

@Composable
private fun FolderLine(label: String, path: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
