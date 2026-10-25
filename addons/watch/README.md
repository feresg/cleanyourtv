# Watch

What you're watching and what's worth watching, as rows of posters, played through
[Umbrella](https://umbrellaplug.github.io) in Kodi. For the [Clean Your TV](../../README.md) launcher it declares a
[module card](../../docs/modules.md): once you answer **Show card** its tile reads "Watch" with a red glow. On any
other Android TV home it is an ordinary app. It's a personal add-on, not made by or affiliated with Trakt, TMDB,
Kodi or Umbrella.

| Tab | Shows | From |
|---|---|---|
| Up next | The next episode of each of the 20 series you watched most recently, without the ones hidden from progress | Trakt |
| Continue | Films and episodes you paused, with how far you got | Trakt |
| Watchlist | Films, then series, the last added first | Trakt |
| Trending | Trending this week worldwide, then the most popular films and series on subscription streaming in each of your countries (US, UK and France by default) | TMDB |
| New on streaming | Films released for digital in the last 45 days, and series started in the last 60 days that stream on a subscription, per country | TMDB |

Moving along the tabs switches the page. OK on a poster opens the title over its backdrop:

- **A film:** **Play in Umbrella** brings Kodi to the front and asks Umbrella to play it. Umbrella looks for
  sources and you pick one.
- **An episode:** **Play S02E08**, **Season 2** (its episodes in Umbrella), or **All seasons**.
- **A series:** **Seasons in Umbrella**.

The request is the same address Umbrella's own lists use (`plugin://plugin.video.umbrella/?action=play_Item&title=…&year=…&imdb=…&tmdb=…`,
plus `tvdb`, `season`, `episode`, `tvshowtitle` and `premiered` for an episode). It goes to Kodi's JSON-RPC as
`Player.Open`, which works like Kodi's `PlayMedia`. Umbrella scrobbles what you play to Trakt if you have
connected Trakt inside Umbrella. This app only reads from Trakt and never writes to it.

## Setup

### 1. Kodi

In Kodi: Settings, Services, Control. Turn on **Allow remote control via HTTP**, keep port **8080**, and set a
user name and password. Watch talks to Kodi on `127.0.0.1` only, which is this same TV. Kodi's package is
`org.xbmc.kodi`, so a debloat list must leave it enabled.

### 2. TMDB (Trending and New, and posters for the Trakt tabs)

Make a free account at [themoviedb.org](https://www.themoviedb.org/signup). Then go to Settings, API, and request
a developer key. Copy the **API Read Access Token**, the long one. The shorter v3 API key works too.

### 3. Trakt (Up next, Continue, Watchlist)

At [trakt.tv/oauth/applications](https://trakt.tv/oauth/applications), create a **New application**:

- **Name:** anything, for example "My TV".
- **Redirect URI:** `urn:ietf:wg:oauth:2.0:oob`.

Copy its **Client ID** and **Client Secret**. The first time you open a Trakt tab, the TV shows a code and a QR code
for `trakt.tv/activate`. Enter the code on your phone and the page carries on by itself. The sign-in stays on the TV
and refreshes itself.

### 4. Push the keys

Copy [`watch.example.json`](watch.example.json) to `backup/watch.json`. `backup/` is git-ignored, so the keys
never land in a commit. Fill it in, then:

```bash
TV_HOST=<tv ip> addons/watch/install.sh backup/watch.json
```

The app copies the file into its own storage and deletes it. To change a key, push a new file; only the keys in
it change. A new `trakt_client_id` signs Trakt out.

| Key | Default | What it is |
|---|---|---|
| `tmdb_token` | none | TMDB API read access token (or v3 key) |
| `trakt_client_id`, `trakt_client_secret` | none | Your Trakt application |
| `kodi_port` | `8080` | Kodi's web server port |
| `kodi_user`, `kodi_pass` | `kodi`, none | Kodi's web server login |
| `regions` | `["US", "GB", "FR"]` | Countries for the TMDB rows, two-letter codes (`UK` is read as `GB`) |
| `language` | `en-US` | Language of titles and overviews from TMDB, for example `fr-FR` |

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | `api.trakt.tv`, `api.themoviedb.org`, `image.tmdb.org`, and Kodi's JSON-RPC on `127.0.0.1` |

- **What is sent:** to Trakt, your app keys and sign-in token. To TMDB, your key and the lists asked for. To Kodi,
  its login and the Umbrella address.
- **Plain HTTP:** allowed for `127.0.0.1` and `localhost` only (`res/xml/network.xml`). Everything else is HTTPS.
- **Storage:** keys and tokens stay in the app's own storage (backups are off). Posters are kept in memory only.
- **Pushed config:** `ConfigProvider` takes the pushed file, and only the ADB shell can use it, because it
  requires `WRITE_SECURE_SETTINGS`, as Smart Home's does.
- **Nothing else:** no background service, no telemetry, no ads, no libraries. It reads Trakt and TMDB only while
  the screen is open. A tab's rows are kept for 10 minutes.

## Build and install

You need JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher. Then run `install.sh` as
above. To build only: `cd addons/watch && ./gradlew assembleRelease`. The JVM tests (Trakt, TMDB, the Umbrella
addresses and the Kodi requests) run with `./gradlew testReleaseUnitTest`.

## Limits

- **Up next:** looks at your 20 most recently watched series, one Trakt request each, four at a time.
- **Seasons and episode lists** open in Umbrella, not in this app.
- **Without TMDB,** the Trakt rows show names without posters, and Umbrella gets no IMDb id for titles that came
  from TMDB.

## Tested on

Not yet tried on a TV. The JVM tests cover the answers as Trakt's and TMDB's APIs document them. The Umbrella
parameters were checked against Umbrella's `resources/lib/modules/router.py` and the `play_Item` addresses its own
lists build.

## Maintainer

[@feresg](https://github.com/feresg)
