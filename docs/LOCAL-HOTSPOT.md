# In-app hotspot ("Make a network"): design note

Status: design only (owner asked 7 Nov 13:1x; code after the owner's OK). Lines marked **UNVERIFIED** are what the
Android docs or AOSP say, not yet seen on our phones. The test plan below checks each one.

## Why

Today nearby play needs a shared Wi-Fi. With no router (a train, a park), the players must turn on a phone hotspot in
Settings by hand. "Make a network" does it inside the game, so the table needs no Settings at all.

## Flow

1. **Host** taps **Make a network**. The app calls `WifiManager.startLocalOnlyHotspot` (LOHS). Android picks a random
   SSID and a random WPA2 password for this start. The app never chooses or stores them.
2. The host screen shows one QR in the standard Wi-Fi format, `WIFI:T:WPA;S:<ssid>;P:<password>;;` (with `\`, `;`,
   `,`, `:` and `"` escaped), and the room's **short code** under it. Because it is the standard format, a phone camera
   can also join the network without our app.
3. The room starts as usual (`NearbyHost`), listening on the hotspot interface too.
4. **Joiner** scans the QR in the game (or types the network name and password), and the app joins that network (see
   "Join paths").
5. On that network, the normal join runs: the room shows up in the nearby list (mDNS), or the player types the short
   code. The host is also the network's gateway, so the joiner tries the gateway address first for a short code.
6. When the room ends (or the host leaves nearby play), the host closes the reservation and the hotspot stops. The
   joiner releases its network request, and Android goes back to the saved Wi-Fi.

## Android versions

| Android | Host ("Make a network") | Joiner |
|---|---|---|
| 7 (API 24, our minSdk) | Not offered (LOHS starts at API 26) | `addNetwork` + `enableNetwork`, or type it in Settings |
| 8, 9 (API 26 to 28) | LOHS; SSID and password from `getWifiConfiguration()` | `addNetwork` + `enableNetwork` (still allowed on these versions) |
| 10 (API 29) | LOHS, as above | `WifiNetworkSpecifier` (`ConnectivityManager.requestNetwork`) |
| 11, 12 (API 30 to 32) | LOHS; `getSoftApConfiguration()` | `WifiNetworkSpecifier` |
| 13+ (API 33+) | LOHS; `getSoftApConfiguration().wifiSsid` | `WifiNetworkSpecifier` |

- `WifiNetworkSpecifier` (Android 10+) shows a system "Connect to <ssid>?" dialog, and the network is only for our app.
  It has no internet, so Android keeps mobile data for everything else. Our sockets must use that network:
  `ConnectivityManager.bindProcessToNetwork(network)` while nearby play is open, and undo it after. While bound, the
  game's own internet traffic (analytics, ads, online play) also goes to the hotspot and fails. Games must know this.
- `WifiNetworkSuggestion` is not used. It is for networks the phone should keep joining later, it waits for the system
  to choose, and it saves the network. We want one session only.
- Joining with a phone camera (outside our app) makes the hotspot a normal saved Wi-Fi with "no internet". It works for
  play if the phone stays on it. Android may switch back to mobile data or show "No internet" (**UNVERIFIED**).
- mDNS on these networks is the biggest unknown. NSD may not find the room on a specifier network, and the host may
  not advertise on the hotspot interface (**UNVERIFIED**, both). The short code (gateway first) is the path that does
  not need mDNS.
- The host's short code must come from the hotspot interface address (`ap0`, `swlan0` or `wlan1`), not from mobile
  data. Today `wifiFirst()` puts `wlan*` first, and on a phone where the hotspot is `wlan1` it can pick the home Wi-Fi
  address first (assumption A12). The code change picks the LOHS interface address by name.

## Permissions

| Who | Android | What | Prompt |
|---|---|---|---|
| Host | 8 to 12 (API 26 to 32) | `ACCESS_FINE_LOCATION` (declare with `maxSdkVersion="32"`), and Location turned on | Yes, runtime |
| Host | 13+ (API 33+) | `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"` | Yes, runtime ("Nearby devices") |
| Host and joiner | all | `CHANGE_WIFI_STATE`, `ACCESS_WIFI_STATE` (install-time) | No |
| Joiner | 10+ | `WifiNetworkSpecifier` needs no location for an exact SSID (**UNVERIFIED**); the system dialog asks the player | System dialog |

These permissions must not reach every game by default. So the hotspot goes in a separate artifact,
`nearby-hotspot` (Android only). A game adds it only if it shows "Make a network". The core `nearby` artifact keeps
its two install-time permissions. A location permission changes the Play listing and Data safety form: **check with
the owner before filing.**

## The host's own internet

- LOHS shares no internet. Joiners get none from the host.
- The host keeps mobile data.
- Its own Wi-Fi client stays on only if the phone runs Wi-Fi client and hotspot at the same time
  (`WifiManager.isStaApConcurrencySupported()`, API 30+). Otherwise, Android drops the home Wi-Fi while the hotspot
  runs (**UNVERIFIED** on the Lava and the Mi A3).
- LOHS fails if the normal Settings hotspot (tethering) is on. The app then shows "Turn off your hotspot first".
- The app must call it while in front. AOSP refuses a background start. Android stops LOHS when our process dies.
  What it does when the app goes to the background while LOHS runs is **UNVERIFIED** (test: Home 30 s).

## Battery

A hotspot costs the host extra battery. Rules:

- Start it only on tap, never by itself.
- Stop it when the room ends, the host leaves, or the lobby has had no joiner for 5 minutes.
- Show "Network on" on the host screen while it runs.
- Measure in the test plan: battery level and temperature before and after a 20-minute session on the host.

## iOS (week 3)

- **Host:** not possible. iOS has no public API to start a hotspot, so "Make a network" is hidden on iOS.
- **Joiner:**
  - The iPhone camera reads the `WIFI:` QR and joins.
  - In the game, `NEHotspotConfiguration(ssid:passphrase:isWEP:)` with `joinOnce = true` joins too. It needs the
    "Hotspot Configuration" entitlement.
  - Typing the network in Settings also works.
- Then the short code or the nearby list works as on Android. This needs the Local Network permission, already in the
  week 3 plan.

## Security

- Android makes the password at random for each hotspot start. A new room means a new password.
- The library never logs the SSID or the password: no `Log`, no exception message, no analytics, no saved file.
- The QR is shown only on the host screen. The UI tells the host: "Show this only to the players at the table".
- Some Android 11+ phones may run WPA3 transition instead of WPA2-PSK (**UNVERIFIED**). The QR stays `T:WPA` either
  way.
- The network is only a road. The game data on it stays end-to-end encrypted, and a short-code join still needs the
  host's Accept and the check code. A person who gets the password can join the network, but not the room without
  Accept.

## Test plan (Lava, Android 16, and Mi A3, Android 11)

Important: both phones use adb over Wi-Fi today. A phone that joins the hotspot leaves the home Wi-Fi, and so does a
host without STA and AP concurrency, so adb over Wi-Fi drops. ceo's hard rule (no network changes over a Wi-Fi adb
link) applies. So:

- **0. Read-only check, under the locks:** `dumpsys wifi` concurrency lines on both phones, to see which phone keeps
  adb while it hosts.
- **Every step that moves a phone off the home Wi-Fi** runs only when the script sees that phone on USB adb (as for
  `wifi_drop`). Otherwise it is OWNER: the owner taps the steps, and the app saves screenshots on the phone.
- **Cases**, in both directions (Lava hosts with the Mi A3 joining, and the reverse), 3 runs each, with a screenshot
  pair for each:
  - Make a network.
  - QR scan join.
  - Typed join.
  - The room is found by mDNS (or not: then UNVERIFIED is the finding, not a failure, if the short code works).
  - Short-code join.
  - Full match.
  - Host Home 30 s.
  - Host leaves: does the hotspot stop?
  - The joiner gets its home Wi-Fi back after the room.
  - Tethering already on gives the error message.
  - Permission denied gives the error message.
- **Battery:** the host's battery level and temperature before and after a 20-minute session.
- **End:** both phones back on the home Wi-Fi and on adb before the locks are freed. Uninstall the test builds, and run
  the Play-apps check as in every kit.
