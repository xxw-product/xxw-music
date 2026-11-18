# musicdl — Kotlin / Android port

Kotlin rewrite of [CharlesPikachu/musicdl](https://github.com/CharlesPikachu/musicdl)
for Android Studio. The Python package is a CLI music search/downloader with 57
registered source clients; this project ports its engine to Kotlin and puts a Compose UI on top.

## Module layout

```
android/
├── musicdl-core/        pure Kotlin/JVM — the ported engine (no Android deps, unit-testable)
│   ├── model/           SongInfo, DownloadUrlStatus, PathUtils
│   ├── net/             HttpClient (OkHttp), AudioLinkTester, HlsPlaylist, SongDownloader
│   ├── source/          BaseMusicClient + the ported source clients + MusicClient facade
│   └── util/            Crypto, JsonUtil, TextUtils, LoggerHandle
└── app/                 Android app — Compose UI over the engine
```

`musicdl-core` is a plain JVM module on purpose: the engine is testable with
`./gradlew :musicdl-core:test` without an emulator.

## The app

`app/` is a single-activity Compose player built on Material 3 Expressive:

* `MusicdlTheme.kt` — `MaterialExpressiveTheme` with `MotionScheme.expressive()`, expressive corner
  scale (10→38 dp) and dynamic color on API 31+.
* `MusicdlScreen.kt` — Russian UI: pill search field, source chips (top 5 / all 55 / per-client), a hero card
  whose single button streams **and** saves the best match, result cards where a tap plays and the
  trailing button saves, plus a collapsible now-playing panel with a seek slider.
* `SearchViewModel.kt` — search fan-out through the ported `MusicClient`, Media3 `ExoPlayer`
  playback, and per-track downloads through `MusicClient.download`.

### «Моя волна» and genre weights

The sources expose no genre field, so `Wave.kt` classifies every track with a local keyword heuristic
(title / artist / album / source) into 16 genres plus a fallback bucket. Each genre carries a weight
(0…2, default 0.6) stored in SharedPreferences (`wave_prefs`). A like or dislike on a card or in the
player moves that track's genre weight by 0.2, and «Включить волну» searches with the
highest-weighted genres' keywords and ranks the answers by weight, so playback drifts towards what you
rated. «Жанры» opens a slider editor for every coefficient.

Search also streams: `MusicClient.search` reports each source through `onSourceResult` as soon as it
answers (or hits the per-source cap, 20 s in the app, 30 s in the engine default), so cards appear
while the slower sources are still running instead of after the slowest one.

### Sources: defaults, picker and the rescue pass

The engine keeps the faithful port of the Python default (the Chinese top-5), but that is the wrong
default for this app: those endpoints are geo-blocked or DNS-dead outside mainland China, which left
whole searches empty. The app therefore starts on `RECOMMENDED_SOURCES` (`Sources.kt`): public
catalogs and mirrors that answer anonymously, shown as «Источники · 11 · авто».

Results are ordered by relevance to the query — title/artist matches first, file size only breaking
ties — so a small Apple preview of the exact track is not buried under unrelated FLAC rips.

A picker dialog opens from that one button: «Авто» (the recommended set), «Все 56», per-source
checkboxes, and «только» on each row to search a single source. The selection is respected: the
search never widens to sources the user did not pick. An empty feed instead offers a card with an
«Искать» button that adds the recommended sources and re-runs the query — widening happens only
when it is tapped.

### Full tracks, no previews

Each source is asked for up to 30 hits (`searchSizePerSource`), not 8: the old cap is why deeper
matches like a fifth-page result never surfaced. A hit is also kept only when its title or artist
mentions a word of the query: Audius and Jamendo answer any query with vaguely similar tracks, and
that fuzzy tail is what filled the feed with unrelated songs.

«Авто» is 11 sources that hand out a playable file: Audius, Jamendo, FMA, CCmixter, Wikimedia,
JioSaavn, StreetVoice, SoundCloud, MP3Juice, TuneHub, MyFreeMP3. The catalogs that only serve
previews (Deezer 30 s, Apple/iTunes 30 s to about two minutes, Qobuz, Spotify) are collected in
`PREVIEW_SOURCES`: «Авто» never uses them and their rows are filtered out of the default feed,
together with any hit shorter than 36 s. Choose one of them (or «Все») and previews come back,
marked «превью» in the card — that is the only place a mainstream release like `Miura — BATO`
exists at all.

### Following an artist

The person icon on a card subscribes to that track's artist; `LibraryStore.followedArtists` keeps
the list across launches. The «Авторы» tab shows the subscriptions, and tapping one runs the usual
engine search by that artist name over the full-track sources, so their other music shows up next
to what you already found. Unfollowing is the same icon, or the button in the artist header.

### Full-screen player

Tapping the now-playing panel opens a full-screen player: large artwork, title/artist with the
follow and like buttons, a seek bar with elapsed and remaining time, and prev / play-pause / next
over a tinted gradient. The bubble button at the bottom switches the artwork for the lyrics — the
current line is bold and opaque while its neighbours fade out line by line (`LyricsPane`), and the
list follows playback. The separate sheets that used to show lyrics are gone; the panel's lyrics
icon opens this player already in lyrics mode.

### Cover art and timed lyrics

Cards and the player show the catalog artwork (`coverUrl`): Deezer `cover_xl`, iTunes
`artworkUrl100` widened to 600 px, Apple `artwork.url`. Images are loaded by `Cover.kt` —
`HttpURLConnection` plus a 48-bitmap LRU cache, so no image-loading dependency is added; when a
source has no artwork the letter tile (or the note icon in the player) stays as the fallback.

The player's «Текст» button opens a lyrics sheet. The lookup starts at lrclib.net: first the exact
`/api/get` (it carries LRC timings), then `/api/search` with a title/artist sanity check, then
`lyrics.ovh` for plain text. LRC is parsed with support for several `[mm:ss.xx]` stamps on one line;
the line being sung is highlighted from the player position and the list scrolls along with it.

### What survives a restart

`LibraryStore` persists the keyword, the last results, the wave, the «Нравится» playlist, the history,
the selected sources, the current tab and the playback queue with its cursor and position (written
every ~3 s while playing, since a swipe-away never reaches `onCleared`). A cold start therefore puts
the same track back on the panel, paused where it stopped, and re-opens the tab you left.

Playback reuses the headers the audio tester validated (`song.downloadHeaders`), so any link the
engine accepts streams here without a second resolution step. `DefaultMediaSourceFactory` picks HLS
for `.m3u8` links, which is what the Quark/HLS-backed sources return.

**Version pin:** M3 Expressive needs `material3` newer than the Compose BOM's stable line, so it is
pinned explicitly. `1.5.0-beta02` (and the 2026 BOMs) require compileSdk 37 and AGP 9.1, which this
project does not use; `1.5.0-alpha12` is the newest Expressive build that still compiles against
SDK 36 with AGP 8.13.2, paired with Compose BOM `2025.09.01` and Kotlin 2.2.20. The Expressive theme
API in that version needs `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.

## What was ported

| Python | Kotlin |
|---|---|
| `musicdl/modules/utils/data.py::SongInfo` | `model/SongInfo.kt` |
| `musicdl/modules/utils/misc.py::AudioLinkTester` | `net/AudioLinkTester.kt` |
| `musicdl/modules/utils/hls.py` (playlist resolution, direct segments) | `net/HlsPlaylist.kt`, `net/SongDownloader.kt` |
| `musicdl/modules/utils/misc.py` (json/string helpers) | `util/JsonUtil.kt`, `model/PathUtils.kt` |
| `cleanlrc` / `extractdurationsecondsfromlrc` / `to_seconds_func` | `util/TextUtils.kt` |
| `musicdl/modules/utils/neteaseutils.py::EapiCryptoUtils` | `util/Crypto.kt` |
| `musicdl/modules/utils/{kugou,qq,qianqian,migu}utils.py` (signing) | `util/Crypto.kt` |
| `musicdl/modules/sources/base.py::BaseMusicClient` | `source/BaseMusicClient.kt` |
| `musicdl/musicdl.py::MusicClient` | `source/MusicClient.kt` |
| `musicdl/musicdl.py::MusicClientCMD` (CLI) | `app/.../MusicdlScreen.kt` (Compose UI) |

### Source clients ported (55 of 57)

| Client | Family | Kept |
|---|---|---|
| `NeteaseMusicClient` | streaming | cloudsearch + eapi url resolution + lyrics |
| `QQMusicClient` | streaming | `musicu.fcg` search + vkeys url/lyrics |
| `KuwoMusicClient` | streaming | search + `trackercdn` kgcloudv2 resolution |
| `MiguMusicClient` | streaming | `search_all.do` + magic-prefix decryption + quality ladder |
| `QianqianMusicClient` | streaming | signed search + `tracklink` rate ladder + lyrics |
| `WikimediaCommonsMusicClient` | catalog | `generator=search` file search + imageinfo |
| `AudiusMusicClient` | catalog | tracks search, `stream` endpoint, playlist resolve |
| `JamendoMusicClient` | catalog | `x-jam-call` signing, track/artist/album enrichment |
| `FMAMusicClient` | catalog | search HTML + `data-track-info` detail page |
| `CCMixterMusicClient` | catalog | XSPF query + upload info durations |
| `OpenGameArtMusicClient` | catalog | art-search + `sites/default/files` ranking |
| `JioSaavnMusicClient` | catalog | `search.getResults` + DES url decryption + lyrics |
| `StreetVoiceMusicClient` | catalog | search HTML + `v5/song` + HLS file endpoint |
| `SunoMusicClient` | catalog | unified feed (`auth_token` cookie required) |
| `KugouMusicClient` | streaming | `song_search_v2` + mirror ladder (haitangw / chksz / cocodownloader / lzmhhh) |
| `BilibiliMusicClient` | streaming | `search/type` + `view` parts + DASH `playurl` (FLAC → Dolby → audio) |
| `BodianMusicClient` | streaming | `bd-api` search + anonymous Kuwo gateways (`nmobi` / `mobi` `convert_url_with_sign`) |
| `FiveSingMusicClient` | streaming | `search.5sing.kugou.com/home/json` + `getSongUrl` (sq → hq → lq) + `newget` lyric |
| `SodaMusicClient` | streaming | `luna/search/track` + `_ROUTER_DATA` audition URL and timed lyrics |
| `HTQYYMusicClient` | scrapers | `home/search` HTML list + play page `var fileHost`/`var mp3` |
| `YinyuekuMusicClient` | scrapers | `api.php` search → `url`/`pic`/`lyric` POSTs |
| `BuguyyMusicClient` | scrapers | `api/search` + `api/geturl` (or `MP3#` mirror via Quark) |
| `MituMusicClient` | scrapers | `api/songs.php` + `api/kw.php?level=exhigh&lrc=true` |
| `XiagebaMusicClient` | scrapers | `api/music/search` + `so.liumingye.cn` share links via Quark |
| `KKWSMusicClient` | scrapers | `search.html` list + `openModel` → `getdown` → Quark |
| `FiveSongMusicClient` | scrapers | `search.html` + `play/songdown.php` Quark links + `play/lrc.php` |
| `ITunesMusicClient` | audiobooks | podcast search + RSS feed → episodes |
| `ITunesSongsMusicClient` | catalog | iTunes Search API (`entity=song`) over `ru` + `us` storefronts |
| `XimalayaMusicClient` | audiobooks | album/track search, `xm-sign`, play-url decryption |
| `QingtingMusicClient` | audiobooks | HMAC-signed audiostreams (tokens required) |
| `LizhiMusicClient` | audiobooks | vodapi search, quality ladder, play sheets |
| `LRTSMusicClient` | audiobooks | book/album search + `getPlayPath` ladder |
| `GDStudioMusicClient` | aggregators | `api.php` search/url/pic/lyric with `makesign` |
| `TuneHubMusicClient` | aggregators | meting/Kuwo/QQ search + parse gateway |
| `MP3JuiceMusicClient` | aggregators | apiKey scrape, YouTube convert chain, SoundCloud |
| `MyFreeMP3MusicClient` | aggregators | netease outer-url path |
| `JBSouMusicClient` | aggregators | form search + `mk_visit` cookie + lrc pages |
| `XiaoBaiMusicClient` | aggregators | form search + lrc pages |
| `GequbaoMusicClient` | scrapers | `so.php` search + Quark share links |
| `TwoT58MusicClient` | scrapers | `ajax_music.php` search, captcha detection + Quark links |
| `HeishanMusicClient` | scrapers | `api.php` search + Quark share links |
| `YinyuedaoMusicClient` | scrapers | `api/` search + Quark share links |
| `FangpiMusicClient` | scrapers | `api.php` search + Quark share links |
| `GequhaiMusicClient` | scrapers | search HTML + base64 (`#`→`H`, `%`→`S`) mirror URLs |
| `GequkeMusicClient` | scrapers | search HTML + base64 (`#`→`H`, `%`→`S`) mirror URLs |
| `LeedodoMusicClient` | scrapers | `search.php` + Quark share links |
| `MGMP3MusicClient` | scrapers | `search.php` + Quark share links |
| `SgogoMusicClient` | scrapers | `api/search` + Quark share links |
| `XMFWAVMusicClient` | scrapers | `search.php` + Quark share links |
| `DeezerMusicClient` | streaming | anonymous mirrors (`deezdownloaders` / `musicfab`) + 30 s preview |
| `SoundCloudMusicClient` | streaming | `api-v2` search + progressive transcoding |
| `JooxMusicClient` | streaming | `openjoox` search + track metadata, `gdstudio` mirror fallback |
| `MOOVMusicClient` | streaming | `search-hk` search + `checkout` HLS ladder + `getLyric` |
| `AppleMusicClient` | streaming | `amp-api` catalog search with a scraped developer token → preview URLs |
| `QobuzMusicClient` | streaming | signed `catalog/search` + `anandserver` stream mirror |
| `SpotifyMusicClient` | streaming | TOTP → `api/token` → `pathfinder` search, `spotsaver`/`musicfab` downloads |

Client-specific limits from `MusicClient.__init__` are preserved in
`MusicClientRegistry.configFor` (three results for the audiobook/FM clients,
MP3Juice's doubled page budget).

### Credential-gated clients

`QingtingMusicClient` needs `qingting_id` plus `access_token`/`refresh_token`,
`SunoMusicClient` needs an `auth_token` cookie. Both read them from the client
config (or the `rule` map for Qingting); without them the client logs and returns
no results rather than pretending to search.

`XiagebaMusicClient`, `KKWSMusicClient` and `FiveSongMusicClient` only yield tracks
through Quark shares, so they need the user's Quark cookies. Pass them either as a
`quark_cookies` entry holding a `k=v; k2=v2` string, or as a cookie map containing
`b-user-id`/`__pus`/`__puus`. Without them the client logs and returns nothing.
`BuguyyMusicClient` and `MituMusicClient` still work anonymously through their own
`geturl`/`kw.php` endpoints. `MOOVMusicClient` derives a `MOOVUUID`-style device id
when none is configured (playback still depends on the account behind the device).

`DeezerMusicClient` implements only the anonymous branch: the `deezdownloaders` /
`musicfab` mirrors return 30-second previews. The premium path (`arl` cookie plus
Blowfish stream decryption) is not ported.

`AppleMusicClient` and `QobuzMusicClient` also run anonymously: Apple scrapes the
`music.apple.com` bundle for the developer token (catalog previews, no
`media-user-token`), Qobuz uses the catalog app credentials plus the `anandserver`
stream mirror. Adding a `media-user-token` (Apple) or `user_auth_token` (Qobuz) to the
client cookies switches them to the account path. `SpotifyMusicClient` derives its own
web-player tokens (TOTP → `api/token` → `clienttoken`) and downloads through the
anonymous `spotsaver` / `musicfab` parsers.
`SoundCloudMusicClient` keeps only the `progressive` transcodings; the Widevine and
FairPlay branches that cover most `ctr-` tracks are not ported.

## Not ported yet (2 of 57)

`TIDALMusicClient` and `YouTubeMusicClient`. Both are blocked by an external runtime or
an account session, not by missing HTTP knowledge:

* **`YouTubeMusicClient` needs a JavaScript runtime.** `youtubeutils.py`'s
  `YouTubeAudioURLExtractor` takes a `node_path`, writes the player script to disk and
  executes it (`JavaScriptRuntimeError` is one of its declared failures) to de-obfuscate
  `nsig`/`sig`. Android has no `node` binary, and shipping one inside the APK is out of
  scope for this module, so the decipher step cannot be reproduced faithfully.
* **`TIDALMusicClient` needs a stored account session.** `TIDALMusicClient.__init__`
  asserts that search/parse/download cookies exist and then builds a `TidalTvSession`
  from them. Without that session the official route
  (`GET api.tidalhifi.com/v1/tracks/{id}/playbackinfopostpaywall`) answers with
  `assetPresentation: PREVIEW`, which `getstreamurl` explicitly rejects. The anonymous
  routes live in separate utilities (`zarz.py`, `afkarxyz.py`) that are not part of the
  ported engine, and the resulting streams are `vnd.tidal.bt` / `dash+xml` manifests
  that need an external remuxer (`N_m3u8DL-RE`, `ffmpeg`).

Everything else in `musicdl/modules/sources/` now has a Kotlin counterpart. Porting the
last two as HTTP wrappers would either return nothing or a 30-second preview labelled as
a track, which is why they are left out instead of faked.

Adding a source is a contained job: subclass `BaseMusicClient`, implement
`constructSearchUrls` and `searchOne`, register it in `MusicClientRegistry`.

## Build

Requires JDK 17 and Android SDK 36.

```bash
cd android
./gradlew :musicdl-core:test      # engine tests, no emulator
./gradlew :app:assembleDebug      # APK -> app/build/outputs/apk/debug/
```

Open the `android/` folder in Android Studio and it resolves as a normal
two-module Gradle project.

## Tests

* `CoreUtilsTest` — md5/eapi encryption, the Migu magic-prefix decryption
  round-trip, `legalizeString` entities, `safeextractfromdict` negative indexing,
  save-path de-duplication, duration formatting, `hasValidDownloadUrl`.
* `SearchPipelineTest` — end-to-end fan-out over MockWebServer: pagination,
  link validation, identifier de-duplication, work-dir stamping, surviving a 500
  from one page.
* `MusicDownloadTest` — direct and HLS downloads.
* `CatalogSourcesTest` — the Web-API clients.
* `PortedSourcesTest` — `cleanLrc`/duration helpers and registry coverage for the
  families added in this round (55 registered sources in total).

## Notes

- `usesCleartextTraffic="true"` is set because several Chinese music endpoints are
  plain HTTP.
- Downloads go to the app's external files dir under `musicdl_outputs/`.
- Downloads send `User-Agent: MusicDL-Android/1.0` unless the source supplies its own; Wikimedia
  Commons answers HTTP 403 to OkHttp's default UA.
- HLS support is intentionally partial: complete VOD playlists with uniform
  segments are downloaded and concatenated; encryption, byte ranges and live
  playlists are rejected with an explicit error instead of writing broken files.
- The engine does not embed audio tags; the Python version does this through
  `mutagen`. Use Media3/ExoPlayer (already a dependency) to play what you fetch.
- Lyrics come from each source's own endpoint where one exists; the Python
  package's fallback `LyricSearchClient` is not ported, so a client without a
  native lyric endpoint returns no lyric instead of a searched one.
