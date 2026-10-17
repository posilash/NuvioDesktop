package com.nuvio.app.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class CalendarView { Month, Agenda }

private enum class EpisodeStatus { Watched, New }

private val LocalEpisodeStatus = compositionLocalOf<(CalendarEpisode) -> EpisodeStatus?> { { null } }

private val WatchedBg = Color(0xFF97C459)
private val WatchedFg = Color(0xFF173404)

private val TextStrong = Color(0xFFF4F5F7)
private val TextSoft = Color(0xFFB7BDC4)
private val CardFill = Color(0xFF1B1F24)
private val CellFill = Color(0xFF171A1E)
private val Hairline = Color(0xFF2A3036)
private val PremiereBg = Color(0xFFFAC775)
private val PremiereFg = Color(0xFF412402)
private val FinaleBg = Color(0xFFED93B1)
private val FinaleFg = Color(0xFF4B1528)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onShowClick: (CalendarEpisode) -> Unit = {},
) {
    val state by CalendarRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { CalendarRepository.ensureFresh() }

    var nowMs by remember { mutableStateOf(EpisodeReleaseDatePlatform.nowEpochMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowMs = EpisodeReleaseDatePlatform.nowEpochMs()
        }
    }

    val todayIso = state.todayIso.ifEmpty { CurrentDateProvider.todayIsoDate() }
    var viewName by rememberSaveable { mutableStateOf(CalendarView.Month.name) }
    val view = CalendarView.valueOf(viewName)
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedDate by rememberSaveable { mutableStateOf(todayIso) }
    val month = YearMonth.of(todayIso).plus(monthOffset)

    val byDate = remember(state.episodes) { state.episodes.groupBy { it.date } }
    val upcoming = remember(state.episodes, nowMs, todayIso) {
        state.episodes.filter { ep ->
            ep.date >= todayIso && (ep.airEpochMs == null || ep.airEpochMs > nowMs - AIRING_GRACE_MS)
        }
    }

    val watchedState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val statusOf: (CalendarEpisode) -> EpisodeStatus? = remember(state.episodes, watchedState, nowMs, todayIso) {
        val cache = HashMap<CalendarEpisode, EpisodeStatus?>()
        val lambda: (CalendarEpisode) -> EpisodeStatus? = { ep ->
            if (ep in cache) cache[ep] else episodeStatus(ep, nowMs, todayIso).also { cache[ep] = it }
        }
        lambda
    }

    // Nothing above this screen sets a content color, so unstyled text drew
    // in the default black: the title, month and show names all vanished.
    CompositionLocalProvider(LocalContentColor provides TextStrong, LocalEpisodeStatus provides statusOf) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 40.dp,
                end = 40.dp,
                top = (topChromePadding ?: 0.dp) + 8.dp,
                bottom = 56.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item(key = "header") {
                CalendarHeader(
                    view = view,
                    monthLabel = month.label,
                    onView = { viewName = it.name },
                    onPrevMonth = { monthOffset-- },
                    onNextMonth = { monthOffset++ },
                    onRefresh = { CalendarRepository.refresh() },
                )
            }

            if (state.isLoading && state.hasLoaded) {
                item(key = "refreshing") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }

            if (state.isLoading && !state.hasLoaded) {
                item(key = "loading") { CalendarLoading(state.loadedShows, state.totalShows) }
                return@LazyColumn
            }
            if (state.hasLoaded && state.totalShows == 0) {
                item(key = "empty") { CalendarEmpty() }
                return@LazyColumn
            }

            upcoming.firstOrNull()?.let { next ->
                val rest = upcoming.drop(1)
                val sameDay = rest.filter { it.date == next.date }
                item(key = "hero") {
                    CalendarHero(
                        next = next,
                        side = sameDay.ifEmpty { rest }.take(HERO_SIDE_ITEMS),
                        sideIsSameDay = sameDay.isNotEmpty(),
                        todayIso = todayIso,
                        nowMs = nowMs,
                        onClick = onShowClick,
                    )
                }
            }

            when (view) {
                CalendarView.Month -> {
                    item(key = "grid-${month.label}") {
                        MonthGrid(
                            month = month,
                            byDate = byDate,
                            todayIso = todayIso,
                            selectedDate = selectedDate,
                            onSelect = { selectedDate = it },
                        )
                    }
                    item(key = "day-$selectedDate") {
                        DayPanel(
                            date = selectedDate,
                            todayIso = todayIso,
                            episodes = byDate[selectedDate].orEmpty(),
                            onClick = onShowClick,
                        )
                    }
                }

                CalendarView.Agenda -> {
                    val days = upcoming.groupBy { it.date }.toList()
                    if (days.isEmpty()) {
                        item(key = "agenda-empty") { Muted(stringResource(Res.string.calendar_nothing_upcoming)) }
                    }
                    days.forEach { (date, episodes) ->
                        item(key = "agenda-$date") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                DayHeading(dayHeading(date, todayIso), isToday = date == todayIso)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    episodes.forEach { AgendaCard(it, onShowClick) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarHeader(
    view: CalendarView,
    monthLabel: String,
    onView: (CalendarView) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.calendar_title),
            color = TextStrong,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(24.dp))
        if (view == CalendarView.Month) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = TextStrong)
            }
            Text(
                monthLabel,
                color = TextStrong,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.widthIn(min = 160.dp),
            )
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextStrong)
            }
        }
        Spacer(Modifier.weight(1f))
        ViewSwitch(view, onView)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.calendar_refresh), tint = TextSoft)
        }
    }
}

