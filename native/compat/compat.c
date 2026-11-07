/* Implementations for android_compat.h. Built without the forced include. */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define PTHREAD_CANCELED ((void *)-1)

static __thread int cancel_disabled; /* 1 while PTHREAD_CANCEL_DISABLE */
static __thread volatile sig_atomic_t cancel_pending;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static int cancel_signal;

static void on_cancel_signal(int sig) {
  (void)sig;
  cancel_pending = 1;
  if (!cancel_disabled)
    pthread_exit(PTHREAD_CANCELED);
}

static void install_handler(void) {
  cancel_signal = SIGRTMIN + 6;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_handler = on_cancel_signal;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = 0; /* no SA_RESTART: blocking calls return EINTR, like a cancellation point */
  sigaction(cancel_signal, &sa, NULL);
}

int compat_pthread_cancel(pthread_t thread) {
  pthread_once(&once, install_handler);
  return pthread_kill(thread, cancel_signal);
}

int compat_pthread_setcancelstate(int state, int *oldstate) {
  pthread_once(&once, install_handler);
  if (oldstate)
    *oldstate = cancel_disabled;
  cancel_disabled = (state != 0);
  if (!cancel_disabled && cancel_pending)
    pthread_exit(PTHREAD_CANCELED);
  return 0;
}

int compat_pthread_setcanceltype(int type, int *oldtype) {
  (void)type;
  if (oldtype)
    *oldtype = 0;
  return 0;
}

void compat_pthread_testcancel(void) {
  if (!cancel_disabled && cancel_pending)
    pthread_exit(PTHREAD_CANCELED);
}

static void shm_path(const char *name, char *out, size_t n) {
  const char *dir = getenv("NQPTP_SHM_DIR");
  if (dir == NULL || *dir == '\0')
    dir = "/data/local/tmp";
  while (*name == '/')
    name++;
  snprintf(out, n, "%s/%s", dir, name);
}

int compat_shm_open(const char *name, int oflag, mode_t mode) {
  char path[PATH_MAX];
  shm_path(name, path, sizeof path);
  int fd = open(path, oflag | O_CLOEXEC, mode);
  if (fd >= 0 && (oflag & O_CREAT))
    fchmod(fd, 0644); /* readable by the app that runs shairport-sync */
  return fd;
}

int compat_shm_unlink(const char *name) {
  char path[PATH_MAX];
  shm_path(name, path, sizeof path);
  return unlink(path);
}
