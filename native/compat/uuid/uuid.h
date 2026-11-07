/* Minimal libuuid replacement: the calls shairport-sync uses. */
#ifndef COMPAT_UUID_H
#define COMPAT_UUID_H
typedef unsigned char uuid_t[16];
void uuid_generate_random(uuid_t out);
void uuid_generate(uuid_t out);
#include <stddef.h>
void uuid_generate_sha1(uuid_t out, const uuid_t ns, const char *name, size_t len);
#ifndef UUID_STR_LEN
#define UUID_STR_LEN 37
#endif
int uuid_parse(const char *in, uuid_t uu);
void uuid_unparse_lower(const uuid_t uu, char *out);
#endif
