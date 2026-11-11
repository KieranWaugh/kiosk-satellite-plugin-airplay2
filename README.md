# AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync)

Makes a [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) device an AirPlay 2 speaker. An iPhone, iPad or Mac can play to it alone, or group it with HomePods and other AirPlay 2 speakers and have them play in sync. It plays through Kiosk Satellite's own player, so the echo canceller, the media volume and the assistant's duck apply, and Now Playing shows the track.

Not affiliated with Apple. AirPlay is a trademark of Apple Inc.

Needs Kiosk Satellite 2026.11.16 or newer, on Android 8.0 or newer on a device that runs 32-bit ARM code (`armeabi-v7a`).

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

**The speaker has no password.** While it is the picked player, anyone on your network can play to it, as with most AirPlay speakers. Only pick it on a network you trust.

## How it works

The plugin runs two open-source programs, built for Android, as separate processes:

- **[shairport-sync](https://github.com/mikebrady/shairport-sync)** is the AirPlay 2 receiver. It runs as Kiosk Satellite's own user. It sends its audio to the plugin, which writes it to Kiosk Satellite's player output. The plugin passes back when each frame is actually heard, so shairport-sync can keep in sync with the other speakers. Track info goes to Now Playing.
- **[NQPTP](https://github.com/mikebrady/nqptp)** keeps the shared clock (PTP) that grouped AirPlay 2 speakers play to. PTP uses UDP ports 319 and 320.
- **mDNS:** the speaker is advertised with Android's own NsdManager.

The hard part is ports 319 and 320. Most Android kernels don't let apps open ports below 1024. Where they're blocked, a short root command adds `iptables` rules that redirect 319 and 320 to 10319 and 10320, and NQPTP runs as an ordinary app on those. PTP that NQPTP sends goes out from 319 and 320, so other speakers answer it as usual. Root is also used to give NQPTP real-time priority and to remove the rules again. No root process keeps running.

When the rules are removed:

- **When AirPlay stops** (another player is picked, or the plugin is turned off, updated or removed), with Shizuku or su, whichever added them. Kiosk Satellite 2026.11.16 and newer keep the plugin's Shizuku access open while it stops, which is why the plugin needs that version.
- **At the next start**, if they couldn't be removed then: for example Shizuku or su had stopped working, Kiosk Satellite or the plugin crashed, or the device lost power mid-session. A restart of the device also clears them.

Rules left in place are harmless while nothing listens on 10319 and 10320: PTP sent to the device is simply dropped.

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
- **Drawbacks:** Shizuku has to be started again after each reboot, from a computer with root ADB, and on some images root ADB has to stay on while it runs (see [why](#why-the-plugin-doesnt-keep-shizuku-running-across-reboots)). Shizuku running as the shell user (uid 2000) isn't enough; it has to be root.

### su (such as Magisk)

Allow **Kiosk Satellite** under **Magisk > Superuser**. Magisk 31 shows no prompt, so add it in advance.

- **Benefits:** set up once, and it keeps working across reboots with no further steps.
- **Drawbacks:** an image update removes Magisk until you flash it again. Rooting the device takes more work than starting Shizuku.

### Root NQPTP (fallback)

If `su` works but the kernel has no `iptables` NAT for the redirect, NQPTP runs as root itself, as in older versions.

- **Benefits:** works on kernels that can't redirect.
- **Drawbacks:** a root process runs for as long as AirPlay is on. Needs `su`; Shizuku can't do it.

### Why the plugin doesn't keep Shizuku running across reboots

Shizuku stops at every reboot, and starting it as root again needs root ADB (`adb root`), because these images have no `su` until you add Magisk. A plugin could do that itself at boot, by keeping ADB listening on the network and signing in to it with a key of its own. This plugin doesn't, because:

- **It leaves a root shell open on the network.** ADB as root on port 5555 is open to anything that can reach it. On the Echo Dot image ADB doesn't check keys at all (`ro.adb.secure=0`), so anyone on the Wi-Fi would get root with `adb connect`, no key needed.
- **The key is root.** Elsewhere the plugin would have to store a root ADB key in its settings, where Kiosk Satellite's configuration exports and Fleet Management's plugin secrets can carry it off the device.
- **Root ADB can't be switched off behind it.** On the Echo Dot image, `adb unroot` and `biscuit_service adb root off` restart ADB, and that stops Shizuku too. Shizuku as root only lasts as long as root ADB stays on.
- **It's a lot of security-sensitive code** (an ADB client with its own sign-in) to do something the image could do safely.

Use `su` (Magisk) for a device that has to come back by itself after a power cut. For Shizuku without Magisk, the safe fixes belong in the image: ADB that requires keys, starting Shizuku as root at boot, or letting apps open ports 319 and 320 (which needs no root at all).

## Tested devices

| Device | Image | Kiosk Satellite | Root | Result |
| --- | --- | --- | --- | --- |
| Echo Show 5 (2nd gen, `cronos`) | [Kiosk Satellite Edition of LineageOS](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite) 18.1 (Android 11), kernel 4.9 | 2026.11.14 | Magisk 31.1 (`su`) | Plays alone and grouped with a HomePod and an Echo Dot. Sync settles within about 2 ms |
| Echo Show 5 (2nd gen) | same | 2026.11.16 | Shizuku 13.6 as root, started through `adb root` | 1.1.0 plays. The rules are removed when AirPlay stops (another player picked, or the plugin turned off), and rules left by 1.0.x are tidied on the first start. Shizuku has to be started again after each reboot. The 30-second rule repair was fixed in 1.0.2 and not tested again since |
| Echo Dot (2nd gen, `biscuit`) | [Echo Dot image](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite/blob/main/docs/echo-dot.md), LineageOS 18.1 (Android 11), kernel 4.9 | 2026.11.14 | Magisk 31.1 (`su`) | Plays alone and grouped with a HomePod and an Echo Show. Sync settles within about 2 ms. Comes back by itself after a power cut. Uses about 14 MB of the Dot's memory |

Neither kernel has `ip6tables` NAT, so on both the plugin listens on IPv4 only.

### Root on these devices

**Echo Show 5 with Magisk.** In TWRP, **Advanced > ADB Sideload**, then `adb sideload Magisk.zip` (a copy of `Magisk-v31.1.apk`). Reboot, install the Magisk app with `adb install`, and allow **Kiosk Satellite** under **Magisk > Superuser** (Magisk 31 shows no prompt, so add it in advance).

**Echo Dot with Magisk.** The Dot has no screen, so everything goes through `adb`, with the Dot plugged into a computer's USB port (not a hub):

1. `adb reboot recovery`, then check `adb shell bcbtool get_active` prints `a`. If not: `adb shell bcbtool set_active a`, then `adb reboot recovery` again, so TWRP starts on slot A.
2. Back up both boot slots: `adb shell dd if=/dev/block/by-name/boot_a of=/tmp/boot_a.img` (and `boot_b`), then `adb pull` them.
3. `adb push Magisk.zip /tmp/` and `adb shell twrp install /tmp/Magisk.zip`. Check the `Target image` line: the bootloader always starts slot A, so if Magisk patched `boot_b`, copy it across with `adb shell dd if=/dev/block/by-name/boot_b of=/dev/block/by-name/boot_a`.
4. `adb reboot`, `adb install Magisk-v31.1.apk`.
5. Allow Kiosk Satellite without the Magisk app's screen, with root ADB on for these commands only:

   ```sh
   adb shell dumpsys package me.jxl.kiosk_satellite | grep userId   # e.g. userId=10018
   adb shell biscuit_service adb root on && adb root
   adb shell 'magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(10018,2,0,1,0)"'
   adb unroot && adb shell biscuit_service adb root off
   ```

**After an image update** Magisk is gone on both devices (the update rewrites the boot partitions), so redirecting the ports stops working until you flash it again. The plugin and the Magisk permission survive. Image updates only install when you choose them.

**Shizuku as root.** Turn on root ADB (on the Echo Dot: `adb shell biscuit_service adb root on`), then `adb root`, install Shizuku, and start it as root with `adb shell 'p=$(pm path moe.shizuku.privileged.api | sed s/package://); $(dirname $p)/lib/arm/libshizuku.so'` (or the command Shizuku shows under **Start via connected computer**). It should end with `shizuku_starter exit with 0`. Grant Kiosk Satellite access under **Settings > Device > Shizuku**; on a device without a screen, `adb shell pm grant me.jxl.kiosk_satellite moe.shizuku.manager.permission.API_V23` (Android marked it granted on the Echo Dot; the plugin wasn't run through Shizuku there). Leave root ADB on, since switching it off stops Shizuku on the Echo Dot image, and see [above](#why-the-plugin-doesnt-keep-shizuku-running-across-reboots) for what that exposes.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Speaker name | Kiosk Satellite | The name in the AirPlay menu. Give each device its own. |
| Sync offset (ms) | 0 | Moves playback against the other speakers in a group. Negative plays earlier. Tune it by ear next to a HomePod. |
| Log sync statistics | Off | Writes shairport-sync's sync figures to the app log every few seconds. |

## Where data goes

- **Nowhere outside your network.** While the plugin is the picked player, the speaker listens on your local network (TCP 7000, UDP 319/320 and the ports AirPlay negotiates) and is advertised there with Android's own mDNS. Audio and track info pass between shairport-sync and the plugin over pipes and the device's loopback address only. The service records shairport-sync hands the plugin carry a secret made for each start, so other apps on the device can't advertise services through it.
- **Files:** the settings, a random device ID, the shairport-sync config and an empty marker file while the port redirect may be in place, in Kiosk Satellite's private storage.

## License

The plugin and build scripts are [MIT](LICENSE). The SDK interfaces in `sdk/` are Apache-2.0. The packaged native programs are separate works under their own licences: shairport-sync (MIT-style), NQPTP (GPL-2.0), OpenSSL (Apache-2.0), FFmpeg, libgcrypt, libgpg-error, libplist and libconfig (LGPL-2.1 or later), libsodium (ISC) and popt (MIT).

- **Notices and licence texts:** [`assets/licenses/`](assets/licenses/), which the plugin package carries too. [`THIRD-PARTY-NOTICES.txt`](assets/licenses/THIRD-PARTY-NOTICES.txt) lists each component's version, licence, upstream source, checksum and changes.
- **Source:** each [release](https://github.com/KieranWaugh/kiosk-satellite-plugin-airplay2/releases) has the pinned upstream tarballs and `native-sources.tar.gz` (the build script, patches and added files) attached next to the plugin ZIP. `native/build.sh` downloads each tarball, checks its SHA-256, applies the patches in `native/` and builds both programs, so you can rebuild them or relink with modified libraries.
