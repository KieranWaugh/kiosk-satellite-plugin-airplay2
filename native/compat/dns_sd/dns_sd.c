/* dns_sd shim: sends each service to the plugin as one UDP datagram on
 * 127.0.0.1:$SPS_MDNS_PORT (default 5556):
 *   "REG <id> <port> <regtype> <name as hex> <TXT rdata as hex>"
 *   "DEL <id>"
 * Every registration is re-sent every 30 s, so a plugin that restarts, or a lost
 * datagram, catches up. The plugin treats repeats of the same content as no-ops. */
#include "dns_sd.h"
#include <arpa/inet.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define MAX_SERVICES 4

struct _DNSServiceRef_t {
  int id;
  int in_use;
  uint16_t port; /* host order */
  char regtype[64];
  char name[256];
  uint8_t txt[2048];
  uint16_t txt_len;
};

static struct _DNSServiceRef_t services[MAX_SERVICES];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t once = PTHREAD_ONCE_INIT;
static int sock = -1;
static struct sockaddr_in dest;
static int next_id = 1;

static void hex(char *out, const uint8_t *in, size_t n) {
  static const char d[] = "0123456789abcdef";
  for (size_t i = 0; i < n; i++) {
    out[2 * i] = d[in[i] >> 4];
    out[2 * i + 1] = d[in[i] & 15];
  }
  out[2 * n] = '\0';
}

static void send_service(struct _DNSServiceRef_t *s) { /* call with lock held */
  char name_hex[2 * sizeof s->name + 1], txt_hex[2 * sizeof s->txt + 1];
  char msg[64 + sizeof name_hex + sizeof txt_hex + 64];
  hex(name_hex, (const uint8_t *)s->name, strlen(s->name));
  hex(txt_hex, s->txt, s->txt_len);
  int n = snprintf(msg, sizeof msg, "REG %d %u %s %s %s", s->id, s->port, s->regtype, name_hex,
                   s->txt_len ? txt_hex : "-");
  sendto(sock, msg, n, 0, (struct sockaddr *)&dest, sizeof dest);
}

static void *refresher(void *arg) {
  (void)arg;
  for (;;) {
    sleep(30);
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_SERVICES; i++)
      if (services[i].in_use)
        send_service(&services[i]);
    pthread_mutex_unlock(&lock);
  }
  return NULL;
}

static void setup(void) {
  sock = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  memset(&dest, 0, sizeof dest);
  dest.sin_family = AF_INET;
  dest.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  const char *p = getenv("SPS_MDNS_PORT");
  dest.sin_port = htons(p ? (uint16_t)atoi(p) : 5556);
  pthread_t t;
  if (pthread_create(&t, NULL, refresher, NULL) == 0)
    pthread_detach(t);
}

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags,
                                       uint32_t interfaceIndex, const char *name,
                                       const char *regtype, const char *domain, const char *host,
                                       uint16_t port, uint16_t txtLen, const void *txtRecord,
                                       DNSServiceRegisterReply callBack, void *context) {
  (void)flags, (void)interfaceIndex, (void)domain, (void)host, (void)callBack, (void)context;
  if (sdRef == NULL || name == NULL || regtype == NULL || txtLen > sizeof services[0].txt ||
      strlen(name) >= sizeof services[0].name || strlen(regtype) >= sizeof services[0].regtype)
    return kDNSServiceErr_BadParam;
  pthread_once(&once, setup);
  pthread_mutex_lock(&lock);
  struct _DNSServiceRef_t *s = NULL;
  for (int i = 0; i < MAX_SERVICES && s == NULL; i++)
    if (!services[i].in_use)
      s = &services[i];
  if (s == NULL) {
    pthread_mutex_unlock(&lock);
    return kDNSServiceErr_NoMemory;
  }
  s->in_use = 1;
  s->id = next_id++;
  s->port = ntohs(port);
  strcpy(s->regtype, regtype);
  strcpy(s->name, name);
  memcpy(s->txt, txtRecord, txtLen);
  s->txt_len = txtLen;
  send_service(s);
  pthread_mutex_unlock(&lock);
  *sdRef = s;
  return kDNSServiceErr_NoError;
}

DNSServiceErrorType DNSServiceUpdateRecord(DNSServiceRef sdRef, DNSRecordRef recordRef,
                                           DNSServiceFlags flags, uint16_t rdlen,
                                           const void *rdata, uint32_t ttl) {
  (void)recordRef, (void)flags, (void)ttl;
  if (sdRef == NULL || rdlen > sizeof sdRef->txt)
    return kDNSServiceErr_BadParam;
  pthread_mutex_lock(&lock);
  memcpy(sdRef->txt, rdata, rdlen);
  sdRef->txt_len = rdlen;
  send_service(sdRef);
  pthread_mutex_unlock(&lock);
  return kDNSServiceErr_NoError;
}

void DNSServiceRefDeallocate(DNSServiceRef sdRef) {
  if (sdRef == NULL)
    return;
  pthread_mutex_lock(&lock);
  char msg[32];
  int n = snprintf(msg, sizeof msg, "DEL %d", sdRef->id);
  sendto(sock, msg, n, 0, (struct sockaddr *)&dest, sizeof dest);
  sdRef->in_use = 0;
  pthread_mutex_unlock(&lock);
}
