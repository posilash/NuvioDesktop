package com.nuvio.app.features.calendar

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.parseReleaseDateToEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Serializable
enum class CalendarEpisodeKind { Regular, Premiere, Finale }

/** One airing on the calendar. A same-day drop of a whole batch is one event. */
@Serializable
data class CalendarEpisode(
    val showId: String,
    val showType: String,
    val showName: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val still: String?,
    /** Local calendar date, yyyy-MM-dd. */
    val date: String,
    /** The exact moment, when the provider gave a time; date-only releases have none. */
    val airEpochMs: Long?,
    val kind: CalendarEpisodeKind,
    /** More than 1 when several episodes of the show drop on the same day. */
    val batchSize: Int = 1,
) {
    val epochDay: Long get() = isoEpochDay(date)
}

data class CalendarUiState(
    val isLoading: Boolean = false,
    val loadedShows: Int = 0,
    val totalShows: Int = 0,
    val episodes: List<CalendarEpisode> = emptyList(),
    val todayIso: String = "",
    val hasLoaded: Boolean = false,
)

/**
 * Upcoming and recent episodes of the series in the library and in Continue
 * Watching, from the same addon metadata the rest of the app shows.
 */
object CalendarRepository {
    private val log = Logger.withTag("CalendarRepository")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    private var buildJob: Job? = null
    private var builtForShows: Set<ShowKey> = emptySet()
    private var builtAtEpochMs = 0L
    private var loadedProfileId: Int? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ShowKey(val type: String, val id: String)

    @Serializable
    private data class StoredCalendar(
        val builtAtEpochMs: Long,
        val shows: List<ShowKey>,
        val episodes: List<CalendarEpisode>,
    )

    /**
     * Show the saved calendar at once, then rebuild in the background if it is
     * stale or the followed shows changed. The saved one stays on screen while
     * that runs.
     */
    fun ensureFresh() {
        loadSaved()
        val shows = followedShows()
        val now = EpisodeReleaseDatePlatform.nowEpochMs()
        val stale = now - builtAtEpochMs > STALE_AFTER_MS
        if (buildJob?.isActive == true) return
        if (!stale && shows.keys == builtForShows && _uiState.value.hasLoaded) return
        build(shows)
    }

    private fun storageKey(profileId: Int) = ProfileScopedKey.of(STORAGE_KEY, profileId)

