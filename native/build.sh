#!/usr/bin/env bash
# Builds NQPTP and shairport-sync (AirPlay 2) for Android armeabi-v7a, statically linked,
# as PIE executables the plugin packages as native/armeabi-v7a/lib{nqptp,shairport}.so and
# starts through /system/bin/linker.
#
# Runs on Linux (GitHub Actions, ubuntu-24.04) and macOS. Needs: the Android NDK r27d
# (27.3.13750724), curl, autoconf, automake, libtool, pkg-config, make, perl, xxd and
# plistutil (Debian/Ubuntu: libplist-utils).
#
# Every source is downloaded from its upstream and checked against the SHA-256 below.
# Output: native/out/armeabi-v7a/. Work files: native/work/ (safe to delete).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
DL="$WORK/downloads"
SRC="$WORK/src"
BUILD="$WORK/build"
PREFIX="$WORK/prefix"
OUT="$HERE/out/armeabi-v7a"
API=26 # the plugin's minAndroidSdk
NDK_VERSION=27.3.13750724

# name|url|sha256
SOURCES=(
  "openssl-3.5.9.tar.gz|https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz|603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
  "libsodium-1.0.22.tar.gz|https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz|adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349"
  "libgpg-error-1.61.tar.bz2|https://gnupg.org/ftp/gcrypt/libgpg-error/libgpg-error-1.61.tar.bz2|7a85413f2bc354f4f8aa832b718af122e48965e9e0eb9012ee659c13c6385c93"
  "libgcrypt-1.12.4.tar.bz2|https://gnupg.org/ftp/gcrypt/libgcrypt/libgcrypt-1.12.4.tar.bz2|d77f68f48879510e79a2f65977ccc68981781ea0923e5bdffac2a193ea3d660e"
  "libplist-2.7.0.tar.bz2|https://github.com/libimobiledevice/libplist/releases/download/2.7.0/libplist-2.7.0.tar.bz2|7ac42301e896b1ebe3c654634780c82baa7cb70df8554e683ff89f7c2643eb8b"
  "libconfig-1.7.3.tar.gz|https://github.com/hyperrealm/libconfig/releases/download/v1.7.3/libconfig-1.7.3.tar.gz|545166d6cac037744381d1e9cc5a5405094e7bfad16a411699bcff40bbb31ee7"
  "popt-1.19.tar.gz|https://ftp.osuosl.org/pub/rpm/popt/releases/popt-1.x/popt-1.19.tar.gz|c25a4838fc8e4c1c8aacb8bd620edb3084a3d63bf8987fdad3ca2758c63240f9"
  "ffmpeg-7.1.5.tar.xz|https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz|de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f"
  "nqptp-1.2.9.tar.gz|https://github.com/mikebrady/nqptp/archive/refs/tags/1.2.9.tar.gz|6aa1d73b8ee760bf19378c319db0d0537a867ff961764b150e19f87d96a02210"
  "shairport-sync-5.6.2.tar.gz|https://github.com/mikebrady/shairport-sync/archive/refs/tags/5.6.2.tar.gz|0747706e19a64ec550ec8a6a5c26bf8e231a4d032de31644f5a5daa0632c41c4"
)

# ---- toolchain ------------------------------------------------------------------------
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/$NDK_VERSION}"
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64; JOBS=$(sysctl -n hw.ncpu) ;;
  Linux) HOST_TAG=linux-x86_64; JOBS=$(nproc) ;;
  *) echo "unsupported build host $(uname -s)" >&2; exit 1 ;;
esac
TC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
[ -x "$TC/bin/clang" ] || { echo "Android NDK $NDK_VERSION not found at $NDK" >&2; exit 1; }
for tool in curl autoreconf pkg-config make perl xxd plistutil; do
  command -v "$tool" >/dev/null || { echo "missing build tool: $tool" >&2; exit 1; }
done

export PATH="$TC/bin:$PATH"
export CC="$TC/bin/armv7a-linux-androideabi$API-clang"
export CXX="$TC/bin/armv7a-linux-androideabi$API-clang++"
export AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" NM="$TC/bin/llvm-nm" STRIP="$TC/bin/llvm-strip"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" PKG_CONFIG_PATH=""
LIB_CFLAGS="-Os -fPIC -ffunction-sections -fdata-sections -fvisibility=hidden -march=armv7-a -mfpu=neon -mfloat-abi=softfp"
APP_CFLAGS="-Os -fPIE -ffunction-sections -fdata-sections -march=armv7-a -mfpu=neon -mfloat-abi=softfp"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
log() { echo "== $*"; }

fetch() {
  mkdir -p "$DL" "$SRC"
  for entry in "${SOURCES[@]}"; do
    IFS='|' read -r name url want <<<"$entry"
    if [ ! -f "$DL/$name" ] || [ "$(sha256 "$DL/$name")" != "$want" ]; then
      log "download $name"
      curl -fsSL --retry 3 -o "$DL/$name.part" "$url"
      mv "$DL/$name.part" "$DL/$name"
    fi
    got=$(sha256 "$DL/$name")
    [ "$got" = "$want" ] || { echo "checksum mismatch for $name: $got" >&2; exit 1; }
    dir="${name%.tar.*}"
    [ -d "$SRC/$dir" ] || { mkdir -p "$SRC/$dir" && tar -xf "$DL/$name" -C "$SRC/$dir" --strip-components=1; }
  done
}

