/*
 * AAudio output backend for shairport-sync on Android.
 * Built in place of audio_stdout.c (the CONFIG_STDOUT slot), selected with
 *   output_backend = "aaudio";
 *
 * play() copies frames into a ring buffer (never blocks); AAudio's data callback
 * drains it into the stream's small, low-jitter buffer. shairport-sync keeps
 * about audio_backend_buffer_desired_length queued in total, so the ring is the
 * cushion that absorbs late writes. delay() = frames in the ring + frames AAudio
 * holds but hasn't presented (from AAudioStream_getTimestamp).
 *
 * What didn't work on the Echo Show 5 (MT8163, Android 11):
 *  - writing straight into AAudio: its buffer is ~0.1 s, so any write more than
 *    ~50 ms late underruns (crackles, and the delay drifts so sync corrects hard);
 *  - asking AAudio for a big buffer: that selects the deep-buffer output, which
 *    moves audio in 0.25 s bursts and makes playback join seconds late;
 *  - pausing and flushing the stream in place on a skip: it got stuck. A flush
 *    now closes the stream and play() opens a new one.
 *
 * The plugin controls an extra gain (Kiosk's media volume, and ducking for the
 * assistant) by writing lines to stdin:  "gain 0.35"  (linear amplitude, 0..1).
 */
#include "audio.h"
#include "common.h"
#include <aaudio/AAudio.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define NS_PER_S 1000000000LL

static pthread_mutex_t ring_lock = PTHREAD_MUTEX_INITIALIZER;
static int16_t *ring;          // interleaved S16 frames
static size_t ring_frames;     // capacity in frames
static size_t head, occupancy; // head = next frame to play
static unsigned int channels = 2, rate = 48000;
static AAudioStream *stream;
static int started;
static atomic_int stream_failed;
static _Atomic float gain = 1.0f; // target gain from the plugin
static float current_gain = 1.0f; // ramped towards gain in the callback

// diagnostics, logged every 10 s while playing
static uint64_t frames_in, frames_silent;
static int peak_in;
static time_t last_report;

static int delay(long *the_delay);

static void *control_thread(void *arg) {
  (void)arg;
  char line[128];
  while (fgets(line, sizeof line, stdin) != NULL) {
    float g;
    if (sscanf(line, "gain %f", &g) == 1 && g >= 0.0f && g <= 1.0f) {
      atomic_store(&gain, g);
      debug(2, "aaudio: gain %.3f", g);
    }
  }
  return NULL; // stdin closed: the plugin went away; keep the last gain
}

