# AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync)

Makes a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) device an AirPlay 2 speaker. An iPhone, iPad or Mac can play to it alone, or group it with HomePods and other AirPlay 2 speakers and have them play in sync. It plays through Kiosk Satellite's own player, so the echo canceller, the media volume and the assistant's duck apply, and Now Playing shows the track.

Not affiliated with Apple. AirPlay is a trademark of Apple Inc.

Needs Kiosk Satellite 2026.11.14 or newer, on Android 8.0 or newer on a device that runs 32-bit ARM code (`armeabi-v7a`).

## Installation

### Community Plugins (Recommended)

AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync) is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=KieranWaugh/kiosk-satellite-plugin-airplay2)

Or search for `AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync)` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/KieranWaugh/kiosk-satellite-plugin-airplay2` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

### Setup

1. If your device needs root for the timing ports (most do), set up one of the [methods below](#timing-ports-which-method).
2. Set **Speaker name** and enable the plugin.
3. Under **Settings > Media Player > Player**, pick **AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync): <speaker name>** under **This device**.

The speaker shows up in the AirPlay menu while it is the picked player. The plugin's status line shows the name and how the timing ports are reached: `no root needed`, `PTP ports redirected with Shizuku`, `PTP ports redirected with su` or `timing (NQPTP) runs as root`.

Only one AirPlay 2 receiver can run on a device, so picking this player takes Kiosk Satellite's built-in AirPlay receiver offline.

## How it works

The plugin runs two open-source programs, built for Android, as separate processes:

- **[shairport-sync](https://github.com/mikebrady/shairport-sync)** is the AirPlay 2 receiver. It runs as Kiosk Satellite's own user. It sends its audio to the plugin, which writes it to Kiosk Satellite's player output. The plugin passes back when each frame is actually heard, so shairport-sync can keep in sync with the other speakers. Track info goes to Now Playing.
- **[NQPTP](https://github.com/mikebrady/nqptp)** keeps the shared clock (PTP) that grouped AirPlay 2 speakers play to. PTP uses UDP ports 319 and 320.
- **mDNS:** the speaker is advertised with Android's own NsdManager.

The hard part is ports 319 and 320. Most Android kernels don't let apps open ports below 1024. Where they're blocked, a short root command adds `iptables` rules that redirect 319 and 320 to 10319 and 10320, and NQPTP runs as an ordinary app on those. PTP that NQPTP sends goes out from 319 and 320, so other speakers answer it as usual. Root is also used to give NQPTP real-time priority and to remove the rules when AirPlay stops. No root process keeps running.

Where the kernel can redirect IPv4 but not IPv6 (as on the Echo Show 5), shairport-sync accepts connections over IPv4 only. Otherwise an iPhone playing to the speaker alone could connect and send its timing over IPv6, and nothing would play.

## Timing ports: which method

The plugin picks the first method that works, in this order.

### No root

If the device already lets apps open ports 319 and 320, NQPTP uses them directly.

- **Benefits:** nothing to set up, and nothing runs as root.
- **Drawbacks:** most Android devices don't allow it.

### Shizuku running as root

Start [Shizuku](https://shizuku.rikka.app/) as root (on Kiosk Satellite's Echo builds, turn on **Rooted debugging** in Developer options and start Shizuku through `adb root`). Then grant access under **Settings > Device > Shizuku** in Kiosk Satellite.

- **Benefits:** survives image updates, because nothing changes the system or boot partitions. No toast pops up for each command. The plugin checks the rules every 30 seconds and puts them back if something removed them.
- **Drawbacks:** Shizuku has to be started again after each reboot. Shizuku running as the shell user (uid 2000) isn't enough; it has to be root.

### su (such as Magisk)

Allow **Kiosk Satellite** under **Magisk > Superuser**. Magisk 31 shows no prompt, so add it in advance.

- **Benefits:** set up once, and it keeps working across reboots with no further steps.
- **Drawbacks:** an image update removes Magisk until you flash it again. Rooting the device takes more work than starting Shizuku.

### Root NQPTP (fallback)

If `su` works but the kernel has no `iptables` NAT for the redirect, NQPTP runs as root itself, as in older versions.

- **Benefits:** works on kernels that can't redirect.
- **Drawbacks:** a root process runs for as long as AirPlay is on. Needs `su`; Shizuku can't do it.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | Kiosk Satellite | The name in the AirPlay menu. Give each device its own. |
| Sync offset (ms) | 0 | Moves playback against the other speakers in a group. Negative plays earlier. Tune it by ear next to a HomePod. |
| Log sync statistics | Off | Writes shairport-sync's sync figures to the app log every few seconds. |

## Where data goes

- **Nowhere outside your network.** While the plugin is the picked player, the speaker listens on your local network (TCP 7000, UDP 319/320 and the ports AirPlay negotiates) and is advertised there with Android's own mDNS. Audio and track info pass between shairport-sync and the plugin over pipes and the device's loopback address only.
- **Files:** the settings, a random device ID and the shairport-sync config, in Kiosk Satellite's private storage.

## License

The plugin and build scripts are [MIT](LICENSE). The SDK interfaces in `sdk/` are Apache-2.0. The packaged native programs are separate works under their own licences: shairport-sync (MIT-style), NQPTP (GPL-2.0), OpenSSL (Apache-2.0), FFmpeg, libgcrypt, libgpg-error, libplist and libconfig (LGPL-2.1 or later), libsodium (ISC) and popt (MIT). `native/build.sh` downloads each from its upstream and pins its version and checksum.
