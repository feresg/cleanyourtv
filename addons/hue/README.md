# Lights

Your Philips Hue rooms and zones on the TV, straight from the Hue Bridge on your home network: no cloud, no
account. It opens as a panel on the right over whatever is on screen (Home, from the top bar), and Back closes it.
OK switches a room on or off, ◀ ▶ dims it by a tenth, and Menu (or holding OK) picks one of its scenes.
**All off** at the top switches off every light on the bridge. For the [Clean Your TV](../../README.md) launcher it
declares a [module card](../../docs/modules.md): once you answer **Show card** its tile reads "Lights" with a warm
glow. On any other Android TV home it is an ordinary app. A personal add-on, not made by or affiliated with Signify
or Philips Hue.

## In the launcher's top bar

To have Lights as a round bulb button in the top bar, next to the Wi-Fi and Settings pills, list it in the launcher's
`top_bar_apps` in `launcher/app/src/main/res/values/config.xml` and rebuild the launcher:

```xml
<string-array name="top_bar_apps" translatable="false">
    <item>io.github.feresg.hue</item>
</string-array>
```

(Or `"top_bar_apps": ["io.github.feresg.hue"]` in a theme's `config.json`.) The app then leaves the grid; answer
**Keep hidden** when the launcher asks about its card, so it isn't on Home twice. The icon is a plain bulb on
transparent, so it sits cleanly in the round button.

## Setup

1. Open Lights. It looks for the bridge on the network with mDNS (`_hue._tcp`) for a few seconds. If that finds
   nothing (wired TVs often can't see mDNS), it asks Signify's discovery address (`discovery.meethue.com`) once.
   You can also enter the bridge's IP address yourself (your router, or the Hue app: Settings, Bridges, the ⓘ).
2. Press the round link button on top of the bridge. The screen asks the bridge every 2 seconds for 30 seconds
   and is paired as soon as you press it. **Pair** starts again if you miss it.

The pairing (a bridge username) is kept in the app's own storage. If you delete it in the Hue app, Lights notices
and asks you to pair again. **Use another bridge** forgets it.

| Row | With the remote |
|---|---|
| All off | OK switches every light off |
| Rooms, then zones | OK on or off; ◀ ▶ brightness by 10 % (presses within 0.3 s are sent as one); Menu or hold OK for its scenes |

A room with only some lights on says "Some lights on". It reads `GET /api/<user>/groups` every 5 seconds while the
screen is open, and nothing when it is closed.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Your Hue Bridge on the home network, and `discovery.meethue.com` only if the bridge isn't found on the network. |
| `ACCESS_NETWORK_STATE` | Network service discovery. |
| `CHANGE_WIFI_MULTICAST_STATE` | Receiving the bridge's mDNS answer on Wi-Fi, only during the few seconds of the search. |

Plain `http://` is allowed (`res/xml/network.xml`) because the bridge's local API v1 is HTTP. No telemetry, no ads,
no background service, no libraries.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
TV_HOST=<tv ip> addons/hue/install.sh
```

To build only: `cd addons/hue && ./gradlew assembleRelease`. The JVM tests run with `./gradlew testReleaseUnitTest`.

## Limits

- It uses the bridge's API v1, which current bridges still answer. API v2 (HTTPS with the bridge's own certificate,
  and live updates) would be a later step.
- Colours aren't set from the TV: use a scene.

## Tested on

Not yet tried on a TV. The JVM tests cover the groups, scenes, pairing and error answers as API v1 documents them.

## Maintainer

[@feresg](https://github.com/feresg)