@Composable
private fun ViewSwitch(view: CalendarView, onView: (CalendarView) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(CardFill)
            .border(1.dp, Hairline, shape)
            .padding(3.dp),
    ) {
        listOf(
            CalendarView.Month to Res.string.calendar_view_month,
            CalendarView.Agenda to Res.string.calendar_view_agenda,
        ).forEach { (option, label) ->
            val selected = option == view
            Text(
                stringResource(label),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else TextSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onView(option) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun CalendarHero(
    next: CalendarEpisode,
    side: List<CalendarEpisode>,
    sideIsSameDay: Boolean,
    todayIso: String,
    nowMs: Long,
    onClick: (CalendarEpisode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(HeroHeight),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1.75f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(CardFill)
                .clickable { onClick(next) },
        ) {
            NuvioAsyncImage(
                model = next.backdrop ?: next.still ?: next.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.78f),
                        0.55f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(stringResource(Res.string.calendar_up_next), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    Text(
                        listOfNotNull(dayLabel(next.date, todayIso), localAirTime(next), countdown(next, nowMs, todayIso))
                            .joinToString(" · "),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (next.logo != null) {
                    NuvioAsyncImage(
                        model = next.logo,
                        contentDescription = next.showName,
                        modifier = Modifier.height(72.dp).widthIn(max = 360.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        next.showName,
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    listOfNotNull(episodeCode(next).ifEmpty { null }, next.title).joinToString(" · "),
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { EpisodeBadges(next) }
            }
        }
        if (side.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardFill)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(if (sideIsSameDay) Res.string.calendar_also_today else Res.string.calendar_coming_up),
                    color = TextSoft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                side.forEach { episode ->
                    EpisodeRow(episode, todayIso, showDay = !sideIsSameDay, posterWidth = 44.dp, onClick = onClick)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    byDate: Map<String, List<CalendarEpisode>>,
    todayIso: String,
    selectedDate: String,
    onSelect: (String) -> Unit,
) {
    val todayDay = isoEpochDay(todayIso)
    val leading = weekdayMondayFirst(month.firstDay)
    val rows = (leading + month.length + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeekdayNames.forEach { name ->
                Text(
                    name,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    color = TextSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        for (row in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 7) {
                    val index = row * 7 + col - leading
                    if (index < 0 || index >= month.length) {
                        Spacer(Modifier.weight(1f).height(DayCellHeight))
                    } else {
                        val epochDay = month.firstDay + index
                        val iso = epochDayToIso(epochDay)
                        DayCell(
                            day = index + 1,
                            episodes = byDate[iso].orEmpty(),
                            isToday = epochDay == todayDay,
                            isPast = epochDay < todayDay,
                            isSelected = iso == selectedDate,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(iso) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    episodes: List<CalendarEpisode>,
    isToday: Boolean,
    isPast: Boolean,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = MaterialTheme.colorScheme.primary
    val ring = when {
        isToday -> accent
        isSelected -> TextStrong
        else -> Hairline
    }
    val shows = episodes.distinctBy { it.showId }
    Column(
        modifier = modifier
            .height(DayCellHeight)
            .alpha(if (isPast && !isSelected) 0.55f else 1f)
            .clip(shape)
            .background(if (isToday) accent.copy(alpha = 0.16f) else CellFill)
            .border(if (isToday || isSelected) 2.dp else 1.dp, ring, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // As many side by side as the cell's width allows; past that, a
            // fanned stack, so a busy day still shows its shows at a glance.
            val width = maxWidth
            val sideBySide = ((width + PosterGap) / (MinPosterWidth + PosterGap)).toInt().coerceIn(1, MAX_SIDE_BY_SIDE)
            val posterWidth = minOf((width - PosterGap * (sideBySide - 1)) / sideBySide, MaxPosterWidth)
            val stacked = shows.size > sideBySide
            val shown = if (stacked) minOf(shows.size, MAX_STACKED) else shows.size
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        day.toString(),
                        color = if (isToday) accent else TextStrong,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (shows.size > shown) {
                        Text("+${shows.size - shown}", color = TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (stacked) {
                    val step = (width - posterWidth) / (shown - 1)
                    Box(Modifier.fillMaxWidth().height(posterWidth * 1.5f)) {
                        shows.take(shown).forEachIndexed { index, episode ->
                            CellPoster(episode, posterWidth, Modifier.offset(x = step * index))
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(PosterGap)) {
                        shows.forEach { CellPoster(it, posterWidth) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CellPoster(episode: CalendarEpisode, width: Dp, modifier: Modifier = Modifier) {
    val status = LocalEpisodeStatus.current(episode)
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .background(CardFill)
            .border(1.dp, CellFill, RoundedCornerShape(6.dp)),
    ) {
        NuvioAsyncImage(
            model = episode.poster,
            contentDescription = episode.showName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        when (status) {
            EpisodeStatus.Watched -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                Box(
                    Modifier.align(Alignment.Center).size(22.dp).clip(CircleShape).background(WatchedBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = WatchedFg, modifier = Modifier.size(15.dp))
                }
            }
            EpisodeStatus.New -> Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(9.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            null -> Unit
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPanel(
    date: String,
    todayIso: String,
    episodes: List<CalendarEpisode>,
    onClick: (CalendarEpisode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DayHeading(dayHeading(date, todayIso), isToday = date == todayIso)
        if (episodes.isEmpty()) {
            Muted(stringResource(Res.string.calendar_nothing_this_day))
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                episodes.forEach { episode ->
                    Box(
                        Modifier
                            .width(340.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardFill)
                            .padding(10.dp),
                    ) {
                        EpisodeRow(episode, todayIso, showDay = false, posterWidth = 60.dp, onClick = onClick)
                    }
                }
            }
        }
    }
}

/** Poster, show, episode code with time, and the episode's own title. */
@Composable
private fun EpisodeRow(
    episode: CalendarEpisode,
    todayIso: String,
    showDay: Boolean,
    posterWidth: Dp,
    onClick: (CalendarEpisode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick(episode) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioAsyncImage(
            model = episode.poster,
            contentDescription = null,
            modifier = Modifier
                .width(posterWidth)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(CellFill),
            contentScale = ContentScale.Crop,
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                episode.showName,
                color = TextStrong,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    episodeCode(episode).ifEmpty { null },
                    if (showDay) dayLabel(episode.date, todayIso) else null,
                    localAirTime(episode),
                ).joinToString(" · "),
                color = TextSoft,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            episode.title?.let {
                Text(
                    it,
                    color = TextSoft,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EpisodeBadges(episode)
                when (LocalEpisodeStatus.current(episode)) {
                    EpisodeStatus.Watched -> Chip(stringResource(Res.string.calendar_watched), WatchedBg, WatchedFg)
                    EpisodeStatus.New -> Chip(
                        stringResource(Res.string.calendar_new),
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.onPrimary,
                    )
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun AgendaCard(episode: CalendarEpisode, onClick: (CalendarEpisode) -> Unit) {
    Box(
        modifier = Modifier
            .width(360.dp)
            .aspectRatio(16f / 8f)
            .clip(RoundedCornerShape(14.dp))
            .background(CardFill)
            .clickable { onClick(episode) },
    ) {
        NuvioAsyncImage(
            model = episode.still ?: episode.backdrop ?: episode.poster,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.35f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.85f),
                ),
            ),
        )
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { EpisodeBadges(episode) }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                episode.showName,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(episodeCode(episode).ifEmpty { null }, localAirTime(episode), episode.title)
                    .joinToString(" · "),
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EpisodeBadges(episode: CalendarEpisode) {
    if (episode.batchSize > 1) {
        Chip(
            stringResource(Res.string.calendar_batch, episode.batchSize),
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
    }
    when (episode.kind) {
        CalendarEpisodeKind.Premiere -> Chip(
            stringResource(if (episode.season == 1) Res.string.calendar_series_premiere else Res.string.calendar_season_premiere),
            PremiereBg,
            PremiereFg,
        )
        CalendarEpisodeKind.Finale -> Chip(stringResource(Res.string.calendar_season_finale), FinaleBg, FinaleFg)
        CalendarEpisodeKind.Regular -> Unit
    }
}

@Composable
private fun Chip(text: String, background: Color, foreground: Color) {
    Text(
        text,
        color = foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun DayHeading(text: String, isToday: Boolean) {
    Text(
        text,
        color = if (isToday) MaterialTheme.colorScheme.primary else TextStrong,
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, color = TextSoft, fontSize = 14.sp)
}

@Composable
private fun CalendarLoading(loaded: Int, total: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Muted(stringResource(Res.string.calendar_loading, loaded, total))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { loaded.toFloat() / total },
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
        }
    }
}

@Composable
private fun CalendarEmpty() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(Res.string.calendar_empty_title), color = TextStrong, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Muted(stringResource(Res.string.calendar_empty_body))
    }
}

@Composable
private fun dayLabel(date: String, todayIso: String): String =
    when (isoEpochDay(date) - isoEpochDay(todayIso)) {
        0L -> stringResource(Res.string.calendar_today)
        1L -> stringResource(Res.string.calendar_tomorrow)
        else -> dayHeading(date, todayIso)
    }

@Composable
private fun countdown(episode: CalendarEpisode, nowMs: Long, todayIso: String): String? {
    val at = episode.airEpochMs
    if (at == null) {
        val days = (isoEpochDay(episode.date) - isoEpochDay(todayIso)).toInt()
        return if (days >= 2) stringResource(Res.string.calendar_in_days, days) else null
    }
    val minutes = ((at - nowMs) / 60_000L).toInt()
    return when {
        minutes <= 0 -> stringResource(Res.string.calendar_airing_now)
        minutes < 60 -> stringResource(Res.string.calendar_in_minutes, minutes)
        minutes < 48 * 60 -> stringResource(Res.string.calendar_in_hours_minutes, minutes / 60, minutes % 60)
        else -> stringResource(Res.string.calendar_in_days_hours, minutes / (24 * 60), (minutes / 60) % 24)
    }
}

private val HeroHeight = 330.dp
private val DayCellHeight = 150.dp
private val PosterGap = 5.dp
private val MinPosterWidth = 40.dp
private val MaxPosterWidth = 72.dp
private const val MAX_SIDE_BY_SIDE = 4
private const val MAX_STACKED = 6
private const val NEW_FOR_DAYS = 14
private const val HERO_SIDE_ITEMS = 3
private const val AIRING_GRACE_MS = 60L * 60 * 1000

/** Watched once marked; New if it aired within two weeks unwatched; nothing before it airs. */
private fun episodeStatus(episode: CalendarEpisode, nowMs: Long, todayIso: String): EpisodeStatus? {
    val aired = episode.airEpochMs?.let { it <= nowMs } ?: (episode.date <= todayIso)
    if (!aired) return null
    if (WatchedRepository.isWatched(episode.showId, episode.showType, episode.season, episode.episode)) {
        return EpisodeStatus.Watched
    }
    val daysAgo = isoEpochDay(todayIso) - isoEpochDay(episode.date)
    return if (daysAgo <= NEW_FOR_DAYS) EpisodeStatus.New else null
}
