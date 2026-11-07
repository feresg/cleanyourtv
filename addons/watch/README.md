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

In a country whose language isn't English, its rows are that language's own productions, titled in it: France shows
French films and series in French rather than a copy of the US and UK lists (`with_original_language=fr`,
`language=fr-FR`).

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

At [app.trakt.tv/settings/apps](https://app.trakt.tv/settings/apps), create a **new API app**:

- **Name:** anything, for example "My TV".
- **Redirect URI:** `http://localhost`. Trakt lets a development app redirect only to localhost, and the TV's sign-in
  never opens it, but Trakt checks it every time the sign-in is refreshed, so `trakt_redirect_uri` below must be the
  same text. If you entered something else, such as `http://localhost:8080`, put that in the file.

Copy its **Client ID**. New Trakt apps get no client secret (Trakt now signs apps in without one), and the TV's
code sign-in needs only the client id. If your app is an older one with a **Client Secret**, you may add it as
`trakt_client_secret`; it is sent only then. The first time you open a Trakt tab, the TV shows a code and a QR code
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
| `trakt_client_id` | none | Your Trakt application's Client ID |
| `trakt_client_secret` | none | Only for an older Trakt app that has one; new apps have no secret |
| `trakt_redirect_uri` | `http://localhost` | The Redirect URI on your Trakt application, exactly as entered there |
| `kodi_port` | `8080` | Kodi's web server port |
| `kodi_user`, `kodi_pass` | `kodi`, none | Kodi's web server login |
| `regions` | `["US", "GB", "FR"]` | Countries for the TMDB rows, two-letter codes (`UK` is read as `GB`) |
| `language` | `en-US` | Language of titles and overviews from TMDB, for example `fr-FR` |
| `home_shelves` | all four | The rows Watch offers the launcher's Home, in order: `up_next`, `continue`, `trending`, `new` |
| `home_regions` | the first of `regions` | The countries that each get a "New" row on Home, in order, e.g. `["US", "FR"]`; the Watch screen keeps every country in `regions` |

## Rows on Home

With the launcher's `home_shelves` switch on, Home shows Watch's rows under the app rows: Up next and Continue
watching (when Trakt is signed in), Trending this week, and New on streaming for each country in `home_regions`
(the first of `regions` unless you list them; French films and series for France). OK on a card opens that title's page here, and Back returns to Home.

The launcher has no network access, so Watch does the reading. `ShelfProvider` hands Home the rows from a copy on the
TV and their pictures (TMDB backdrops at w300) from Watch's cache. When Home opens and that copy is more than 30
minutes old, Watch reads Trakt and TMDB again on one thread of its own and tells Home, which shows the new rows. There is
no background service or timer: nothing is fetched unless Home asks.

To turn them on, set `home_shelves` to `true` in the launcher's `config.xml` (with `module_cards`, on by default),
install it again, and answer **Show card** for Watch. Build Watch and the launcher on the same computer: they must be
signed with the same key, or Watch refuses Home. The launcher's side is described in
[`docs/specs/shelves.md`](../../docs/specs/shelves.md).

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | `api.trakt.tv`, `api.themoviedb.org`, `image.tmdb.org`, and Kodi's JSON-RPC on `127.0.0.1` |

- **What is sent:** to Trakt, your app keys and sign-in token. To TMDB, your key and the lists asked for. To Kodi,
  its login and the Umbrella address.
- **Plain HTTP:** allowed for `127.0.0.1` and `localhost` only (`res/xml/network.xml`). Everything else is HTTPS.
- **Storage:** keys and tokens stay in the app's own storage (backups are off). The Watch screen keeps posters in
  memory only. Home's rows are kept in `shelves.json` in the app's own files, their pictures (20 per row, the ones no
  row uses deleted at each read) in its cache.
- **Home's rows:** `ShelfProvider` is exported so the launcher can read it, and answers only the launcher
  (`com.gathin.tvhome`) signed with the same key as Watch, checked on every call; any other app is refused. It is
  read-only. The launcher opens a title by handing Watch only that title's key, which Watch looks up in its own rows.
- **Pushed config:** `ConfigProvider` takes the pushed file, and only the ADB shell can use it, because it
  requires `WRITE_SECURE_SETTINGS`, as Smart Home's does. On a TV that won't hand the shell that provider, `install.sh`
  copies the file into the app's own folder (`Android/data/io.github.feresg.watch/files`) instead, where the provider
  would have put it. Either way the app reads it once on opening and deletes it.
- **Nothing else:** no background service, no telemetry, no ads, no libraries. It reads Trakt and TMDB while its
  screen is open (a tab's rows are kept for 10 minutes) and, for Home's rows, when Home asks and its copy is over 30
  minutes old.

## Build and install

You need JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher. Then run `install.sh` as
above. To build only: `cd addons/watch && ./gradlew assembleRelease`. The JVM tests (Trakt, TMDB, the Umbrella
addresses and the Kodi requests) run with `./gradlew testReleaseUnitTest`.

## Limits

- **Up next:** looks at your 20 most recently watched series, one Trakt request each, four at a time.
- **Sign-in:** Trakt's access token lasts 7 days and is renewed by itself. Each refresh token works once, so only
  one renewal runs at a time. If Trakt ever answers that the sign-in has ended, the Trakt tabs show a new code.
- **Seasons and episode lists** open in Umbrella, not in this app.
- **Without TMDB,** the Trakt rows show names without posters, and Umbrella gets no IMDb id for titles that came
  from TMDB.

## Tested on

Not yet tried on a TV. The JVM tests cover the answers as Trakt's and TMDB's APIs document them. The Umbrella
parameters were checked against Umbrella's `resources/lib/modules/router.py` and the `play_Item` addresses its own
lists build.

## Maintainer

[@feresg](https://github.com/feresg)
