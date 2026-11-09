#!/usr/bin/env python3
"""Compile the plugin against the SDK and Android platform, run its tests, then the tool tests."""
from datetime import datetime, timezone
import os
import plistlib
import sys
from pathlib import Path
import subprocess
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
try:
    platform = str(android_platform(sdk_root))
except ValueError as error:
    raise SystemExit(str(error)) from error


def now_playing(merge_policy, fields):
    """An updateMRNowPlayingInfo message as the iPhone sends it (shairport-sync's ssnc/copl)."""
    npi = {f'kMRMediaRemoteNowPlayingInfo{key}': value for key, value in fields.items()}
    return plistlib.dumps({'type': 'updateMRNowPlayingInfo', 'params': {'mergePolicy': merge_policy, 'params': npi}},
                          fmt=plistlib.FMT_BINARY)


def at(seconds):
    return datetime.fromtimestamp(seconds, timezone.utc).replace(tzinfo=None)


T = 1762560000
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java')), *sorted((root / 'tests').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='kiosk-plugin-test-') as directory:
    classes = Path(directory) / 'classes'
    fixtures = Path(directory) / 'fixtures'
    fixtures.mkdir()
    (fixtures / 'first.bplist').write_bytes(now_playing('replace', {
        'Title': 'Song One', 'Artist': 'Artist', 'Album': 'Album', 'Duration': 200.0, 'ElapsedTime': 10.0,
        'PlaybackRate': 1.0, 'Timestamp': at(T), 'ArtworkData': bytes(range(256)) + bytes(44), 'ArtworkIdentifier': 'a1',
    }))
    (fixtures / 'second.bplist').write_bytes(now_playing('merge', {'Title': 'Song Two', 'Timestamp': at(T + 5)}))
    (fixtures / 'third.bplist').write_bytes(now_playing('merge', {'PlaybackRate': 0.0}))
    (fixtures / 'fourth.bplist').write_bytes(now_playing('merge', {'PlaybackRate': 1.0}))

    subprocess.run([tool('javac'), '--release', '8', '-encoding', 'UTF-8', '-cp', platform, '-d', str(classes), *map(str, sources)], check=True)
    # The tests only touch plain Java; the platform jar's Android stubs just need to load.
    for test, *test_args in [
        ['io.github.kieranwaugh.airplay2.PluginLogicTest'],
        ['io.github.kieranwaugh.airplay2.AudioBridgeTest'],
        ['io.github.kieranwaugh.airplay2.NowPlayingTest', str(fixtures)],
    ]:
        subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([str(classes), platform]), test, *test_args], check=True)

for script in ['test_android_sdk.py', 'test_plugin_manifest.py', 'test_plugin_assets.py']:
    subprocess.run([sys.executable, str(root / 'tools' / script)], check=True)
