/*
 * Plugin output backend for shairport-sync on Android.
 * Built in place of audio_stdout.c (the CONFIG_STDOUT slot), selected with
 *   output_backend = "plugin";
 *
 * The audio goes to the Kiosk Satellite plugin, which writes it to the kiosk's own
 * output (PluginPlayer.openAudio). That output is the one the echo canceller hears and
 * the one Kiosk applies its media volume and the assistant's duck to.
 *
 * Helper -> plugin, on stdout: messages with an 8-byte header
 *   'S' 'P' <type> 0   <payload length, uint32 little endian>
 *   'F' format:  uint32 rate, uint32 channels, uint32 epoch (the plugin opens or flushes its output)
 *   'P' PCM:     interleaved S16_LE frames
 *   'X' flush:   uint32 epoch (the plugin flushes its output)
 * At init, stdout is moved to a private descriptor and fd 1 is pointed at stderr, so a
 * stray printf elsewhere in shairport-sync lands in the log, not in the audio.
 *
 * Plugin -> helper, on stdin, about every 100 ms:
 *   "ts <epoch> <frame> <nanos>"
 * from AudioOutput.getTimestamp: <frame> was heard at CLOCK_MONOTONIC <nanos>
 * (System.nanoTime()). Frames count from the output's last flush, as ours count from
 * our last flush; the epoch says which flush a timestamp belongs to, so one sent before
 * a flush reached the plugin is ignored after it.
 *
 * delay() = frames written since the flush - frames heard by now (extrapolated from
 * the latest timestamp). That covers the pipe, the plugin and Kiosk's output.
 */
#include "audio.h"
#include "common.h"
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define NS_PER_S 1000000000LL

static int pcm_fd = -1;
static pthread_mutex_t out_lock = PTHREAD_MUTEX_INITIALIZER; // one message at a time
static pthread_mutex_t ts_lock = PTHREAD_MUTEX_INITIALIZER;  // the fields below
static unsigned int channels = 2, rate = 44100;
static uint32_t epoch;
static int64_t frames_written; // since the last flush
static int have_ts;
static int64_t ts_frame, ts_nanos;
static int output_failed;

// diagnostics, logged every 10 s while playing
static time_t last_report;

static int64_t now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (int64_t)t.tv_sec * NS_PER_S + t.tv_nsec;
}

static void put32(uint8_t *p, uint32_t v) {
  p[0] = v & 0xff, p[1] = (v >> 8) & 0xff, p[2] = (v >> 16) & 0xff, p[3] = (v >> 24) & 0xff;
}

static int write_all(const void *buf, size_t len) {
  const uint8_t *p = buf;
  while (len > 0) {
    ssize_t n = write(pcm_fd, p, len);
    if (n < 0) {
      if (errno == EINTR)
        continue;
      return -1;
    }
    p += n;
    len -= (size_t)n;
  }
  return 0;
}

// Call with out_lock held.
static int send_message(char type, const void *payload, uint32_t len) {
  uint8_t h[8] = {'S', 'P', (uint8_t)type, 0};
  put32(h + 4, len);
  if (write_all(h, sizeof h) != 0 || (len > 0 && write_all(payload, len) != 0)) {
    if (!output_failed)
      warn("plugin: can't send audio to the plugin: %s", strerror(errno));
    output_failed = 1;
    return -1;
  }
  return 0;
}

// Starts a new count, for a flush or a new format. Call with out_lock held; returns the new epoch.
static uint32_t new_epoch(void) {
  pthread_mutex_lock(&ts_lock);
  epoch++;
  frames_written = 0;
  have_ts = 0;
  uint32_t e = epoch;
  pthread_mutex_unlock(&ts_lock);
  return e;
}

static void *control_thread(void *arg) {
  (void)arg;
  char line[128];
  while (fgets(line, sizeof line, stdin) != NULL) {
    unsigned int e;
    long long frame, nanos;
    if (sscanf(line, "ts %u %lld %lld", &e, &frame, &nanos) == 3) {
      pthread_mutex_lock(&ts_lock);
      if (e == epoch) {
        ts_frame = frame;
        ts_nanos = nanos;
        have_ts = 1;
      }
      pthread_mutex_unlock(&ts_lock);
    }
  }
  return NULL; // stdin closed: the plugin went away
}

