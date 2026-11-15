package dev.xiaoman.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.diary.DiaryEntry
import dev.xiaoman.app.diary.DiarySummary
import dev.xiaoman.app.diary.ModelNotConfiguredException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One page of the diary: everything Xiaoman did on a single day. */
private data class DiaryDay(val date: LocalDate, val entries: List<DiaryEntry>)

private val weekdayLabel = DateTimeFormatter.ofPattern("EEE")
private val dateKey = DateTimeFormatter.ISO_LOCAL_DATE

private fun daysOf(entries: List<DiaryEntry>): List<DiaryDay> {
    val zone = ZoneId.systemDefault()
    return entries
        .groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        .toSortedMap(reverseOrder())
        .map { (date, list) -> DiaryDay(date, list.sortedByDescending { it.at }) }
}

/** The deck and a single day are two faces of the same tab; switching between them is a nudge. */
private const val DetailInMillis = 280
private const val DetailOutMillis = 240

/**
 * "小满" tab: a horizontally swipeable deck of daily cards. Each card ends with a
 * model-generated summary; tapping the card opens that day as a full-screen detail
 * page with a back arrow in the top-left corner.
 */
@Composable
fun DiaryScreen(graph: AppGraph) {
    val entries by graph.diary.entries.collectAsStateWithLifecycle()
    val summaries by graph.diary.summaries.collectAsStateWithLifecycle()
    val days = remember(entries) { daysOf(entries) }
    val scope = rememberCoroutineScope()
    /** Non-null while one day is open full-screen; kept across rotation. */
    var openDay by rememberSaveable { mutableStateOf<String?>(null) }
    // Generation state is kept per day so paging or opening details never cancels a request.
    var loading by remember { mutableStateOf(emptySet<String>()) }
    var errors by remember { mutableStateOf(emptyMap<String, Throwable>()) }
    var attempted by remember { mutableStateOf(emptySet<String>()) }
    val pager = rememberPagerState(pageCount = { days.size })
    val dayKey: (DiaryDay) -> String = { it.date.format(dateKey) }
    // This tab keeps running in the background (TabPager), so nothing here may act on the user's
    // behalf while another page is on screen.
    val tabActive = LocalTabActive.current

    /** Calls the model API once per (day, entry count); [force] is the user's refresh button. */
    fun generate(day: DiaryDay, force: Boolean) {
        val date = dayKey(day)
        val key = "$date:${day.entries.size}"
        if (!force && (key in attempted || date in loading)) return
        attempted = attempted + key
        loading = loading + date
        errors = errors - date
        scope.launch {
            val result = graph.generateDiarySummary(date, day.entries)
            loading = loading - date
            result.onFailure { errors = errors + (date to it) }
        }
    }

    val detailDay = openDay?.let { key -> days.firstOrNull { dayKey(it) == key } }
    val visibleDay = detailDay ?: days.getOrNull(pager.currentPage)
    // The hardware back button closes the full-screen day view instead of leaving the app.
    BackHandler(enabled = openDay != null && tabActive) { openDay = null }
    // Auto-generate for the visible day when the summary is missing or stale. The
    // persisted summary changes the key too, so this settles after one API call. Background
    // copies of this page stay silent; the request fires the moment the tab is shown.
    LaunchedEffect(visibleDay?.date, visibleDay?.entries?.size, summaries, tabActive) {
        if (!tabActive) return@LaunchedEffect
        val day = visibleDay ?: return@LaunchedEffect
        val saved = summaries[dayKey(day)]
        if (saved == null || saved.entryCount != day.entries.size) generate(day, force = false)
    }

    Column(Modifier.fillMaxSize().padding(bottom = DockSpace)) {
        // Opening a day slides the detail up over the deck; closing reverses it. Because the
        // transition is interruption-aware, a fast back-then-open keeps the motion continuous.
        AnimatedContent(
            targetState = openDay,
            transitionSpec = {
                if (targetState != null) {
                    (
                        slideInVertically(tween(DetailInMillis, easing = FastOutSlowInEasing)) { it / 6 } +
                            fadeIn(tween(DetailInMillis - 60))
                        ) togetherWith fadeOut(tween(140))
                } else {
                    fadeIn(tween(120)) togetherWith
                        (
                            slideOutVertically(tween(DetailOutMillis, easing = FastOutSlowInEasing)) { it / 6 } +
                                fadeOut(tween(DetailOutMillis - 40))
                            )
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = "diary-open",
        ) { opened ->
            val day = opened?.let { key -> days.firstOrNull { dayKey(it) == key } }
            if (day != null) {
                val date = dayKey(day)
                DiaryDayDetail(
                    day = day,
                    summary = summaries[date],
                    loading = date in loading,
                    error = errors[date],
                    onRefresh = { generate(day, force = true) },
                    onBack = { openDay = null },
                )
            } else {
                DiaryDeck(
                    days = days,
                    pager = pager,
                    summaries = summaries,
                    loading = loading,
                    errors = errors,
                    onRefresh = { generate(it, force = true) },
                    onOpenDetail = { openDay = it },
                )
            }
        }
    }
}

/** The card deck itself: persona header, swipeable day cards and the page dots. */
@Composable
private fun DiaryDeck(
    days: List<DiaryDay>,
    pager: PagerState,
    summaries: Map<String, DiarySummary>,
    loading: Set<String>,
    errors: Map<String, Throwable>,
    onRefresh: (DiaryDay) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val dayKey: (DiaryDay) -> String = { it.date.format(dateKey) }
    Column(Modifier.fillMaxSize()) {
        DiaryHeader()
        if (days.isEmpty()) {
            EmptyDiary(Modifier.fillMaxWidth().padding(18.dp))
        } else {
            HorizontalPager(
                state = pager,
                // The card now fills the space below the header: longer than the old fixed
                // 330.dp, leaving room for the summary block pinned to its bottom.
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 18.dp),
                pageSpacing = 12.dp,
            ) { page ->
                val day = days[page]
                val date = dayKey(day)
                DiaryDayCard(
                    day = day,
                    summary = summaries[date],
                    loading = date in loading,
                    error = errors[date],
                    onRefresh = { onRefresh(day) },
                    onOpenDetail = { onOpenDetail(date) },
                )
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

/** Open Muse persona header: round avatar, name and a short companion line. */
@Composable
private fun DiaryHeader() {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.avatar_xiaoman),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), CircleShape),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.diary_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** Full-screen view of one diary day: back arrow top-left, complete summary and entries. */
@Composable
private fun DiaryDayDetail(
    day: DiaryDay,
    summary: DiarySummary?,
    loading: Boolean,
    error: Throwable?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.diary_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    "${dayLabel(day.date)} · ${stringResource(R.string.diary_count, day.entries.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        DiarySummaryBlock(summary, loading, error, onRefresh, expanded = true)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.diary_detail_entries),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(day.entries, key = { it.id }) { entry -> DiaryRow(entry, expanded = true) }
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
private fun DiaryDayCard(
    day: DiaryDay,
    summary: DiarySummary?,
    loading: Boolean,
    error: Throwable?,
    onRefresh: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val openLabel = stringResource(R.string.diary_open_detail)
    Surface(
        modifier = Modifier.fillMaxSize().clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenDetail),
        shape = RoundedCornerShape(28.dp),
        // Semi-transparent so the blue-to-peach wash shows through, like the reference deck.
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            weekdayLabel.format(day.date).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            day.date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    dayLabel(day.date),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }
            Text(
                stringResource(R.string.diary_count, day.entries.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
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
                items(day.entries, key = { it.id }) { entry ->
                    DiaryRow(entry, onClick = onOpenDetail, openLabel = openLabel)
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            DiarySummaryBlock(summary, loading, error, onRefresh)
        }
    }
}

/** The extra length at the bottom of each card: a recap written by the model API. */
@Composable
private fun DiarySummaryBlock(
    summary: DiarySummary?,
    loading: Boolean,
    error: Throwable?,
    onRefresh: () -> Unit,
    expanded: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.diary_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(
                    stringResource(
                        when {
                            error != null -> R.string.diary_summary_retry
                            summary == null -> R.string.diary_summary_generate
                            else -> R.string.diary_summary_refresh
                        },
                    ),
                )
            }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.diary_summary_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else when {
            error is ModelNotConfiguredException -> Text(
                stringResource(R.string.diary_summary_unconfigured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error != null -> Text(
                stringResource(R.string.diary_summary_error, error.message ?: error.javaClass.simpleName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            summary != null -> {
                Text(
                    summary.text,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.diary_summary_generated, timeOf(summary.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            else -> Text(
                stringResource(R.string.diary_summary_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun DiaryRow(
    entry: DiaryEntry,
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    openLabel: String = "",
) {
    val color = kindColor(entry.kind)
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.Top,
    ) {
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
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
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
