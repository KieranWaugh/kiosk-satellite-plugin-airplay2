# Changelog

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