static aaudio_data_callback_result_t data_cb(AAudioStream *s, void *user, void *audio_data,
                                             int32_t num_frames) {
  (void)s, (void)user;
  int16_t *out = audio_data;
  size_t want = (size_t)num_frames, got = 0;
  pthread_mutex_lock(&ring_lock);
  while (got < want && occupancy > 0) {
    size_t n = ring_frames - head;
    if (n > occupancy)
      n = occupancy;
    if (n > want - got)
      n = want - got;
    memcpy(out + got * channels, ring + head * channels, n * channels * sizeof(int16_t));
    head = (head + n) % ring_frames;
    occupancy -= n;
    got += n;
  }
  frames_silent += want - got;
  pthread_mutex_unlock(&ring_lock);
  if (got < want) // ring ran dry: play silence (delay() then reports it honestly)
    memset(out + got * channels, 0, (want - got) * channels * sizeof(int16_t));

  float target = atomic_load(&gain);
  if (target != 1.0f || current_gain != 1.0f) {
    size_t samples = want * channels;
    float step = (target - current_gain) / (float)(samples ? samples : 1);
    for (size_t i = 0; i < samples; i++) {
      current_gain += step;
      out[i] = (int16_t)lrintf(out[i] * current_gain);
    }
    current_gain = target;
  }
  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void error_cb(AAudioStream *s, void *user, aaudio_result_t error) {
  (void)s, (void)user;
  warn("aaudio: stream error %d (%s); it will be reopened", error, AAudio_convertResultToText(error));
  atomic_store(&stream_failed, 1);
}

static void close_stream(void) {
  if (stream != NULL) {
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    stream = NULL;
  }
  started = 0;
}

static void reset_ring(void) {
  pthread_mutex_lock(&ring_lock);
  head = occupancy = 0;
  pthread_mutex_unlock(&ring_lock);
}

static int open_stream(void) {
  close_stream();
  atomic_store(&stream_failed, 0);
  AAudioStreamBuilder *b;
  if (AAudio_createStreamBuilder(&b) != AAUDIO_OK)
    return -1;
  AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
  AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
  AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
  AAudioStreamBuilder_setChannelCount(b, (int32_t)channels);
  AAudioStreamBuilder_setSampleRate(b, (int32_t)rate);
#if __ANDROID_API__ >= 28
  AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
  AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
#endif
  AAudioStreamBuilder_setDataCallback(b, data_cb, NULL);
  AAudioStreamBuilder_setErrorCallback(b, error_cb, NULL);
  aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
  AAudioStreamBuilder_delete(b);
  if (r != AAUDIO_OK) {
    warn("aaudio: can't open a %u Hz, %u channel stream: %s", rate, channels,
         AAudio_convertResultToText(r));
    stream = NULL;
    return -1;
  }
  debug(1, "aaudio: opened %d Hz, %d ch, buffer %d of %d frames, burst %d",
        AAudioStream_getSampleRate(stream), AAudioStream_getChannelCount(stream),
        AAudioStream_getBufferSizeInFrames(stream), AAudioStream_getBufferCapacityInFrames(stream),
        AAudioStream_getFramesPerBurst(stream));
  return 0;
}

static int init(__attribute__((unused)) int argc, __attribute__((unused)) char **argv) {
  config.audio_backend_buffer_desired_length = 0.35; // the ring's cushion against late writes
  config.audio_backend_latency_offset = 0;
  parse_audio_options("aaudio", (1 << SPS_FORMAT_S16_LE), (1 << SPS_RATE_48000) | (1 << SPS_RATE_44100),
                      (1 << 2));
  pthread_t t;
  if (pthread_create(&t, NULL, control_thread, NULL) == 0)
    pthread_detach(t);
  return 0;
}

static void deinit(void) {
  close_stream();
  free(ring);
  ring = NULL;
}

static int32_t get_configuration(unsigned int ch, unsigned int r, unsigned int format) {
  return search_for_suitable_configuration(ch, r, format, NULL);
}

static int configure(int32_t encoded, __attribute__((unused)) char **channel_map) {
  unsigned int ch = CHANNELS_FROM_ENCODED_FORMAT(encoded);
  unsigned int r = RATE_FROM_ENCODED_FORMAT(encoded);
  if (FORMAT_FROM_ENCODED_FORMAT(encoded) != SPS_FORMAT_S16_LE) {
    warn("aaudio: only S16_LE output is supported");
    return EINVAL;
  }
  if (ring == NULL || ch != channels || r != rate) {
    close_stream();
    channels = ch;
    rate = r;
    pthread_mutex_lock(&ring_lock);
    free(ring);
    ring_frames = (size_t)rate * 2; // 2 s; shairport-sync keeps ~0.35 s queued
    ring = calloc(ring_frames * channels, sizeof(int16_t));
    head = occupancy = 0;
    pthread_mutex_unlock(&ring_lock);
    if (ring == NULL)
      return ENOMEM;
  }
  return 0;
}

static int play(void *buf, int samples, __attribute__((unused)) int sample_type,
                __attribute__((unused)) uint32_t timestamp,
                __attribute__((unused)) uint64_t playtime) {
  if (ring == NULL)
    return -ENODEV;
  // Open after a flush (which closes the stream) or after a stream error.
  if ((stream == NULL || atomic_load(&stream_failed)) && open_stream() != 0)
    return -EIO;
  const int16_t *in = buf;
  size_t n = (size_t)samples;
  for (size_t i = 0; i < n * channels; i++) {
    int a = in[i] < 0 ? -in[i] : in[i];
    if (a > peak_in)
      peak_in = a;
  }
  frames_in += n;

  pthread_mutex_lock(&ring_lock);
  if (n > ring_frames - occupancy)
    n = ring_frames - occupancy; // full: drop the excess (shairport-sync will resync)
  size_t tail = (head + occupancy) % ring_frames;
  size_t first = ring_frames - tail < n ? ring_frames - tail : n;
  memcpy(ring + tail * channels, in, first * channels * sizeof(int16_t));
  memcpy(ring, in + first * channels, (n - first) * channels * sizeof(int16_t));
  occupancy += n;
  pthread_mutex_unlock(&ring_lock);

  if (!started) {
    aaudio_result_t r = AAudioStream_requestStart(stream);
    if (r != AAUDIO_OK) {
      warn("aaudio: start failed: %s", AAudio_convertResultToText(r));
      atomic_store(&stream_failed, 1);
      return -EIO;
    }
    started = 1;
  }

  time_t now = time(NULL);
  if (now - last_report >= 10) {
    last_report = now;
    long d = 0;
    delay(&d);
    debug(1, "aaudio: %llu frames in, peak %d, delay %ld, silence %llu, xruns %d",
          (unsigned long long)frames_in, peak_in, d, (unsigned long long)frames_silent,
          AAudioStream_getXRunCount(stream));
    peak_in = 0;
  }
  return 0;
}

static int delay(long *the_delay) {
  pthread_mutex_lock(&ring_lock);
  size_t occ = occupancy;
  pthread_mutex_unlock(&ring_lock);
  int64_t in_flight = 0;
  if (stream != NULL && started) {
    int64_t written = AAudioStream_getFramesWritten(stream);
    int64_t frame_pos = 0, frame_time = 0;
    if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &frame_pos, &frame_time) == AAUDIO_OK) {
      struct timespec now;
      clock_gettime(CLOCK_MONOTONIC, &now);
      int64_t now_ns = (int64_t)now.tv_sec * NS_PER_S + now.tv_nsec;
      in_flight = written - (frame_pos + (now_ns - frame_time) * (int64_t)rate / NS_PER_S);
    } else {
      in_flight = written - AAudioStream_getFramesRead(stream); // until timestamps start
    }
    if (in_flight < 0)
      in_flight = 0;
  }
  *the_delay = (long)(occ + (size_t)in_flight);
  return 0;
}

// A flush (skip, seek, pause) empties the ring and closes the stream; play()
// opens a fresh one. Pausing and flushing in place got stuck on this device.
static void flush(void) {
  close_stream();
  reset_ring();
}

static void stop(void) { flush(); }

audio_output audio_stdout = {.name = "aaudio",
                             .help = NULL,
                             .init = &init,
                             .deinit = &deinit,
                             .prepare = NULL,
                             .get_configuration = &get_configuration,
                             .configure = &configure,
                             .stop = &stop,
                             .is_running = NULL,
                             .flush = &flush,
                             .delay = &delay,
                             .stats = NULL,
                             .play = &play,
                             .volume = NULL,
                             .parameters = NULL,
                             .mute = NULL};
