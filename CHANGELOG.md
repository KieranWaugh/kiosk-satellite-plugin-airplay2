# Changelog

## 1.1.0 - 2026-11-10

Fixes from the Community Plugins review. Needs Kiosk Satellite 2026.11.16 or newer.

- **A device on the network could crash Kiosk Satellite.** A sender's Now Playing info (passed through by shairport-sync) with a huge length, or a dictionary that refers to the same child at every level, made the plist parser run out of memory, and the error took down the app. The parser now checks every length and offset against the data before allocating, and caps how many objects it builds (20,000), how many bytes it copies (4 MB) and how deep it goes (32). Every thread the plugin starts now catches `Throwable`.
- **Callbacks return at once.** `onEvent("shizuku.state")` and `configure` used to take locks the supervisor holds during root commands (up to about 28 s), so Kiosk Satellite could time the callback out and turn the plugin off. They now only note the change for the supervisor.
- **`stop()` returns within 2.5 s** (it could take about 6 s). Nothing starts a helper after `stop()`, even if it came while one was starting: before, shairport-sync could still be launched and was left running.
- **Leftover redirect rules are removed.** With Shizuku the rules stayed until the next reboot, because Kiosk Satellite closed the plugin's Shizuku connection before the plugin stopped. Kiosk Satellite 2026.11.16 keeps it open during `stop()`, so the rules are now removed when AirPlay stops, with su as a fallback. If that fails (a crash, or a power cut mid-session), the next start removes them, and it never adds a second copy. The first start after upgrading from 1.0.x also checks for rules an earlier version left.
- **mDNS from other apps.** The service records shairport-sync hands the plugin now go to a random port and carry a secret token made for each start (in shairport-sync's environment, which other apps can't read). Only `_airplay._tcp` and `_raop._tcp` are advertised, at most four services. Chunked metadata is capped at four items in progress, 1.25 MB each, for 10 s.
- **Licences.** The package now has the full licence texts of every bundled component and a third-party notice with versions, upstream sources, checksums and changes (`assets/licenses/`). Each release has the pinned source tarballs and the patches attached.
- README: the speaker has no password, and when the redirect rules are removed with Shizuku and with su.

## 1.0.3 - 2026-11-10

- README: the devices it has been tested on (Echo Show 5 and Echo Dot, 2nd gen) with their root methods, how to set up Magisk or Shizuku on each, and why the plugin doesn't keep Shizuku running across reboots.

## 1.0.2 - 2026-11-10

- With Shizuku, a missing redirect rule is now put back: the repair command came straight after the check, and Kiosk Satellite refuses a Shizuku command within 250 ms of the previous one. Shizuku commands are now spaced out, and the first check waits 30 seconds after AirPlay starts.
- README follows the Community Plugins layout, with the install button, settings and where data goes.

## 1.0.1 - 2026-11-10

- Renamed to **AirPlay 2 Speaker for Kiosk Satellite (multiroom and sync)**.
- README trimmed to installation, how it works and the timing-port methods.

## 1.0.0 - 2026-11-10

NQPTP no longer runs as root, and the plugin has an icon.

- Where apps can't open UDP 319/320, a short root command adds `iptables` rules that redirect them to 10319/10320, and NQPTP (patched to listen there) runs as Kiosk Satellite. PTP it sends leaves from 319/320, so other speakers answer it as before. The rules are removed when AirPlay stops.
- Root can now come from Shizuku running as root (new `shizuku` capability), which survives image updates, as well as from `su`. Shizuku is tried first. With Shizuku the rules are checked every 30 seconds and put back if missing.
- NQPTP gets its real-time priority through root, as it had when it ran as root.
- If the kernel has no `iptables` NAT, NQPTP runs as root through `su` as before.
- Where only IPv4 can be redirected (no `ip6tables` NAT, as on the Echo Show 5's 4.9 kernel), shairport-sync listens on IPv4 only (new `native/sps/ipv4-only.patch`). An iPhone playing to the speaker alone connects over IPv6 if it can and sends its timing over IPv6 too, which NQPTP would never hear: the speaker joined but stayed silent.
- An NQPTP an earlier version left running as root is stopped when the redirect is set up.

## 0.4.0 - 2026-11-09

Now one of Kiosk Satellite's own players. Needs Kiosk Satellite 2026.11.14 or newer.

- Pick **AirPlay 2 Speaker** under Settings > Media Player > Player. shairport-sync, NQPTP and the mDNS records run only while it is the pick, so the speaker appears in the AirPlay menu only where it can play.
- Plays through the player's audio output: the echo canceller now removes AirPlay from the microphone, and Kiosk Satellite applies its media volume and the assistant's duck. The sender's volume still applies.
- Sync uses the output's timestamps, passed to shairport-sync's new plugin backend, in place of its own AAudio stream.
- Now Playing comes from the player's state, without buttons, since AirPlay 2 gives no remote control.
- Removed: the Now Playing companion app (uninstall `io.github.kieranwaugh.airplay2.nowplaying` if 0.3.0 installed it), the **Follow the media volume** and **Duck to** settings, and the `time.apple.com` lookup.

## 0.3.0 - 2026-11-07

First public release.

- AirPlay 2 receiver (shairport-sync 5.6.2, NQPTP 1.2.9) for `armeabi-v7a`, in sync with HomePods and other AirPlay 2 speakers.
- Plays through AAudio from a helper process. Follows Kiosk Satellite's media volume and ducks while the assistant is active.
- NQPTP runs without root where the device lets apps open UDP 319/320, and through `su` otherwise.
- Advertises with Android's NsdManager.
- Now Playing through the bundled companion app, which the plugin installs and starts.
