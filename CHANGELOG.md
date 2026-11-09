# Changelog

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
