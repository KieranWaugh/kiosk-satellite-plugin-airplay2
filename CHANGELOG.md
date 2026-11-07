# Changelog

## 0.3.0 - 2026-11-07

First public release.

- AirPlay 2 receiver (shairport-sync 5.6.2, NQPTP 1.2.9) for `armeabi-v7a`, in sync with HomePods and other AirPlay 2 speakers.
- Plays through AAudio from a helper process. Follows Kiosk Satellite's media volume and ducks while the assistant is active.
- NQPTP runs without root where the device lets apps open UDP 319/320, and through `su` otherwise.
- Advertises with Android's NsdManager.
- Now Playing through the bundled companion app, which the plugin installs and starts.