static int init(__attribute__((unused)) int argc, __attribute__((unused)) char **argv) {
  config.audio_backend_buffer_desired_length = 0.35;
  config.audio_backend_latency_offset = 0;
  parse_audio_options("plugin", (1 << SPS_FORMAT_S16_LE), (1 << SPS_RATE_48000) | (1 << SPS_RATE_44100),
                      (1 << 2));
  signal(SIGPIPE, SIG_IGN); // a closed pipe makes write() fail instead of killing us
  pcm_fd = dup(STDOUT_FILENO);
  if (pcm_fd < 0 || dup2(STDERR_FILENO, STDOUT_FILENO) < 0) {
    warn("plugin: can't set up the audio pipe: %s", strerror(errno));
    return -1;
  }
  pthread_t t;
  if (pthread_create(&t, NULL, control_thread, NULL) == 0)
    pthread_detach(t);
  return 0;
}

static void deinit(void) {
  if (pcm_fd >= 0)
    close(pcm_fd);
  pcm_fd = -1;
}

static int32_t get_configuration(unsigned int ch, unsigned int r, unsigned int format) {
  return search_for_suitable_configuration(ch, r, format, NULL);
}

static int configure(int32_t encoded, __attribute__((unused)) char **channel_map) {
  if (FORMAT_FROM_ENCODED_FORMAT(encoded) != SPS_FORMAT_S16_LE) {
    warn("plugin: only S16_LE output is supported");
    return EINVAL;
  }
  pthread_mutex_lock(&out_lock);
  channels = CHANNELS_FROM_ENCODED_FORMAT(encoded);
  rate = RATE_FROM_ENCODED_FORMAT(encoded);
  uint8_t p[12];
  put32(p, rate);
  put32(p + 4, channels);
  put32(p + 8, new_epoch());
  int r = send_message('F', p, sizeof p);
  pthread_mutex_unlock(&out_lock);
  debug(1, "plugin: %u Hz, %u channels", rate, channels);
  return r == 0 ? 0 : EIO;
}

static int delay(long *the_delay) {
  pthread_mutex_lock(&ts_lock);
  int64_t written = frames_written, heard = 0;
  if (have_ts)
    heard = ts_frame + (now_ns() - ts_nanos) * (int64_t)rate / NS_PER_S;
  pthread_mutex_unlock(&ts_lock);
  int64_t d = written - heard;
  if (d < 0)
    d = 0; // an underrun: what was written has all been heard
  *the_delay = (long)d;
  return 0;
}

static int play(void *buf, int samples, __attribute__((unused)) int sample_type,
                __attribute__((unused)) uint32_t timestamp,
                __attribute__((unused)) uint64_t playtime) {
  if (pcm_fd < 0)
    return -ENODEV;
  pthread_mutex_lock(&out_lock);
  int r = send_message('P', buf, (uint32_t)samples * channels * sizeof(int16_t));
  if (r == 0) {
    pthread_mutex_lock(&ts_lock);
    frames_written += samples;
    pthread_mutex_unlock(&ts_lock);
  }
  pthread_mutex_unlock(&out_lock);
  if (r != 0)
    return -EIO;

  time_t now = time(NULL);
  if (now - last_report >= 10) {
    last_report = now;
    long d = 0;
    delay(&d);
    pthread_mutex_lock(&ts_lock);
    int ts = have_ts;
    int64_t w = frames_written;
    pthread_mutex_unlock(&ts_lock);
    debug(1, "plugin: %lld frames written since the flush, delay %ld, timestamps %s", (long long)w, d,
          ts ? "yes" : "not yet");
  }
  return 0;
}

// A flush (skip, seek, pause) tells the plugin to flush its output and starts a new count.
static void flush(void) {
  if (pcm_fd < 0)
    return;
  pthread_mutex_lock(&out_lock);
  uint8_t p[4];
  put32(p, new_epoch());
  send_message('X', p, sizeof p);
  pthread_mutex_unlock(&out_lock);
}

static void stop(void) { flush(); }

audio_output audio_stdout = {.name = "plugin",
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
