package com.musicdl.android

import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import com.musicdl.core.model.SongInfo

/** One genre in the personalization model. [query] is what the wave searches upstream. */
data class Genre(
    val id: String,
    val label: String,
    val query: String,
    val keywords: List<String>,
)

object Genres {
    /** Shared vocabulary for provider genres and user taste controls. */
    val all = listOf(
        Genre(
            "electronic", "Электроника", "electronic mix",
            listOf("electronic", "electro", "edm", "house", "techno", "trance", "dubstep", "synth", "club", "электрон", "хаус", "техно", "транс"),
        ),
        Genre(
            "lofi", "Лоу-фай", "lofi chill",
            listOf("lofi", "lo-fi", "chillhop", "chill", "ambient", "study", "лоу-фай", "лоуфай", "эмбиент", "чилл"),
        ),
        Genre(
            "hiphop", "Хип-хоп", "hip hop",
            listOf("hip hop", "hip-hop", "rap", "trap", "drill", "хип-хоп", "хип хоп", "рэп", "трэп"),
        ),
        Genre(
            "rock", "Рок", "rock",
            listOf("rock", "punk", "grunge", "alt", "рок", "панк", "альтернатив"),
        ),
        Genre(
            "pop", "Поп", "pop hits",
            listOf("pop", "поп", "хит", "hits"),
        ),
        Genre(
            "metal", "Метал", "metal",
            listOf("metal", "metalcore", "deathcore", "метал"),
        ),
        Genre(
            "indie", "Инди", "indie",
            listOf("indie", "инди"),
        ),
        Genre(
            "jazz", "Джаз", "jazz",
            listOf("jazz", "bebop", "swing", "джаз", "свинг"),
        ),
        Genre(
            "classical", "Классика", "classical",
            listOf("classical", "symphony", "sonata", "классик", "симфон", "сонат", "шопен", "бах", "моцарт"),
        ),
        Genre(
            "rnb", "Ритм-н-блюз", "rnb soul",
            listOf("rnb", "r&b", "soul", "funky", "соул", "фанк", "ритм-н-блюз"),
        ),
        Genre(
            "reggae", "Регги", "reggae",
            listOf("reggae", "ska", "dancehall", "регги"),
        ),
        Genre(
            "folk", "Фолк", "folk acoustic",
            listOf("folk", "acoustic", "country", "фолк", "акустик", "кантри", "народн"),
        ),
        Genre(
            "soundtrack", "Саундтрек", "soundtrack ost",
            listOf("ost", "soundtrack", "score", "theme", "саундтрек", "музыка из"),
        ),
        Genre(
            "chanson", "Шансон", "шансон",
            listOf("шансон", "chanson", "блатн", "дворов"),
        ),
        Genre(
            "kpop", "K-pop", "kpop",
            listOf("kpop", "k-pop", "корейск", "blackpink", "twice", "bts"),
        ),
        Genre(
            "anime", "Аниме", "anime opening",
            listOf("anime", "opening", "ending", "аниме", "опенинг"),
        ),
        // --- Russian scene: what actually charts and gets searched here ---
        Genre(
            "russian_pop", "Русский поп", "русский поп",
            listOf("русск", "поп", "russian pop", "батюшкина", "лобода", "билан", "киркоров", "пугач"),
        ),
        Genre(
            "russian_rap", "Русский рэп", "русский рэп",
            listOf("русский рэп", "рэп", "баттл", "мияги", "моргенштерн", "oxxxymiron", "скриптонит", "тбили"),
        ),
        Genre(
            "phonk", "Фонк", "phonk",
            listOf("phonk", "фонк", "drift", "мемфис", "memphis", "агрессивн"),
        ),
        Genre(
            "hardbass", "Хардбасс", "hardbass",
            listOf("hardbass", "хардбасс", "hard bass", "колбас", "gopnik"),
        ),
        Genre(
            "dance_russian", "Танцевальная", "танцевальная музыка",
            listOf("танцевальн", "dance", "клубн", "дискотека", "remix", "ремикс"),
        ),
        Genre(
            "bard", "Авторская песня", "авторская песня",
            listOf("авторская", "бард", "высоцкий", "окуджава", "гитарн"),
        ),
        Genre(
            "russian_rock", "Русский рок", "русский рок",
            listOf("русский рок", "рок-н-ролл", "кино", "алиса", "сплин", "ддт", "наутилус", "би-2", "любэ", "мумий тролль", "семь ветров"),
        ),
        Genre(
            "kavkaz", "Кавказская эстрада", "кавказская музыка",
            listOf("кавказ", "лезгинк", "дагестан", "чечен", "осетин", "абхаз", "грузинск", "армянск", "азербайдж"),
        ),
        Genre(
            "newyear", "Новогодние", "новогодние песни",
            listOf("новогодн", "новый год", "christmas", "рождеств", "jingle", "ёлочк", "елочк"),
        ),
        Genre(
            "covers", "Каверы", "каверы",
            listOf("кавер", "cover", "tribute", "перепевк"),
        ),
        Genre(
            "post_punk_ru", "Русский пост-панк", "русский пост-панк",
            listOf("пост-панк", "post-punk", "пост панк", "кино", "молчат дома", "буерак", "sad songs"),
        ),
        Genre(
            "estrada", "Эстрада", "советская эстрада",
            listOf("эстрад", "советск", "ретро", "80-х", "90-х", "лещенко", "магомаев"),
        ),
        Genre(
            "drum_bass", "Драм-н-бейс", "drum and bass",
            listOf("drum and bass", "dnb", "драм", "jungle", "джангл", "neurofunk"),
        ),
        Genre(
            "hyperpop", "Хиперпоп", "hyperpop",
            listOf("hyperpop", "хиперпоп", "glitchcore", "digicore"),
        ),
    )

