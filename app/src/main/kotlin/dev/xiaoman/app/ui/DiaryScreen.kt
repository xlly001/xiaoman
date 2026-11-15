package dev.xiaoman.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.diary.DiaryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One page of the diary: everything Xiaoman did on a single day. */
private data class DiaryDay(val date: LocalDate, val entries: List<DiaryEntry>)

private fun daysOf(entries: List<DiaryEntry>): List<DiaryDay> {
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        .toSortedMap(reverseOrder())
        .map { (date, list) -> DiaryDay(date, list.sortedByDescending { it.at }) }
}

/**
 * "小满" tab: a horizontally swipeable deck of daily cards. Each card lists the tasks Xiaoman
 * finished and the memories it wrote on that day.
 */
@Composable
fun DiaryScreen(graph: AppGraph) {
    val entries by graph.diary.entries.collectAsStateWithLifecycle()
    val days = remember(entries) { daysOf(entries) }

    Column(Modifier.fillMaxSize().padding(bottom = DockSpace)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.diary_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.diary_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (days.isEmpty()) {
            EmptyDiary(Modifier.fillMaxWidth().padding(18.dp))
        } else {
            val pager = rememberPagerState(pageCount = { days.size })
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 12.dp,
            ) { page ->
                DiaryDayCard(days[page])
            }
            if (days.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(days.size) { index ->
                        val active = index == pager.currentPage
                        Box(
                            Modifier.padding(horizontal = 3.dp)
                                .size(if (active) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun EmptyDiary(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.diary_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.diary_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiaryDayCard(day: DiaryDay) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text(dayLabel(day.date), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.diary_count, day.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 4.dp),
            ) {
                items(day.entries, key = { it.id }) { entry -> DiaryRow(entry) }
            }
        }
    }
}

@Composable
private fun DiaryRow(entry: DiaryEntry) {
    val color = kindColor(entry.kind)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color),
        )
        Column(
            Modifier.weight(1f).padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(kindLabel(entry.kind)),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
                Text(
                    timeOf(entry.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (entry.title.isNotBlank()) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> stringResource(R.string.diary_today)
        today.minusDays(1) -> stringResource(R.string.diary_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
}

private fun timeOf(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun kindColor(kind: String): Color = when (kind) {
    "task_done" -> MaterialTheme.colorScheme.primary
    "task_failed" -> MaterialTheme.colorScheme.error
    "memory" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary
}

private fun kindLabel(kind: String): Int = when (kind) {
    "task_done" -> R.string.diary_kind_task_done
    "task_failed" -> R.string.diary_kind_task_failed
    "memory" -> R.string.diary_kind_memory
    else -> R.string.diary_kind_note
}
