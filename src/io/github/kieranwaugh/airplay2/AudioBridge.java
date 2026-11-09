package io.github.kieranwaugh.airplay2;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;
import me.jxl.kiosk.plugins.AudioOutput;

/**
 * Carries shairport-sync's audio to the kiosk's output and the output's timing back.
 *
 * The helper's backend (native/sps/audio_plugin.c) writes messages to its stdout, each with an
 * 8-byte header: 'S' 'P' type 0, then the payload length (uint32, little endian).
 *   'F' rate, channels, epoch (uint32 each): a new format, and a new count of frames from zero
 *   'P' interleaved 16-bit PCM
 *   'X' epoch: a flush (skip, seek, pause), and a new count from zero
 * The output also counts from zero after openAudio and flush, so the helper's count of frames
 * written and the output's timestamps stay in step. About every 100 ms this sends the output's
 * timestamp to the helper as "ts epoch frame nanos", from which it works out its delay.
 */
final class AudioBridge {
    /** Opens the kiosk's output; throws once the player is no longer the pick. */
    interface Opener {
        AudioOutput open(int sampleRate, int channels);
    }

    static final int MAX_PAYLOAD = 1 << 20;

    private final InputStream in;
    private final Opener opener;
    private final Consumer<String> toHelper;
    private final Object lock = new Object();
    private AudioOutput output; // guarded by lock
    private int rate, channels;
    private long epoch;
    private volatile boolean running;
    private Thread reader, clock;

    AudioBridge(InputStream in, Opener opener, Consumer<String> toHelper) {
        this.in = in;
        this.opener = opener;
        this.toHelper = toHelper;
    }

    void start() {
        running = true;
        reader = new Thread(this::readLoop, "airplay2-audio");
        reader.setPriority(Thread.MAX_PRIORITY);
        reader.setDaemon(true);
        reader.start();
        clock = new Thread(this::clockLoop, "airplay2-audio-clock");
        clock.setDaemon(true);
        clock.start();
    }

    /** Stops the threads without waiting; the reader ends when the helper's stdout closes. */
    void stop() {
        running = false;
        if (clock != null) clock.interrupt();
        synchronized (lock) {
            output = null; // KS closes the output itself when the pick moves on
        }
    }

    private void readLoop() {
        try {
            run();
        } catch (EOFException e) {
            // the helper exited
        } catch (IOException e) {
            if (running) Log.w("audio from shairport-sync", e);
        }
    }

    /** Reads messages until the stream ends. Package-private for the tests. */
    void run() throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] header = new byte[8];
        byte[] payload = new byte[16384];
        while (true) {
            data.readFully(header);
            if (header[0] != 'S' || header[1] != 'P') throw new IOException("unexpected bytes in the audio stream");
            int length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (length < 0 || length > MAX_PAYLOAD) throw new IOException("bad message length " + length);
            if (payload.length < length) payload = new byte[length];
            data.readFully(payload, 0, length);
            ByteBuffer p = ByteBuffer.wrap(payload, 0, length).order(ByteOrder.LITTLE_ENDIAN);
            switch (header[2]) {
                case 'F':
                    format(p.getInt(), p.getInt(), p.getInt() & 0xffffffffL);
                    break;
                case 'P':
                    write(p);
                    break;
                case 'X':
                    flush(p.getInt() & 0xffffffffL);
                    break;
                default:
                    Log.d("unknown audio message " + (char) header[2]);
            }
        }
    }

    private void format(int newRate, int newChannels, long newEpoch) {
        synchronized (lock) {
            epoch = newEpoch;
            if (output != null && newRate == rate && newChannels == channels) {
                output.flush(); // the helper starts a new count, so the output does too
                return;
            }
            rate = newRate;
            channels = newChannels;
            try {
                output = opener.open(newRate, newChannels);
                Log.i("audio output open: " + newRate + " Hz, " + newChannels + " channels");
            } catch (RuntimeException e) {
                output = null;
                Log.i("can't open the audio output: " + e.getMessage());
            }
        }
    }

    private void flush(long newEpoch) {
        synchronized (lock) {
            epoch = newEpoch;
            if (output != null) output.flush();
        }
    }

    private void write(ByteBuffer pcm) {
        AudioOutput out;
        int frameBytes;
        synchronized (lock) {
            out = output;
            frameBytes = channels * 2;
        }
        if (out == null || frameBytes == 0) return; // no output: the audio is dropped
        while (running && pcm.remaining() >= frameBytes) {
            int taken = out.write(pcm, pcm.remaining() / frameBytes, 500);
            if (taken < 0) { // closed: the pick moved on
                synchronized (lock) {
                    if (output == out) output = null;
                }
                return;
            }
        }
    }

    private void clockLoop() {
        long[] ts = new long[2];
        long lastFrame = -1, lastEpoch = -1;
        while (running) {
            String line = null;
            synchronized (lock) {
                if (output != null && output.getTimestamp(ts) && (ts[0] != lastFrame || epoch != lastEpoch)) {
                    lastFrame = ts[0];
                    lastEpoch = epoch;
                    line = timestampLine(epoch, ts[0], ts[1]);
                }
            }
            if (line != null) toHelper.accept(line);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    static String timestampLine(long epoch, long frame, long nanos) {
        return "ts " + epoch + " " + frame + " " + nanos;
    }
}