conf() { # autotools cross-configure for a static library
  "$1/configure" --host=arm-linux-androideabi --prefix="$PREFIX" \
    --enable-static --disable-shared --with-pic "${@:2}"
}

fresh() { rm -rf "$BUILD/$1" && mkdir -p "$BUILD/$1" && cd "$BUILD/$1"; }

# ---- libraries --------------------------------------------------------------------------
b_openssl() { # only what shairport-sync uses (RSA, AES, SHA, MD5, ChaCha20-Poly1305, BN, base64)
  rm -rf "$BUILD/openssl" && cp -R "$SRC/openssl-3.5.9" "$BUILD/openssl" && cd "$BUILD/openssl"
  ANDROID_NDK_ROOT="$NDK" CFLAGS="" ./Configure android-arm -D__ANDROID_API__=$API --prefix="$PREFIX" --libdir=lib \
    no-shared no-tests no-apps no-docs no-engine no-comp no-dso no-ssl3 no-dtls no-srp no-ocsp no-cms no-ts \
    no-ct no-quic no-legacy no-deprecated no-sm2 no-sm3 no-sm4 no-gost no-idea no-seed no-camellia no-aria \
    no-bf no-cast no-rc2 no-rc4 no-rc5 no-md4 no-mdc2 no-whirlpool no-rmd160 no-scrypt no-siphash \
    no-ec no-dsa no-dh no-des no-ml-kem no-ml-dsa no-slh-dsa no-cmac no-ocb no-siv no-blake2 \
    no-argon2 no-sock no-http no-ui-console no-autoload-config no-ssl \
    $LIB_CFLAGS >/dev/null
  make -j"$JOBS" build_libs >/dev/null && make install_dev >/dev/null
}

b_sodium() {
  fresh sodium
  CFLAGS="$LIB_CFLAGS" conf "$SRC/libsodium-1.0.22" --disable-pie --disable-ssp >/dev/null
  make -j"$JOBS" >/dev/null && make install >/dev/null
}

b_gpgerror() {
  fresh gpgerror
  CFLAGS="$LIB_CFLAGS" conf "$SRC/libgpg-error-1.61" --disable-nls --disable-doc --disable-tests --disable-languages >/dev/null
  make -j"$JOBS" >/dev/null && make install >/dev/null
}

b_gcrypt() {
  fresh gcrypt
  CFLAGS="$LIB_CFLAGS" conf "$SRC/libgcrypt-1.12.4" --with-libgpg-error-prefix="$PREFIX" --disable-doc --disable-jent-support \
    --disable-asm --disable-padlock-support --disable-aesni-support --disable-large-data-tests >/dev/null
  for d in compat mpi cipher random src; do make -j"$JOBS" -C $d >/dev/null; done
  make -C src install >/dev/null
  install -m 644 src/libgcrypt.pc "$PREFIX/lib/pkgconfig/"
}

b_plist() {
  fresh plist
  CFLAGS="$LIB_CFLAGS" CXXFLAGS="$LIB_CFLAGS" conf "$SRC/libplist-2.7.0" --without-cython --without-tests --disable-debug >/dev/null
  make -j"$JOBS" -C libcnary >/dev/null && make -j"$JOBS" -C src >/dev/null && make -C src install >/dev/null
  make -C include install >/dev/null
  install -m 644 src/libplist-2.0.pc "$PREFIX/lib/pkgconfig/"
}

b_config() {
  fresh config
  CFLAGS="$LIB_CFLAGS" conf "$SRC/libconfig-1.7.3" --disable-cxx --disable-examples --disable-tests >/dev/null
  make -j"$JOBS" -C lib >/dev/null && make -C lib install >/dev/null
}

b_popt() { # glob() needs API 28, so build without it
  fresh popt
  ac_cv_header_glob_h=no ac_cv_func_glob=no CFLAGS="$LIB_CFLAGS" \
    conf "$SRC/popt-1.19" --disable-nls --without-libiconv-prefix >/dev/null
  make -j"$JOBS" -C src >/dev/null && make -C src install >/dev/null
  install -m 644 popt.pc "$PREFIX/lib/pkgconfig/"
}

b_ffmpeg() { # AAC and ALAC decoders and the resampler only
  fresh ffmpeg
  "$SRC/ffmpeg-7.1.5/configure" --prefix="$PREFIX" --enable-cross-compile --target-os=android --arch=arm \
    --cpu=armv7-a --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --nm="$NM" --strip="$STRIP" \
    --extra-cflags="$LIB_CFLAGS" --enable-pic --enable-small --disable-debug --enable-static --disable-shared \
    --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect \
    --disable-avdevice --disable-avfilter --disable-swscale --disable-postproc --enable-swresample \
    --enable-avcodec --enable-avformat --enable-decoder=aac,aac_latm,alac,pcm_s16le,pcm_s16be \
    --enable-parser=aac,aac_latm --enable-neon >/dev/null
  make -j"$JOBS" >/dev/null && make install >/dev/null
}

