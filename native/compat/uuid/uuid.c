#include "uuid.h"
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/evp.h>

void uuid_generate_random(uuid_t out) {
  arc4random_buf(out, 16); /* bionic; getrandom() needs API 28 */
  out[6] = (out[6] & 0x0f) | 0x40; /* version 4 */
  out[8] = (out[8] & 0x3f) | 0x80; /* RFC 4122 variant */
}

static int hexval(int c) {
  if (c >= '0' && c <= '9') return c - '0';
  c = tolower(c);
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

int uuid_parse(const char *in, uuid_t uu) {
  int n = 0;
  for (int i = 0; in[i] != '\0' && n < 16; i++) {
    if (in[i] == '-') continue;
    int hi = hexval(in[i]), lo = hexval(in[i + 1]);
    if (hi < 0 || lo < 0) return -1;
    uu[n++] = (unsigned char)(hi << 4 | lo);
    i++;
  }
  return n == 16 ? 0 : -1;
}

void uuid_unparse_lower(const uuid_t uu, char *out) {
  sprintf(out, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x", uu[0], uu[1],
          uu[2], uu[3], uu[4], uu[5], uu[6], uu[7], uu[8], uu[9], uu[10], uu[11], uu[12], uu[13],
          uu[14], uu[15]);
}

void uuid_generate(uuid_t out) { uuid_generate_random(out); }

/* RFC 4122 version 5 (SHA-1, name-based) UUID. */
void uuid_generate_sha1(uuid_t out, const uuid_t ns, const char *name, size_t len) {
  unsigned char digest[EVP_MAX_MD_SIZE];
  unsigned int dlen = 0;
  EVP_MD_CTX *ctx = EVP_MD_CTX_new();
  EVP_DigestInit_ex(ctx, EVP_sha1(), NULL);
  EVP_DigestUpdate(ctx, ns, sizeof(uuid_t));
  EVP_DigestUpdate(ctx, name, len);
  EVP_DigestFinal_ex(ctx, digest, &dlen);
  EVP_MD_CTX_free(ctx);
  memcpy(out, digest, sizeof(uuid_t));
  out[6] = (out[6] & 0x0f) | 0x50;
  out[8] = (out[8] & 0x3f) | 0x80;
}
