# AirPlay 2 Speaker for Kiosk Satellite

Makes a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) device an AirPlay 2 speaker. An iPhone, iPad or Mac can play to it alone, or group it with HomePods and other AirPlay 2 speakers and have them play in sync.

- **In sync with other AirPlay 2 speakers**, using [shairport-sync](https://github.com/mikebrady/shairport-sync) 5.6.2 and [NQPTP](https://github.com/mikebrady/nqptp) 1.2.9 built for Android.
- **One of Kiosk Satellite's own players.** Pick it under **Settings > Media Player > Player** and it plays through the kiosk's own output, the same as the Sendspin player: the echo canceller removes it from the microphone, so the wake word works over the music, and the media volume and the assistant's duck apply to it. The sender's volume works within that.
- **Shows the track in Now Playing**, the floating player and the ESPHome media sensors: title, artist, album, artwork and progress.

Not affiliated with Apple. AirPlay is a trademark of Apple Inc.

## Requirements

| | |
| --- | --- |
| Kiosk Satellite | 2026.11.14 or newer (plugin players) |
| Android | 8.0 (API 26) or newer, on a device that runs 32-bit ARM code (`armeabi-v7a`). Most 64-bit ARM devices do; x86 devices and 64-bit-only devices don't. |
| Timing ports | AirPlay 2 grouping needs UDP ports 319 and 320. Most Android kernels don't let apps open ports below 1024, so on most devices NQPTP needs **root** (`su`, for example Magisk). Allow Kiosk Satellite in your root manager. If the device lets apps open those ports, no root is needed and the plugin uses it automatically. |
| Other receivers | Only one AirPlay 2 receiver can use ports 319, 320 and 7000 on a device. Picking this player takes Kiosk Satellite's built-in AirPlay receiver offline; stop any other one yourself. |

Version 0.3.0, which played through its own AAudio stream, was tested on an Echo Show 5 (2nd gen) running the [Kiosk Satellite Edition of LineageOS](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite), grouped with a HomePod: steady sync within about ±1.5 ms. 0.4.0 plays through Kiosk Satellite's output instead and hasn't been tested on a device yet.

## Installation

### Community Plugins (Recommended)

AirPlay 2 Speaker is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=KieranWaugh/kiosk-satellite-airplay2)

Or search for `AirPlay 2 Speaker` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/KieranWaugh/kiosk-satellite-airplay2` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

Then:

1. If your device needs root for the timing ports, allow **Kiosk Satellite** in your root manager. Magisk 31 shows no prompt: add it in advance under **Superuser**.
2. Set **Speaker name** and enable the plugin.
3. Under **Settings > Media Player > Player**, pick **AirPlay 2 Speaker: <speaker name>** under **This device**.

The speaker shows up in the AirPlay menu only while it is the picked player. The status line shows `Running as "<name>"` and whether timing runs with or without root, or says to pick the player.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | Kiosk Satellite | The name in the AirPlay menu. Give each device its own. |
| Sync offset (ms) | 0 | Moves playback against the other speakers in a group. Negative plays earlier. Tune it by ear next to a HomePod. |
| Log sync statistics | Off | Writes shairport-sync's sync figures to the app log every few seconds. |

## What it installs and where data goes

- **Two helper programs**, shairport-sync and NQPTP, from this plugin's package. They run only while the plugin's player is the pick, and stop when another player is picked or the plugin is disabled. NQPTP runs as root only where the timing ports need it.
- **Nothing else.** Up to 0.3.0 the plugin installed a Now Playing companion app (`io.github.kieranwaugh.airplay2.nowplaying`). It isn't needed any more, and you can uninstall it like any app.
- **Network:** while it is the pick, the AirPlay speaker listens on your local network (TCP 7000, UDP 319/320 and the ports AirPlay negotiates) and is advertised with Android's own mDNS. Audio and track info pass between shairport-sync and the plugin over pipes and the device's loopback address only. Nothing is sent anywhere else.
- **Files:** settings, a random device ID and the shairport-sync config, in Kiosk Satellite's private storage.

## Known limits

- **No remote control from Now Playing.** In AirPlay 2 sessions the sender gives a receiver no way to send play, pause or skip back, so Now Playing shows the track without buttons. Control playback from the sender.
- **One ABI.** Only `armeabi-v7a` fits the plugin's 4 MB package limit.

## How it works

| Part | Runs as | Notes |
| --- | --- | --- |
| shairport-sync | Kiosk Satellite's user | A separate process started through `/system/bin/linker`, so a crash can't take Kiosk down. A custom backend (`native/sps/audio_plugin.c`) sends its PCM to the plugin over stdout, and the plugin writes it to the player's audio output. Every 100 ms the plugin sends back the output's timestamp (which frame was heard when, on the clock the echo canceller reads), from which the backend works out its delay for sync. |
| NQPTP | Kiosk Satellite's user, or root | Started without root when ports 319/320 can be opened, otherwise through `su`. Shares its clock with shairport-sync through a file. |
| mDNS | Kiosk Satellite | shairport-sync's DNS-SD records are advertised with Android's NsdManager. |
| Now Playing | Kiosk Satellite | The plugin decodes the sender's track info from shairport-sync's metadata and sends it as the player's state. Progress is worked out on the sender's clock, estimated from the messages' own timestamps, so a device clock that's off doesn't matter. |

## Building

The release workflow builds everything on `ubuntu-24.04`. Locally (Linux or macOS):

1. Install JDK 17 or newer, an Android SDK with a platform and build-tools, and NDK `27.3.13750724`. Install `autoconf`, `automake`, `libtool`, `pkg-config`, `xxd` and `plistutil`. On Debian or Ubuntu, `plistutil` is in `libplist-utils`.
2. `native/build.sh` downloads every source from its upstream, checks it against a pinned SHA-256 and builds `native/out/armeabi-v7a/`.
3. `python3 tools/test.py`, then `python3 tools/build.py`. The ZIP is in `dist/`.

Changes to the upstream sources are in `native/sps/` (the plugin output backend and a small patch) and `native/compat/` (stand-ins for what Android's C library lacks: pthread cancellation, POSIX shared memory, libuuid and dns_sd).

## License

The plugin and build scripts are [MIT](LICENSE). The SDK interfaces in `sdk/` are Apache-2.0. The packaged native programs are separate works under their own licences:
- shairport-sync: MIT-style; see its `COPYING` and `LICENSES`.
- NQPTP: GPL-2.0.
- OpenSSL: Apache-2.0.
- FFmpeg, libgcrypt, libgpg-error, libplist and libconfig: LGPL-2.1 or later.
- libsodium: ISC.
- popt: MIT.

`native/build.sh` names the exact source version and checksum of each.