# ---- compatibility layer (gaps in bionic) -----------------------------------------------
b_compat() {
  fresh compat
  "$CC" $APP_CFLAGS -c "$HERE/compat/compat.c" -o compat.o
  "$CC" $APP_CFLAGS -I"$PREFIX/include" -c "$HERE/compat/uuid/uuid.c" -o uuid.o
  "$CC" $APP_CFLAGS -I"$HERE/compat/dns_sd" -c "$HERE/compat/dns_sd/dns_sd.c" -o dns_sd.o
  "$AR" rcs "$PREFIX/lib/libandroidcompat.a" compat.o
  "$AR" rcs "$PREFIX/lib/libuuid.a" uuid.o
  "$AR" rcs "$PREFIX/lib/libdns_sd.a" dns_sd.o
  mkdir -p "$PREFIX/include/uuid"
  cp "$HERE/compat/uuid/uuid.h" "$PREFIX/include/uuid/"
  cp "$HERE/compat/dns_sd/dns_sd.h" "$PREFIX/include/"
  # Android has no separate librt or libpthread; configure scripts insist on linking them.
  "$AR" rcs "$PREFIX/lib/librt.a"
  "$AR" rcs "$PREFIX/lib/libpthread.a"
}

# The compat header is forced in only at compile time: in configure's link tests it would
# clash with their fake prototypes.
CC_COMPAT="$CC -include $HERE/compat/android_compat.h -Wno-unused-command-line-argument"
CXX_COMPAT="$CXX -include $HERE/compat/android_compat.h -Wno-unused-command-line-argument"
APP_LDFLAGS="-L$PREFIX/lib -pie -Wl,--gc-sections -Wl,--as-needed"

b_nqptp() {
  rm -rf "$BUILD/nqptp" && cp -R "$SRC/nqptp-1.2.9" "$BUILD/nqptp" && cd "$BUILD/nqptp"
  autoreconf -fi >/dev/null 2>&1
  # AC_FUNC_MALLOC can't run its test when cross-compiling; older autoconf (Ubuntu's) then
  # assumes malloc(0) returns NULL and links against an rpl_malloc nqptp doesn't provide.
  # Bionic's malloc(0) returns a valid pointer.
  ac_cv_func_malloc_0_nonnull=yes \
    CFLAGS="$APP_CFLAGS -I$PREFIX/include" LDFLAGS="$APP_LDFLAGS" LIBS="-landroidcompat" \
    ./configure --host=arm-linux-androideabi >/dev/null
  make -j"$JOBS" CC="$CC_COMPAT" >/dev/null
  mkdir -p "$OUT" && "$STRIP" -o "$OUT/libnqptp.so" nqptp
}

b_shairport() {
  rm -rf "$BUILD/sps" && cp -R "$SRC/shairport-sync-5.6.2" "$BUILD/sps" && cd "$BUILD/sps"
  cp "$HERE/sps/audio_plugin.c" audio_stdout.c # the plugin backend takes the stdout backend's slot
  patch -p1 -s < "$HERE/sps/android.patch"
  autoreconf -fi >/dev/null 2>&1
  PKG_CONFIG="pkg-config --static" \
    CFLAGS="$APP_CFLAGS -I$PREFIX/include" CXXFLAGS="$APP_CFLAGS -I$PREFIX/include" \
    LDFLAGS="$APP_LDFLAGS -static-libstdc++" LIBS="-landroidcompat -llog -lm" \
    ./configure --host=arm-linux-androideabi --sysconfdir=/etc \
    --with-airplay-2 --with-ssl=openssl --with-dns_sd --with-stdout \
    --with-metadata --with-metadata-multicast >/dev/null
  make -j"$JOBS" CC="$CC_COMPAT" CXX="$CXX_COMPAT" >/dev/null
  grep -q "0x" plists/get_info_response.c || { echo "plistutil produced an empty info plist" >&2; exit 1; }
  mkdir -p "$OUT" && "$STRIP" -o "$OUT/libshairport.so" shairport-sync
}

STEPS=(fetch openssl sodium gpgerror gcrypt plist config popt ffmpeg compat nqptp shairport)
mkdir -p "$BUILD" "$PREFIX" "$OUT"
# Steps run directly (not in "cmd || handler", where bash ignores set -e), so any failing
# command stops the build; the trap says which step it was.
trap 'echo "!! step \"$step\" failed (exit $?)" >&2' ERR
for step in "${@:-${STEPS[@]}}"; do
  log "$step"
  case "$step" in
    fetch) fetch ;;
    *) "b_$step" ;;
  esac
done
trap - ERR
ls -l "$OUT"