    private fun loadSaved() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        buildJob?.cancel()
        loadedProfileId = profileId
        builtForShows = emptySet()
        builtAtEpochMs = 0L
        _uiState.value = CalendarUiState()
        val saved = CalendarStorage.loadPayload(storageKey(profileId))
            ?.let { runCatching { json.decodeFromString<StoredCalendar>(it) }.getOrNull() }
            ?: return
        builtForShows = saved.shows.toSet()
        builtAtEpochMs = saved.builtAtEpochMs
        _uiState.value = CalendarUiState(
            episodes = saved.episodes,
            loadedShows = saved.shows.size,
            totalShows = saved.shows.size,
            todayIso = CurrentDateProvider.todayIsoDate(),
            hasLoaded = true,
        )
    }

    fun refresh() {
        buildJob?.cancel()
        build(followedShows())
    }

    private fun followedShows(): Map<ShowKey, Pair<String, String?>> {
        LibraryRepository.ensureLoaded()
        val shows = linkedMapOf<ShowKey, Pair<String, String?>>()
        LibraryRepository.uiState.value.items
            .filter { it.type.equals("series", ignoreCase = true) || it.type.equals("tv", ignoreCase = true) }
            .forEach { shows[ShowKey("series", it.id)] = it.name to it.poster }
        runCatching { WatchProgressRepository.continueWatching() }
            .getOrDefault(emptyList())
            .filter { it.parentMetaType.equals("series", ignoreCase = true) }
            .forEach { entry ->
                shows.getOrPut(ShowKey("series", entry.parentMetaId)) { entry.title to entry.poster }
            }
        return shows
    }

    private fun build(shows: Map<ShowKey, Pair<String, String?>>) {
        val today = CurrentDateProvider.todayIsoDate()
        val profileId = loadedProfileId ?: ProfileRepository.activeProfileId
        _uiState.update {
            it.copy(isLoading = true, loadedShows = 0, totalShows = shows.size, todayIso = today)
        }
        buildJob = scope.launch {
            val todayDay = isoEpochDay(today)
            val window = (todayDay - PAST_DAYS)..(todayDay + FUTURE_DAYS)
            val semaphore = Semaphore(FETCH_CONCURRENCY)
            val episodes = coroutineScope {
                shows.keys.map { key ->
                    async {
                        semaphore.withPermit {
                            val meta = try {
                                MetaDetailsRepository.fetch(key.type, key.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.w { "metadata for ${key.id} failed: ${e.message}" }
                                null
                            }
                            val events = meta?.let { calendarEpisodesOf(it, key, window) }.orEmpty()
                            _uiState.update { it.copy(loadedShows = it.loadedShows + 1) }
                            events
                        }
                    }
                }.awaitAll().flatten()
            }
            val sorted = episodes.sortedWith(
                compareBy<CalendarEpisode> { it.date }
                    .thenBy { it.airEpochMs ?: Long.MAX_VALUE }
                    .thenBy { it.showName.lowercase() },
            )
            if (ProfileRepository.activeProfileId != profileId) return@launch
            builtForShows = shows.keys
            builtAtEpochMs = EpisodeReleaseDatePlatform.nowEpochMs()
            runCatching {
                CalendarStorage.savePayload(
                    storageKey(profileId),
                    json.encodeToString(StoredCalendar.serializer(), StoredCalendar(builtAtEpochMs, shows.keys.toList(), sorted)),
                )
            }.onFailure { log.w { "saving the calendar failed: ${it.message}" } }
            _uiState.value = CalendarUiState(
                isLoading = false,
                loadedShows = shows.size,
                totalShows = shows.size,
                episodes = sorted,
                todayIso = today,
                hasLoaded = true,
            )
        }
    }

    private fun calendarEpisodesOf(
        meta: MetaDetails,
        key: ShowKey,
        window: LongRange,
    ): List<CalendarEpisode> {
        val numbered = meta.videos.filter { (it.season ?: 0) > 0 && it.episode != null }
        if (numbered.isEmpty()) return emptyList()
        val lastEpisodeOfSeason = numbered.groupBy { it.season }.mapValues { (_, v) -> v.maxOf { it.episode ?: 0 } }
        val lastSeason = numbered.maxOf { it.season ?: 0 }
        val ended = meta.status?.contains("ended", ignoreCase = true) == true ||
            meta.status?.contains("cancel", ignoreCase = true) == true

        val dated = numbered.mapNotNull { video ->
            val date = parseEpisodeReleaseLocalDate(video.released) ?: return@mapNotNull null
            if (isoEpochDay(date) !in window) return@mapNotNull null
            val season = video.season ?: 0
            val episode = video.episode ?: 0
            // A season's last listed episode is only its finale once the
            // season is closed: a later season exists, or the show ended.
            // Otherwise it is merely the last one announced so far.
            val isFinale = episode > 1 && episode == lastEpisodeOfSeason[season] &&
                (season < lastSeason || ended)
            CalendarEpisode(
                showId = key.id,
                showType = key.type,
                showName = meta.name,
                poster = meta.poster,
                backdrop = meta.background,
                logo = meta.logo,
                season = season,
                episode = episode,
                title = video.title.takeIf { it.isNotBlank() },
                still = video.thumbnail,
                date = date,
                airEpochMs = video.released?.takeIf { it.contains('T') }?.let(::parseReleaseDateToEpochMs),
                kind = when {
                    episode == 1 -> CalendarEpisodeKind.Premiere
                    isFinale -> CalendarEpisodeKind.Finale
                    else -> CalendarEpisodeKind.Regular
                },
            )
        }
        // A whole batch on one day reads as one drop, not a wall of cards.
        return dated.groupBy { it.date }.flatMap { (_, sameDay) ->
            if (sameDay.size >= BATCH_MIN) {
                val first = sameDay.minWith(compareBy({ it.season }, { it.episode }))
                listOf(
                    first.copy(
                        batchSize = sameDay.size,
                        kind = if (sameDay.any { it.kind == CalendarEpisodeKind.Premiere }) {
                            CalendarEpisodeKind.Premiere
                        } else {
                            first.kind
                        },
                    ),
                )
            } else {
                sameDay
            }
        }
    }

    private const val FETCH_CONCURRENCY = 4
    private const val PAST_DAYS = 62L
    private const val FUTURE_DAYS = 400L
    private const val BATCH_MIN = 3
    private const val STORAGE_KEY = "calendar_payload"
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000
}
