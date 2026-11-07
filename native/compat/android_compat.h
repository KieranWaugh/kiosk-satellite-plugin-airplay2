/* Force-included (-include) into every shairport-sync and NQPTP source file
 * when building for Android. Fills gaps in bionic. */
#ifndef ANDROID_COMPAT_H
#define ANDROID_COMPAT_H

#include <pthread.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* bionic has no pthread cancellation. compat.c emulates it with a signal:
 * pthread_cancel() signals the thread, whose handler calls pthread_exit()
 * (running its cleanup handlers) unless cancellation is disabled, in which
 * case the request is kept until it is enabled again or pthread_testcancel(). */
#ifndef PTHREAD_CANCELED
#define PTHREAD_CANCELED ((void *)-1)
#endif
#ifndef PTHREAD_CANCEL_ENABLE
#define PTHREAD_CANCEL_ENABLE 0
#define PTHREAD_CANCEL_DISABLE 1
#endif
#ifndef PTHREAD_CANCEL_DEFERRED
#define PTHREAD_CANCEL_DEFERRED 0
#define PTHREAD_CANCEL_ASYNCHRONOUS 1
#endif
int compat_pthread_cancel(pthread_t thread);
int compat_pthread_setcancelstate(int state, int *oldstate);
int compat_pthread_setcanceltype(int type, int *oldtype);
void compat_pthread_testcancel(void);
#define pthread_cancel compat_pthread_cancel
#define pthread_setcancelstate compat_pthread_setcancelstate
#define pthread_setcanceltype compat_pthread_setcanceltype
#define pthread_testcancel compat_pthread_testcancel

/* bionic can't set scheduling inheritance; shairport-sync only asks for it as a hint. */
#ifndef PTHREAD_INHERIT_SCHED
#define PTHREAD_INHERIT_SCHED 0
#define PTHREAD_EXPLICIT_SCHED 1
#endif
#define pthread_attr_setinheritsched(attr, inherit) ((void)(attr), (void)(inherit), 0)

/* bionic has no POSIX shared memory. NQPTP and shairport-sync share a plain
 * file instead: "/nqptp" maps to $NQPTP_SHM_DIR/nqptp (default /data/local/tmp). */
int compat_shm_open(const char *name, int oflag, mode_t mode);
int compat_shm_unlink(const char *name);
#define shm_open compat_shm_open
#define shm_unlink compat_shm_unlink

/* bzero() is gone from bionic's headers. */
#include <string.h>
#ifndef bzero
#define bzero(p, n) memset((p), 0, (n))
#endif

#ifdef __cplusplus
}
#endif
#endif
