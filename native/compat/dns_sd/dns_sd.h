/* Minimal dns_sd API for shairport-sync's mdns_dns_sd backend. Registrations are
 * forwarded to the Kiosk Satellite plugin, which advertises them with Android's
 * NsdManager. See dns_sd.c. */
#ifndef COMPAT_DNS_SD_H
#define COMPAT_DNS_SD_H
#include <stdint.h>

typedef struct _DNSServiceRef_t *DNSServiceRef;
typedef struct _DNSRecordRef_t *DNSRecordRef;
typedef uint32_t DNSServiceFlags;
typedef int32_t DNSServiceErrorType;

enum {
  kDNSServiceErr_NoError = 0,
  kDNSServiceErr_Unknown = -65537,
  kDNSServiceErr_NoMemory = -65539,
  kDNSServiceErr_BadParam = -65540,
};
#define kDNSServiceInterfaceIndexAny 0

typedef void (*DNSServiceRegisterReply)(DNSServiceRef sdRef, DNSServiceFlags flags,
                                        DNSServiceErrorType errorCode, const char *name,
                                        const char *regtype, const char *domain, void *context);

DNSServiceErrorType DNSServiceRegister(DNSServiceRef *sdRef, DNSServiceFlags flags,
                                       uint32_t interfaceIndex, const char *name,
                                       const char *regtype, const char *domain, const char *host,
                                       uint16_t port, uint16_t txtLen, const void *txtRecord,
                                       DNSServiceRegisterReply callBack, void *context);
DNSServiceErrorType DNSServiceUpdateRecord(DNSServiceRef sdRef, DNSRecordRef recordRef,
                                           DNSServiceFlags flags, uint16_t rdlen,
                                           const void *rdata, uint32_t ttl);
void DNSServiceRefDeallocate(DNSServiceRef sdRef);
#endif
