# VPN Status

Whether the TV's traffic goes through NordVPN right now, full screen: **Protected** in green or **Not protected**
in red, the public IP, the city and country it appears to be in, and the ISP. **Quick connect** opens the NordVPN
TV app, where its Connect button connects to the fastest server. For the [Clean Your TV](../../README.md) launcher it
declares a [module card](../../docs/modules.md): once you answer **Show card** its tile reads "VPN Status" with a
blue glow. On any other Android TV home it is an ordinary app. A personal add-on, not made by or affiliated with
NordVPN.

- **Protected** comes from NordVPN's public ip-insights address
  (`https://api.nordvpn.com/v1/helpers/ips/insights`, no account or key). It says whether the public IP is one of
  NordVPN's servers, so it is right for split tunnelling and for the TV's actual route.
- **A VPN tunnel is up** comes from Android itself (the network in use has the VPN transport). The screen updates
  by itself when the tunnel goes up or down, about a second and a half after the network settles.
- **Quick connect** only opens the NordVPN app. Android doesn't let one app switch on another's VPN, and NordVPN
  has no public way to ask it to, so the last press is on NordVPN's own Connect button.
- If the NordVPN app (`com.nordvpn.android`) is missing or disabled, the button says so. A debloat list that
  disables it should leave it enabled.

## VPN in the launcher's top bar

The launcher shows the VPN itself, without this app: with `vpn_pill` on and `vpn_app` set to NordVPN in
`launcher/app/src/main/res/values/config.xml`, a green "VPN" pill with a lock sits next to the network pill while a
VPN is up, a red "VPN off" pill while it isn't, and pressing it opens NordVPN to connect:

```xml
<string name="vpn_app" translatable="false">com.nordvpn.android</string>
<bool name="vpn_pill">true</bool>
```

This app stays for when you want to see the public IP, city and ISP the TV appears with.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | One request to NordVPN's ip-insights address each time the screen opens, the network changes, or you press Refresh. |
| `ACCESS_NETWORK_STATE` | Whether the network in use is a VPN, and a callback when it changes, only while the screen is open. |

What is sent: the request itself, which NordVPN sees from your public IP. No account, no telemetry, no ads, no
background service, nothing stored.

## Build and install

JDK 17 and Android SDK platform 34 with build-tools 34.0.0, as for the launcher:

```bash
TV_HOST=<tv ip> addons/vpn-status/install.sh
```

To build only: `cd addons/vpn-status && ./gradlew assembleRelease`. The JVM tests run with
`./gradlew testReleaseUnitTest`.

## Tested on

Not yet tried on a TV. The JVM tests cover reading NordVPN's answer.

## Maintainer

[@feresg](https://github.com/feresg)
