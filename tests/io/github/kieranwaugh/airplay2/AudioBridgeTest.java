package io.github.kieranwaugh.airplay2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.AudioOutput;

/** Feeds AudioBridge the byte stream native/sps/audio_plugin.c writes. Run with java -ea. */
public class AudioBridgeTest {
    /** Records what reaches the kiosk's output. */
    static final class FakeOutput implements AudioOutput {
        final int rate, channels;
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int flushes;
        boolean closed;
        long written;

        FakeOutput(int rate, int channels) {
            this.rate = rate;
            this.channels = channels;
        }

        @Override public int write(ByteBuffer b, int frames, int timeoutMs) {
            if (closed) return -1;
            int take = Math.min(frames, 3); // short writes, so the bridge has to loop
            byte[] chunk = new byte[take * channels * 2];
            b.get(chunk);
            pcm.write(chunk, 0, chunk.length);
            written += take;
            return take;
        }
        @Override public boolean getTimestamp(long[] out) { return false; }
        @Override public long framesWritten() { return written; }
        @Override public void flush() { flushes++; written = 0; }
        @Override public void close() { closed = true; }
    }

    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("AudioBridgeTest hung (a closed output that keeps being written?)");
            System.exit(1);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        stream();
        badStream();
        closedOutput();
        assert AudioBridge.timestampLine(7, 44100, 123456789L).equals("ts 7 44100 123456789");
        System.out.println("AudioBridgeTest passed");
    }

    private static byte[] message(char type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'S').put((byte) 'P').put((byte) type).put((byte) 0).putInt(payload.length).put(payload);
        return b.array();
    }

    private static byte[] ints(int... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : v) b.putInt(x);
        return b.array();
    }

    private static byte[] frames(int n, int start) {
        byte[] b = new byte[n * 4];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (start + i);
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static AudioBridge bridge(byte[] stream, List<FakeOutput> opened) {
        AudioBridge b = new AudioBridge(new ByteArrayInputStream(stream), (rate, channels) -> {
            FakeOutput o = new FakeOutput(rate, channels);
            opened.add(o);
            return o;
        }, line -> {});
        return b;
    }

    private static void run(AudioBridge b) throws Exception {
        java.lang.reflect.Field f = AudioBridge.class.getDeclaredField("running");
        f.setAccessible(true);
        f.setBoolean(b, true); // as start() does, without its threads
        try {
            b.run();
            throw new AssertionError("run() returns only by throwing at the end of the stream");
        } catch (java.io.EOFException expected) {
        }
    }

    private static void stream() throws Exception {
        List<FakeOutput> opened = new ArrayList<>();
        byte[] a = frames(10, 0), c = frames(5, 100);
        run(bridge(concat(
                message('P', frames(2, 50)),          // before any format: dropped, no output yet
                message('F', ints(44100, 2, 1)),
                message('P', a),
                message('X', ints(2)),                // a skip
                message('F', ints(44100, 2, 3)),      // same format again: a flush, not a new output
                message('P', c),
                message('F', ints(48000, 2, 4))),     // a new rate: a new output
                opened));
        assert opened.size() == 2 : "outputs opened: " + opened.size();
        FakeOutput first = opened.get(0);
        assert first.rate == 44100 && first.channels == 2;
        assert java.util.Arrays.equals(first.pcm.toByteArray(), concat(a, c)) : "PCM reaches the output in order, whole";
        assert first.flushes == 2 : "flushes: " + first.flushes;
        assert opened.get(1).rate == 48000;
    }

    private static void badStream() throws Exception {
        List<FakeOutput> opened = new ArrayList<>();
        try {
            run(bridge("hello from a stray printf".getBytes("US-ASCII"), opened));
            throw new AssertionError("garbage accepted");
        } catch (IOException expected) {
            assert !(expected instanceof java.io.EOFException) : expected;
        }
        byte[] huge = message('P', new byte[0]);
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(4, AudioBridge.MAX_PAYLOAD + 1);
        try {
            run(bridge(huge, opened));
            throw new AssertionError("oversized message accepted");
        } catch (IOException expected) {
            assert expected.getMessage().contains("length") : expected;
        }
    }

    private static void closedOutput() throws Exception {
        List<FakeOutput> opened = new ArrayList<>();
        AudioBridge b = new AudioBridge(new ByteArrayInputStream(concat(
                message('F', ints(44100, 2, 1)),
                message('P', frames(4, 0)),
                message('P', frames(4, 0)))), (rate, channels) -> {
            FakeOutput o = new FakeOutput(rate, channels);
            o.closed = true; // the pick moved on
            opened.add(o);
            return o;
        }, line -> {});
        run(b); // must not spin on a closed output
        assert opened.size() == 1 && opened.get(0).pcm.size() == 0;
    }
}