    /** Fallback for tracks the keyword model cannot place. */
    val other = Genre("other", "Разное", "music mix", emptyList())

    val byId: Map<String, Genre> = (all + other).associateBy { it.id }

    const val DEFAULT_WEIGHT = 0.6f
}

/**
 * Everything the app knows about one recording.
 *
 * Negative evidence (skips, short listens, dislikes, removals) is stored next to positive
 * evidence so a single track can be judged, and so its artist/genre can inherit part of that
 * judgement through [Wave.penalties].
 */
data class ListeningSignal(
    val song: SongInfo,
    val plays: Int = 0,
    val skips: Int = 0,
    val lastPlayedAt: Long = 0,
    val shortListens: Int = 0,
    val repeats: Int = 0,
    val likes: Int = 0,
    val dislikes: Int = 0,
    val playlistAdds: Int = 0,
    val libraryAdds: Int = 0,
    val libraryRemovals: Int = 0,
    val updatedAt: Long = 0,
)

/** Decayed negative evidence, keyed by normalized recording / artist / genre / tag. */
data class Penalties(
    val tracks: Map<String, Float> = emptyMap(),
    val artists: Map<String, Float> = emptyMap(),
    val genres: Map<String, Float> = emptyMap(),
    val tags: Map<String, Float> = emptyMap(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && genres.isEmpty() && tags.isEmpty()
}

data class TasteProfile(
    val genres: Map<String, Float>,
    val artists: Map<String, Int>,
    val history: List<ListeningSignal>,
    val penalties: Penalties = Penalties(),
    /** Culture markers the library already contains; records outside them are not recommended. */
    val cultures: Set<String> = emptySet(),
    /** True when the library itself has Indic titles or artists, so such music is not "foreign". */
    val indic: Boolean = false,
)

/** What a wave query actually asks for; the type decides how strictly results are checked. */
enum class WaveSeedKind { ARTIST, GENRE, BASE }

data class WaveSeed(
    val kind: WaveSeedKind,
    val query: String,
    val artist: String? = null,
    val genre: Genre? = null,
)

object Wave {
    /** A skip stops mattering this slowly, so taste can actually change. */
    const val HALF_LIFE_DAYS = 45.0

    fun decay(ageMs: Long): Float {
        val days = ageMs.coerceAtLeast(0L) / 86_400_000.0
        return exp(-days * ln(2.0) / HALF_LIFE_DAYS).toFloat().coerceIn(0.02f, 1f)
    }

    /** Positive minus negative evidence for one recording, before decay. */
    fun rawScore(signal: ListeningSignal): Float {
        val positive = signal.plays * 1.0f + signal.repeats * 0.6f + signal.likes * 2.4f +
            signal.playlistAdds * 0.9f + signal.libraryAdds * 0.5f
        val negative = signal.skips * 1.0f + signal.shortListens * 0.7f + signal.dislikes * 3.0f +
            signal.libraryRemovals * 1.6f
        return positive - negative
    }

    fun penaltyOf(signal: ListeningSignal, now: Long): Float {
        val negative = signal.skips * 1.0f + signal.shortListens * 0.7f + signal.dislikes * 3.0f +
            signal.libraryRemovals * 1.6f
        if (negative <= 0f) return 0f
        val positive = signal.plays * 0.55f + signal.likes * 1.8f + signal.playlistAdds * 0.7f
        val age = now - maxOf(signal.updatedAt, signal.lastPlayedAt).takeIf { it > 0L }.let { it ?: now }
        return ((negative - positive).coerceAtLeast(0f)) * decay(age)
    }

    /**
     * Spread of negative evidence: the track itself, then the artists, genres and tags it belongs
     * to. Each level gets a smaller share, so one skipped song moves the model a little and a whole
     * artist you keep skipping moves it a lot.
     */
    fun penalties(history: List<ListeningSignal>, now: Long = System.currentTimeMillis()): Penalties {
        val tracks = mutableMapOf<String, Float>()
        val artists = mutableMapOf<String, Float>()
        val genres = mutableMapOf<String, Float>()
        val tags = mutableMapOf<String, Float>()
        history.forEach { signal ->
            val penalty = penaltyOf(signal, now)
            if (penalty <= 0.05f) return@forEach
            val trackKey = recordingKey(signal.song)
            tracks[trackKey] = (tracks[trackKey] ?: 0f) + penalty
            val credits = artistNames(signal.song.singers)
            credits.forEach { name ->
                val key = normalize(name)
                artists[key] = (artists[key] ?: 0f) + penalty * 0.5f / credits.size
            }
            val inferred = inferGenres(signal.song)
            inferred.forEach { genre ->
                genres[genre.id] = (genres[genre.id] ?: 0f) + penalty * 0.45f / inferred.size
            }
            signal.song.tags.orEmpty().split(';', ',', '|').map(::normalize)
                .filter { it.length in 3..32 }.distinct().take(4).forEach { tag ->
                    tags[tag] = (tags[tag] ?: 0f) + penalty * 0.25f
                }
        }
        fun cap(map: MutableMap<String, Float>) = map.mapValues { it.value.coerceAtMost(12f) }
        return Penalties(cap(tracks), cap(artists), cap(genres), cap(tags))
    }

    /** How much evidence says "no" about this recording, including its family and its tags. */
    fun penaltyFor(song: SongInfo, penalties: Penalties): Float {
        if (penalties.isEmpty) return 0f
        val track = penalties.tracks[recordingKey(song)] ?: 0f
        val artist = artistNames(song.singers).maxOfOrNull { penalties.artists[normalize(it)] ?: 0f } ?: 0f
        val genre = inferGenres(song).maxOfOrNull { penalties.genres[it.id] ?: 0f } ?: 0f
        val tag = song.tags.orEmpty().split(';', ',', '|').map(::normalize)
            .filter { it.length in 3..32 }.maxOfOrNull { penalties.tags[it] ?: 0f } ?: 0f
        return track + artist * 0.55f + genre * 0.7f + tag * 0.4f
    }

    private val neighbors = mapOf(
        "jazz" to listOf("rnb", "lofi"), "rnb" to listOf("jazz", "hiphop"),
        "rock" to listOf("indie", "metal"), "metal" to listOf("rock"),
        "indie" to listOf("rock", "folk"), "folk" to listOf("indie", "classical"),
        "electronic" to listOf("lofi", "phonk"), "lofi" to listOf("jazz", "electronic"),
        "hiphop" to listOf("rnb", "phonk"), "phonk" to listOf("hiphop", "electronic"),
        "pop" to listOf("indie", "rnb"), "classical" to listOf("soundtrack", "jazz"),
        "soundtrack" to listOf("classical", "electronic"),
    )
    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun artistNames(credit: String?): List<String> = credit.orEmpty()
        .split(Regex("(?i)\\s*(?:,|;|/|&|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\s+x\\s+)\\s*"))
        .map(String::trim).filter { it.isNotBlank() && !it.equals("NULL", true) }.distinctBy(::normalize)

    fun recordingKey(song: SongInfo): String = normalize(song.singers.orEmpty()) + ":" + normalize(song.songName.orEmpty())

    /** Titles and performer names are not genre evidence. Match whole words, never `alt` in `salt`. */
    fun inferGenres(song: SongInfo): List<Genre> {
        val metadata = " " + normalize(listOfNotNull(song.genre, song.tags).joinToString(" ")) + " "
        return Genres.all.filter { genre -> genre.keywords.any { word ->
            metadata.contains(" " + normalize(word) + " ")
        } }
    }

    fun inferGenre(song: SongInfo): Genre = inferGenres(song).firstOrNull() ?: Genres.other

    fun profile(
        coefficients: Map<String, Float>, likes: List<SongInfo>, history: List<ListeningSignal>,
        followed: List<String>, now: Long = System.currentTimeMillis(), saved: List<SongInfo> = emptyList(),
    ): TasteProfile {
        val genres = mutableMapOf<String, Float>()
        val artists = mutableMapOf<String, Float>()
        fun contribute(song: SongInfo, weight: Float) {
            val known = inferGenres(song)
            known.forEach { genres[it.id] = (genres[it.id] ?: 0f) + weight / known.size }
            val credits = artistNames(song.singers)
            credits.forEach { name ->
                val key = normalize(name)
                artists[key] = (artists[key] ?: 0f) + weight / credits.size
            }
        }
        likes.distinctBy(::recordingKey).forEach { contribute(it, 3f) }
        val liked = likes.map(::recordingKey).toSet()
        saved.distinctBy(::recordingKey).filterNot { recordingKey(it) in liked }.forEach { contribute(it, 1.5f) }
        history.forEach { signal ->
            val strength = rawScore(signal)
            if (strength <= 0f) return@forEach
            val age = (now - maxOf(signal.lastPlayedAt, signal.updatedAt)).coerceAtLeast(0L)
            val freshness = exp(-age / 90_000_000.0).toFloat().coerceAtLeast(0.12f)
            contribute(signal.song, sqrt(strength) * freshness)
        }
        followed.flatMap(::artistNames).forEach { artists[normalize(it)] = (artists[normalize(it)] ?: 0f) + 2f }
        val maximum = genres.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val weights = Genres.all.associate { genre ->
            val manual = coefficients[genre.id] ?: Genres.DEFAULT_WEIGHT
            genre.id to if (manual <= 0f) 0f else manual * (0.5f + 1.5f * (genres[genre.id] ?: 0f) / maximum)
        }
        // Preserve original spelling for upstream queries, normalize only for matching.
        val allSongs = likes + saved + history.map { it.song }
        val names = allSongs.flatMap { artistNames(it.singers) } + followed.flatMap(::artistNames)
        return TasteProfile(
            genres = weights,
            artists = names.distinctBy(::normalize).associateWith { ((artists[normalize(it)] ?: 0f) * 10).toInt() },
            history = history,
            penalties = penalties(history, now),
            cultures = allSongs.flatMap(::cultureMarkers).toSet(),
            indic = allSongs.any { hasIndicScript(it.songName, it.singers, it.album) },
        )
    }

    /**
     * Markers of musical cultures that are rarely what a user means by an anonymous genre query.
     * A global catalog answers "pop hits" with Bollywood soundtracks just as readily as with the
     * music the user actually listens to, so these results are only kept when the library already
     * contains that culture.
     */
    private val CULTURES: Map<String, List<String>> = mapOf(
        "indian" to listOf(
            "bollywood", "indian", "hindi", "punjabi", "bhangra", "tamil", "telugu", "desi",
            "filmi", "ghazal", "qawwali", "carnatic", "hindustani", "sufi", "indian pop",
            "bollywood pop", "south indian", "pakistani", "bengali", "marathi", "gujarati",
            "kannada", "malayalam", "sinhala",
        ),
    )

    /** Devanagari … Sinhala: scripts that never appear in a Latin/Cyrillic library by accident. */
    private val INDIC_SCRIPT = Regex("[\\u0900-\\u0DFF]")

    fun hasIndicScript(vararg values: String?): Boolean =
        values.any { value -> !value.isNullOrBlank() && INDIC_SCRIPT.containsMatchIn(value) }

    /** Which culture families a record carries in its catalog metadata. */
    fun cultureMarkers(song: SongInfo): Set<String> {
        // Catalog genre/tags only: an album called "Indian Summer" is not Indian music.
        val haystack = " " + normalize(listOfNotNull(song.genre, song.tags).joinToString(" ")) + " "
        return CULTURES.filterValues { words ->
            words.any { haystack.contains(" " + normalize(it) + " ") }
        }.keys
    }

    /**
     * A record from a culture the library has never shown is not a personal recommendation: it is
     * whatever the widest catalog happened to answer. Keep such records out of the wave.
     */
    fun isForeignCulture(song: SongInfo, profile: TasteProfile): Boolean {
        if ((cultureMarkers(song) - profile.cultures).isNotEmpty()) return true
        return !profile.indic && hasIndicScript(song.songName, song.singers, song.album)
    }

    /**
     * The catalog may credit "Artist feat. Someone" or "Artist & Other"; the seed must be the
     * actual performer, never just a word that occurs in the title. This is what stops an artist
     * query from returning songs *named* after the artist.
     */
    fun matchesArtist(credit: String?, artist: String): Boolean {
        val target = normalize(artist)
        if (target.length < 2) return false
        return artistNames(credit).any { name ->
            // Credits are split on feat./&/comma already, so a whole credit must equal the seed.
            // Prefix matching would accept "Nirvana Tribute Band" for a Nirvana search.
            val value = normalize(name).removeSuffix(" topic").trim()
            value == target
        }
    }

    fun matchesAnyArtist(credit: String?, artists: Collection<String>): Boolean =
        artists.any { matchesArtist(credit, it) }

    /**
     * The single relevance gate between a catalog answer and the wave. Kept here, not in the
     * network engine, so every rule is testable without a provider: the record must belong to the
     * seed's intent, must not come from a culture the library never showed, and the widest catalog
     * is limited to named/known artists instead of being trusted for anonymous browsing.
     */
    fun acceptsSeedResult(
        song: SongInfo,
        seed: WaveSeed,
        profile: TasteProfile,
        knownArtists: Collection<String>,
        allowGlobalCatalog: Boolean,
        relaxed: Boolean = false,
        globalCatalog: String = "QobuzMusicClient",
    ): Boolean {
        if (song.songName.isNullOrBlank() || song.singers.isNullOrBlank()) return false
        if (isForeignCulture(song, profile)) return false
        val familiar = matchesAnyArtist(song.singers, knownArtists)
        if (!allowGlobalCatalog && song.source == globalCatalog && !familiar) return false
        if (relaxed) return true
        return when (seed.kind) {
            WaveSeedKind.ARTIST -> matchesArtist(song.singers, seed.artist.orEmpty())
            WaveSeedKind.GENRE -> matchesGenre(song, seed.genre ?: return false) || familiar
            WaveSeedKind.BASE -> inferGenres(song).isNotEmpty() || familiar
        }
    }

    /** Genre seeds are only satisfied by a record that really belongs to that genre. */
    fun matchesGenre(song: SongInfo, genre: Genre): Boolean {
        if (inferGenres(song).any { it.id == genre.id }) return true
        val haystack = " " + normalize(listOfNotNull(song.genre, song.tags).joinToString(" ")) + " "
        return genre.keywords.any { haystack.contains(" " + normalize(it) + " ") }
    }

    /** Typed seeds: the engine knows whether a query is an artist, a style or a last resort. */
    fun artistSeeds(profile: TasteProfile, generation: Int = 0): List<WaveSeed> =
        artistQueries(profile.artists, generation).map { WaveSeed(WaveSeedKind.ARTIST, it, artist = it) }

    fun genreSeeds(profile: TasteProfile, generation: Int = 0): List<WaveSeed> {
        val byQuery = Genres.all.associateBy { it.query }
        return genreQueries(profile.genres, generation).mapNotNull { query ->
            byQuery[query]?.let { WaveSeed(WaveSeedKind.GENRE, query, genre = it) }
        }
    }

    /** Used when there is no taste data at all, and again if every personal stage came back empty. */
    val baseSeeds: List<WaveSeed> = listOf("music mix", "lofi chill", "top hits", "instrumental")
        .map { WaveSeed(WaveSeedKind.BASE, it) }

    /** Rotate through the entire taste profile over successive waves, with a bounded network budget. */
    fun buildQueries(coefficients: Map<String, Float>, artists: Map<String, Int>, generation: Int = 0): List<String> =
        (artistQueries(artists, generation) + genreQueries(coefficients, generation)).distinct()

    /** Artist seeds, rotated between waves so one prolific favourite cannot own the wave. */
    fun artistQueries(artists: Map<String, Int>, generation: Int = 0): List<String> {
        if (artists.isEmpty()) return emptyList()
        val ordered = artists.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
        ).map { it.key }
        val start = Math.floorMod(generation.toLong() * 6, ordered.size.toLong()).toInt()
        return (0 until minOf(6, ordered.size)).map { ordered[(start + it) % ordered.size] }
    }

    /** Genre seeds: strongest preferences first, plus a couple of adjacent styles. */
    fun genreQueries(coefficients: Map<String, Float>, generation: Int = 0): List<String> {
        fun <T> rotated(items: List<T>, count: Int): List<T> {
            if (items.isEmpty()) return emptyList()
            val start = Math.floorMod(generation.toLong() * count, items.size.toLong()).toInt()
            return (0 until minOf(count, items.size)).map { items[(start + it) % items.size] }
        }
        val eligible = Genres.all.filter { (coefficients[it.id] ?: Genres.DEFAULT_WEIGHT) > 0.05f }
            .sortedByDescending { coefficients[it.id] ?: Genres.DEFAULT_WEIGHT }
        val peak = eligible.firstOrNull()?.let { coefficients[it.id] ?: Genres.DEFAULT_WEIGHT } ?: 0f
        val preferred = eligible.filter { (coefficients[it.id] ?: Genres.DEFAULT_WEIGHT) >= peak * 0.4f }
        val adjacent = preferred.flatMap { neighbors[it.id].orEmpty() }.distinct()
            .mapNotNull(Genres.byId::get).filter { it in eligible && it !in preferred }
        return (preferred.take(2) + rotated(preferred.drop(2), 3) + rotated(adjacent, 1))
            .distinct().map { it.query }
    }

    fun rank(results: List<SongInfo>, coefficients: Map<String, Float>, artists: Map<String, Int>,
             history: List<ListeningSignal> = emptyList(), recent: Set<String> = emptySet(),
             penalties: Penalties = Penalties()): List<SongInfo> {
        val artistTaste = artists.entries.associate { normalize(it.key) to it.value }
        val listening = history.associateBy { recordingKey(it.song) }
        val candidates = results.filter { !it.songName.isNullOrBlank() && !it.singers.isNullOrBlank() }
            .distinctBy(::recordingKey).mapNotNull { song ->
                val genres = inferGenres(song)
                val signal = listening[recordingKey(song)]
                if (signal != null && signal.skips > signal.plays + 1) return@mapNotNull null
                val weights = genres.map { coefficients[it.id] ?: Genres.DEFAULT_WEIGHT }
                if (weights.isNotEmpty() && weights.all { it <= 0f }) return@mapNotNull null
                val rejection = penaltyFor(song, penalties)
                // A track the user explicitly disliked, or skipped again and again, does not come back.
                if ((penalties.tracks[recordingKey(song)] ?: 0f) >= 4f) return@mapNotNull null
                val affinity = weights.average().takeUnless { it.isNaN() }?.toFloat() ?: 0.25f
                val familiar = artistNames(song.singers).maxOfOrNull { artistTaste[normalize(it)] ?: 0 } ?: 0
                val novelty = if (signal == null) 0.35f else -ln(1f + signal.plays) * 0.3f - signal.skips * 0.4f
                song to (affinity * 2f + ln(1f + familiar) * 0.16f + novelty -
                    (if (recordingKey(song) in recent) 1.25f else 0f) - rejection)
            }.sortedByDescending { it.second }.toMutableList()
        val selected = mutableListOf<SongInfo>()
        val artistCounts = mutableMapOf<String, Int>()
        while (candidates.isNotEmpty() && selected.size < 40) {
            val recentArtists = selected.takeLast(3).flatMap { artistNames(it.singers) }.map(::normalize).toSet()
            val eligible = candidates.filter { (song, _) ->
                artistNames(song.singers).none { (artistCounts[normalize(it)] ?: 0) >= 2 || normalize(it) in recentArtists }
            }
            val next = eligible.maxByOrNull { (song, score) ->
                val sameGenre = selected.takeLast(3).count { inferGenre(it) == inferGenre(song) }
                score - sameGenre * 0.2f
            } ?: break
            candidates.remove(next)
            selected += next.first
            artistNames(next.first.singers).forEach { name -> artistCounts[normalize(name)] = (artistCounts[normalize(name)] ?: 0) + 1 }
        }
        return selected
    }
}
