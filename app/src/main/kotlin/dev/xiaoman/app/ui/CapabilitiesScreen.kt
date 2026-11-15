package dev.xiaoman.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R

/** One place for both phone skills and external MCP services. */
@Composable
fun CapabilitiesScreen(graph: AppGraph, onAuthorSkill: (String) -> Unit) {
    var services by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.nav_capabilities), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !services,
                    onClick = { services = false },
                    label = { Text(stringResource(R.string.capabilities_skills)) },
                )
                FilterChip(
                    selected = services,
                    onClick = { services = true },
                    label = { Text(stringResource(R.string.capabilities_services)) },
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (services) {
                McpScreen(graph, showTitle = false)
            } else {
                SkillsScreen(graph, onAuthorSkill, showTitle = false)
            }
        }
    }
}
