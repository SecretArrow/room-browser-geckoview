/*
 * rb_totp.c — TOTP (RFC 6238) engine and account store for Room Browser core.
 *
 * Everything lives in this one file on purpose: FIPS 180-4 SHA-1/SHA-256,
 * RFC 2104 HMAC, RFC 8018 PBKDF2, RFC 4648 Base32, the RFC 4226/6238 code
 * machine, otpauth:// parsing, a JSON-lines store mirroring rb_bookmarks.c,
 * and the encrypt-then-MAC backup format ("RB2FA1 ").  No crypto library —
 * see rb_totp.h for the reasoning.
 *
 * Conventions follow the other core stores: grow-by-doubling arrays, die on
 * out-of-memory, rb_json.h for parsing and escaping, and the clock passed in
 * by the caller so every output is reproducible in tests.
 */

#include "rb_totp.h"
#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Longest JSON line the loader accepts; longer lines are skipped whole. */
#define RB_TOTP_LINE 16384

/* PBKDF2 iteration count — part of the export wire format (rb_totp.h). */
#define TOTP_EXPORT_ITERS 120000u

/* ------------------------------ allocation ------------------------------- */

static void *totp_xmalloc(size_t n)
{
    void *p;

    if (n == 0) {
        n = 1;
    }
    p = malloc(n);
    if (p == NULL) {
        fprintf(stderr, "rb_totp: out of memory\n");
        exit(1);
    }
    return p;
}

static void *totp_xrealloc(void *p, size_t n)
{
    void *grown;

    if (n == 0) {
        n = 1;
    }
    grown = realloc(p, n);
    if (grown == NULL) {
        fprintf(stderr, "rb_totp: out of memory\n");
        exit(1);
    }
    return grown;
}

static char *totp_xstrdup(const char *s)
{
    size_t n = strlen(s) + 1;
    char *out = (char *)totp_xmalloc(n);

    memcpy(out, s, n);
    return out;
}

/* -------------------------------- SHA-1 ---------------------------------- */

typedef struct {
    unsigned int state[5];
    unsigned long long total; /* bytes fed so far */
    unsigned char block[64];
    size_t used;
} totp_sha1_ctx;

static void totp_sha1_init(totp_sha1_ctx *c)
{
    c->state[0] = 0x67452301u;
    c->state[1] = 0xEFCDAB89u;
    c->state[2] = 0x98BADCFEu;
    c->state[3] = 0x10325476u;
    c->state[4] = 0xC3D2E1F0u;
    c->total = 0;
    c->used = 0;
}

static void totp_sha1_block(unsigned int h[5], const unsigned char p[64])
{
    unsigned int w[80];
    unsigned int a, b, cc, d, e, t;
    int i;

    for (i = 0; i < 16; i++) {
        w[i] = ((unsigned int)p[4 * i] << 24) |
               ((unsigned int)p[4 * i + 1] << 16) |
               ((unsigned int)p[4 * i + 2] << 8) |
               (unsigned int)p[4 * i + 3];
    }
    for (i = 16; i < 80; i++) {
        t = w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16];
        w[i] = (t << 1) | (t >> 31);
    }
    a = h[0];
    b = h[1];
    cc = h[2];
    d = h[3];
    e = h[4];
    for (i = 0; i < 80; i++) {
        unsigned int f, k;

        if (i < 20) {
            f = (b & cc) | ((~b) & d);
            k = 0x5A827999u;
        } else if (i < 40) {
            f = b ^ cc ^ d;
            k = 0x6ED9EBA1u;
        } else if (i < 60) {
            f = (b & cc) | (b & d) | (cc & d);
            k = 0x8F1BBCDCu;
        } else {
            f = b ^ cc ^ d;
            k = 0xCA62C1D6u;
        }
        t = ((a << 5) | (a >> 27)) + f + e + k + w[i];
        e = d;
        d = cc;
        cc = (b << 30) | (b >> 2);
        b = a;
        a = t;
    }
    h[0] += a;
    h[1] += b;
    h[2] += cc;
    h[3] += d;
    h[4] += e;
}

static void totp_sha1_update(totp_sha1_ctx *c, const unsigned char *data,
                             size_t len)
{
    c->total += (unsigned long long)len;
    if (c->used > 0) {
        size_t need = 64 - c->used;
        size_t take = (len < need) ? len : need;

        if (take > 0) {
            memcpy(c->block + c->used, data, take);
        }
        c->used += take;
        data += take;
        len -= take;
        if (c->used == 64) {
            totp_sha1_block(c->state, c->block);
            c->used = 0;
        }
    }
    while (len >= 64) {
        totp_sha1_block(c->state, data);
        data += 64;
        len -= 64;
    }
    if (len > 0) {
        memcpy(c->block, data, len);
        c->used = len;
    }
}

static void totp_sha1_final(totp_sha1_ctx *c, unsigned char out[20])
{
    unsigned long long bits = c->total * 8;
    unsigned char pad[72];
    size_t padlen;
    size_t i;

    memset(pad, 0, sizeof(pad));
    pad[0] = 0x80;
    padlen = (c->used < 56) ? 64 - c->used : 128 - c->used;
    for (i = 0; i < 8; i++) {
        pad[padlen - 8 + i] = (unsigned char)(bits >> (56 - 8 * i));
    }
    totp_sha1_update(c, pad, padlen);
    for (i = 0; i < 5; i++) {
        out[4 * i] = (unsigned char)(c->state[i] >> 24);
        out[4 * i + 1] = (unsigned char)(c->state[i] >> 16);
        out[4 * i + 2] = (unsigned char)(c->state[i] >> 8);
        out[4 * i + 3] = (unsigned char)(c->state[i]);
    }
}

void rb_sha1(const unsigned char *data, size_t len, unsigned char out[20])
{
    totp_sha1_ctx c;

    totp_sha1_init(&c);
    totp_sha1_update(&c, data, len);
    totp_sha1_final(&c, out);
}

/* -------------------------------- SHA-256 -------------------------------- */

typedef struct {
    unsigned int state[8];
    unsigned long long total;
    unsigned char block[64];
    size_t used;
} totp_sha256_ctx;

static void totp_sha256_init(totp_sha256_ctx *c)
{
    c->state[0] = 0x6a09e667u;
    c->state[1] = 0xbb67ae85u;
    c->state[2] = 0x3c6ef372u;
    c->state[3] = 0xa54ff53au;
    c->state[4] = 0x510e527fu;
    c->state[5] = 0x9b05688cu;
    c->state[6] = 0x1f83d9abu;
    c->state[7] = 0x5be0cd19u;
    c->total = 0;
    c->used = 0;
}

static void totp_sha256_block(unsigned int h[8], const unsigned char p[64])
{
    static const unsigned int k[64] = {
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
        0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
        0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
        0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
        0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
        0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
        0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
        0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
        0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
    };
    unsigned int w[64];
    unsigned int a, b, cc, d, e, f, g, hh, t1, t2;
    int i;

    for (i = 0; i < 16; i++) {
        w[i] = ((unsigned int)p[4 * i] << 24) |
               ((unsigned int)p[4 * i + 1] << 16) |
               ((unsigned int)p[4 * i + 2] << 8) |
               (unsigned int)p[4 * i + 3];
    }
    for (i = 16; i < 64; i++) {
        unsigned int s0, s1;

        s0 = ((w[i - 15] >> 7) | (w[i - 15] << 25)) ^
             ((w[i - 15] >> 18) | (w[i - 15] << 14)) ^ (w[i - 15] >> 3);
        s1 = ((w[i - 2] >> 17) | (w[i - 2] << 15)) ^
             ((w[i - 2] >> 19) | (w[i - 2] << 13)) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    a = h[0];
    b = h[1];
    cc = h[2];
    d = h[3];
    e = h[4];
    f = h[5];
    g = h[6];
    hh = h[7];
    for (i = 0; i < 64; i++) {
        unsigned int s1, s0, ch, mj;

        s1 = ((e >> 6) | (e << 26)) ^ ((e >> 11) | (e << 21)) ^
             ((e >> 25) | (e << 7));
        ch = (e & f) ^ ((~e) & g);
        t1 = hh + s1 + ch + k[i] + w[i];
        s0 = ((a >> 2) | (a << 30)) ^ ((a >> 13) | (a << 19)) ^
             ((a >> 22) | (a << 10));
        mj = (a & b) ^ (a & cc) ^ (b & cc);
        t2 = s0 + mj;
        hh = g;
        g = f;
        f = e;
        e = d + t1;
        d = cc;
        cc = b;
        b = a;
        a = t1 + t2;
    }
    h[0] += a;
    h[1] += b;
    h[2] += cc;
    h[3] += d;
    h[4] += e;
    h[5] += f;
    h[6] += g;
    h[7] += hh;
}

static void totp_sha256_update(totp_sha256_ctx *c, const unsigned char *data,
                               size_t len)
{
    c->total += (unsigned long long)len;
    if (c->used > 0) {
        size_t need = 64 - c->used;
        size_t take = (len < need) ? len : need;

        if (take > 0) {
            memcpy(c->block + c->used, data, take);
        }
        c->used += take;
        data += take;
        len -= take;
        if (c->used == 64) {
            totp_sha256_block(c->state, c->block);
            c->used = 0;
        }
    }
    while (len >= 64) {
        totp_sha256_block(c->state, data);
        data += 64;
        len -= 64;
    }
    if (len > 0) {
        memcpy(c->block, data, len);
        c->used = len;
    }
}

static void totp_sha256_final(totp_sha256_ctx *c, unsigned char out[32])
{
    unsigned long long bits = c->total * 8;
    unsigned char pad[72];
    size_t padlen;
    size_t i;

    memset(pad, 0, sizeof(pad));
    pad[0] = 0x80;
    padlen = (c->used < 56) ? 64 - c->used : 128 - c->used;
    for (i = 0; i < 8; i++) {
        pad[padlen - 8 + i] = (unsigned char)(bits >> (56 - 8 * i));
    }
    totp_sha256_update(c, pad, padlen);
    for (i = 0; i < 8; i++) {
        out[4 * i] = (unsigned char)(c->state[i] >> 24);
        out[4 * i + 1] = (unsigned char)(c->state[i] >> 16);
        out[4 * i + 2] = (unsigned char)(c->state[i] >> 8);
        out[4 * i + 3] = (unsigned char)(c->state[i]);
    }
}

void rb_sha256(const unsigned char *data, size_t len, unsigned char out[32])
{
    totp_sha256_ctx c;

    totp_sha256_init(&c);
    totp_sha256_update(&c, data, len);
    totp_sha256_final(&c, out);
}

/* --------------------------------- HMAC ---------------------------------- */

void rb_hmac_sha1(const unsigned char *key, size_t key_len,
                  const unsigned char *data, size_t data_len,
                  unsigned char out[20])
{
    totp_sha1_ctx ctx;
    unsigned char kblock[64];
    unsigned char pad[64];
    unsigned char inner[20];
    size_t klen = (key != NULL) ? key_len : 0;
    size_t i;

    memset(kblock, 0, sizeof(kblock));
    if (klen > 64) {
        rb_sha1(key, klen, kblock); /* long keys are hashed first (RFC 2104) */
    } else if (klen > 0) {
        memcpy(kblock, key, klen);
    }
    for (i = 0; i < 64; i++) {
        pad[i] = (unsigned char)(kblock[i] ^ 0x36);
    }
    totp_sha1_init(&ctx);
    totp_sha1_update(&ctx, pad, sizeof(pad));
    if (data_len > 0 && data != NULL) {
        totp_sha1_update(&ctx, data, data_len);
    }
    totp_sha1_final(&ctx, inner);
    for (i = 0; i < 64; i++) {
        pad[i] = (unsigned char)(kblock[i] ^ 0x5c);
    }
    totp_sha1_init(&ctx);
    totp_sha1_update(&ctx, pad, sizeof(pad));
    totp_sha1_update(&ctx, inner, sizeof(inner));
    totp_sha1_final(&ctx, out);
}

void rb_hmac_sha256(const unsigned char *key, size_t key_len,
                    const unsigned char *data, size_t data_len,
                    unsigned char out[32])
{
    totp_sha256_ctx ctx;
    unsigned char kblock[64];
    unsigned char pad[64];
    unsigned char inner[32];
    size_t klen = (key != NULL) ? key_len : 0;
    size_t i;

    memset(kblock, 0, sizeof(kblock));
    if (klen > 64) {
        rb_sha256(key, klen, kblock);
    } else if (klen > 0) {
        memcpy(kblock, key, klen);
    }
    for (i = 0; i < 64; i++) {
        pad[i] = (unsigned char)(kblock[i] ^ 0x36);
    }
    totp_sha256_init(&ctx);
    totp_sha256_update(&ctx, pad, sizeof(pad));
    if (data_len > 0 && data != NULL) {
        totp_sha256_update(&ctx, data, data_len);
    }
    totp_sha256_final(&ctx, inner);
    for (i = 0; i < 64; i++) {
        pad[i] = (unsigned char)(kblock[i] ^ 0x5c);
    }
    totp_sha256_init(&ctx);
    totp_sha256_update(&ctx, pad, sizeof(pad));
    totp_sha256_update(&ctx, inner, sizeof(inner));
    totp_sha256_final(&ctx, out);
}

/* -------------------------------- PBKDF2 --------------------------------- */

/* RFC 8018 PBKDF2-HMAC-SHA256; dk_len may exceed 32 (block counter). */
static void totp_pbkdf2(const unsigned char *pass, size_t pass_len,
                        const unsigned char *salt, size_t salt_len,
                        unsigned long long iters, unsigned char *dk,
                        size_t dk_len)
{
    unsigned char u[32];
    unsigned char t[32];
    unsigned char *msg;
    unsigned int block = 1;
    size_t done = 0;

    msg = (unsigned char *)totp_xmalloc(salt_len + 4);
    memcpy(msg, salt, salt_len);
    while (done < dk_len) {
        unsigned long long k;
        size_t take;
        int j;

        msg[salt_len + 0] = (unsigned char)(block >> 24);
        msg[salt_len + 1] = (unsigned char)(block >> 16);
        msg[salt_len + 2] = (unsigned char)(block >> 8);
        msg[salt_len + 3] = (unsigned char)block;
        rb_hmac_sha256(pass, pass_len, msg, salt_len + 4, u);
        memcpy(t, u, 32);
        for (k = 1; k < iters; k++) {
            rb_hmac_sha256(pass, pass_len, u, 32, u);
            for (j = 0; j < 32; j++) {
                t[j] ^= u[j];
            }
        }
        take = dk_len - done;
        if (take > 32) {
            take = 32;
        }
        memcpy(dk + done, t, take);
        done += take;
        block++;
    }
    free(msg);
}

/* -------------------------------- Base32 --------------------------------- */

static int totp_b32_value(unsigned char c)
{
    if (c >= 'A' && c <= 'Z') {
        return c - 'A';
    }
    if (c >= 'a' && c <= 'z') {
        return c - 'a';
    }
    if (c >= '2' && c <= '7') {
        return c - '2' + 26;
    }
    return -1;
}

int rb_base32_decode(const char *in, unsigned char *out, size_t out_cap)
{
    unsigned int buf = 0;
    int bits = 0;
    size_t o = 0;
    const char *p = in;

    if (in == NULL) {
        return -1;
    }
    while (*p != '\0') {
        unsigned char c = (unsigned char)*p++;
        int v;

        if (c == ' ' || c == '\t' || c == '-' || c == '=') {
            continue; /* separators and padding are ignored */
        }
        v = totp_b32_value(c);
        if (v < 0) {
            return -1;
        }
        buf = ((buf << 5) | (unsigned int)v) & 0xffffu;
        bits += 5;
        if (bits >= 8) {
            bits -= 8;
            if (out != NULL) {
                if (o >= out_cap) {
                    return -1; /* output too small */
                }
                out[o] = (unsigned char)((buf >> bits) & 0xff);
            }
            o++;
        }
    }
    return (int)o; /* trailing <8 bits are dropped, as RFC 4648 allows */
}

int rb_base32_encode(const unsigned char *in, size_t len, char *out,
                     size_t out_cap)
{
    static const char alpha[33] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    unsigned int buf = 0;
    int bits = 0;
    size_t o = 0;
    size_t i;

    if (out == NULL) {
        return 0;
    }
    if ((len * 8 + 4) / 5 + 1 > out_cap) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        buf = ((buf << 8) | (unsigned int)in[i]) & 0xffffu;
        bits += 8;
        while (bits >= 5) {
            bits -= 5;
            out[o++] = alpha[(buf >> bits) & 31];
        }
    }
    if (bits > 0) {
        out[o++] = alpha[(buf << (5 - bits)) & 31];
    }
    out[o] = '\0';
    return 1;
}

/* ------------------------------- TOTP core ------------------------------- */

int rb_totp_code(const unsigned char *secret, size_t secret_len, int algo,
                 int digits, int period, long long unix_now, char *out)
{
    unsigned char mac[32];
    unsigned char msg[8];
    unsigned long long counter;
    unsigned long long pw;
    unsigned long long code;
    unsigned int bin;
    int i;
    int off;

    if (out == NULL) {
        return 0;
    }
    out[0] = '\0';
    if (secret == NULL || secret_len == 0) {
        return 0;
    }
    if (digits != 6 && digits != 8) {
        return 0;
    }
    if (period < 1) {
        return 0;
    }
    if (unix_now < 0) {
        return 0;
    }
    if (algo != RB_TOTP_SHA1 && algo != RB_TOTP_SHA256) {
        return 0;
    }

    counter = (unsigned long long)unix_now / (unsigned long long)period;
    for (i = 0; i < 8; i++) {
        msg[i] = (unsigned char)(counter >> (56 - 8 * i));
    }
    if (algo == RB_TOTP_SHA1) {
        rb_hmac_sha1(secret, secret_len, msg, sizeof(msg), mac);
        off = mac[19] & 0x0f;
    } else {
        rb_hmac_sha256(secret, secret_len, msg, sizeof(msg), mac);
        off = mac[31] & 0x0f;
    }
    bin = (((unsigned int)mac[off] & 0x7fu) << 24) |
          ((unsigned int)mac[off + 1] << 16) |
          ((unsigned int)mac[off + 2] << 8) |
          (unsigned int)mac[off + 3];
    pw = 1;
    for (i = 0; i < digits; i++) {
        pw *= 10;
    }
    code = (unsigned long long)bin % pw;
    snprintf(out, (size_t)digits + 1, "%0*llu", digits, code);
    return 1;
}

int rb_totp_seconds_remaining(int period, long long unix_now)
{
    long long rem;

    if (period < 1) {
        return 0;
    }
    rem = unix_now % period;
    if (rem < 0) {
        rem += period;
    }
    return (int)(period - rem); /* in [1, period], never 0 */
}

/* ----------------------------- otpauth:// URIs ---------------------------- */

static int totp_ieq_n(const char *a, const char *b, size_t n)
{
    size_t i;

    for (i = 0; i < n; i++) {
        char ca = a[i];
        char cb = b[i];

        if (ca >= 'A' && ca <= 'Z') {
            ca = (char)(ca - 'A' + 'a');
        }
        if (cb >= 'A' && cb <= 'Z') {
            cb = (char)(cb - 'A' + 'a');
        }
        if (ca != cb || ca == '\0') {
            return 0; /* also stops before reading past a short string */
        }
    }
    return 1;
}

/* Case-insensitive match of an n-char run against a literal. */
static int totp_key_is(const char *s, size_t n, const char *lit)
{
    if (strlen(lit) != n) {
        return 0;
    }
    return totp_ieq_n(s, lit, n);
}

static int totp_hexval(char c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

/* Percent-decodes the first n chars of s; bad % runs pass through as-is. */
static char *totp_pct_decode_n(const char *s, size_t n)
{
    char *out = (char *)totp_xmalloc(n + 1);
    size_t i = 0;
    size_t o = 0;

    while (i < n) {
        char c = s[i];

        if (c == '%' && i + 2 < n && totp_hexval(s[i + 1]) >= 0 &&
            totp_hexval(s[i + 2]) >= 0) {
            out[o] = (char)((totp_hexval(s[i + 1]) << 4) |
                            totp_hexval(s[i + 2]));
            o++;
            i += 3;
        } else {
            out[o] = c;
            o++;
            i++;
        }
    }
    out[o] = '\0';
    return out;
}

static int totp_algo_of(const char *s, size_t n)
{
    if (n == 4 && totp_ieq_n(s, "SHA1", 4)) {
        return RB_TOTP_SHA1;
    }
    if (n == 5 && totp_ieq_n(s, "SHA-1", 5)) {
        return RB_TOTP_SHA1;
    }
    if (n == 6 && totp_ieq_n(s, "SHA256", 6)) {
        return RB_TOTP_SHA256;
    }
    if (n == 7 && totp_ieq_n(s, "SHA-256", 7)) {
        return RB_TOTP_SHA256;
    }
    return -1;
}

/* Strict unsigned decimal in a run of n chars; 0 on anything else. */
static int totp_parse_num(const char *s, size_t n, long long *out)
{
    long long v = 0;
    size_t i;

    if (n == 0 || n > 18) {
        return 0;
    }
    for (i = 0; i < n; i++) {
        if (s[i] < '0' || s[i] > '9') {
            return 0;
        }
        v = v * 10 + (s[i] - '0');
    }
    *out = v;
    return 1;
}

void rb_totp_pending_free(rb_totp_pending *p)
{
    if (p == NULL) {
        return;
    }
    free(p->issuer);
    free(p->label);
    free(p->secret);
    memset(p, 0, sizeof(*p));
}

int rb_totp_parse_uri(const char *uri, rb_totp_pending *p)
{
    const char *h;
    const char *lp;
    const char *q;
    size_t hlen;
    size_t llen;
    char *label_dec = NULL;
    char *issuer_param = NULL;
    char *secret_param = NULL;
    unsigned char *secret = NULL;
    char *colon;
    char *rest;
    int algo = RB_TOTP_SHA1;
    int digits = RB_TOTP_DEFAULT_DIGITS;
    int period = RB_TOTP_DEFAULT_PERIOD;
    int secret_len;
    long long v;
    int ok = 0;

    if (p == NULL) {
        return 0;
    }
    memset(p, 0, sizeof(*p)); /* a failed parse leaves nothing to free */
    if (uri == NULL) {
        return 0;
    }
    if (!totp_ieq_n(uri, "otpauth:", 8)) {
        return 0;
    }
    h = uri + 8;
    if (h[0] != '/' || h[1] != '/') {
        return 0;
    }
    h += 2;
    hlen = strcspn(h, "/?");
    if (hlen != 4 || !totp_ieq_n(h, "totp", 4)) {
        return 0;
    }
    lp = h + hlen;
    llen = 0;
    if (*lp == '/') {
        lp++;
        llen = strcspn(lp, "?");
    } else if (*lp != '\0' && *lp != '?') {
        return 0;
    }
    q = lp + llen;
    if (*q == '?') {
        q++;
    }

    label_dec = totp_pct_decode_n(lp, llen);

    while (*q != '\0') {
        const char *amp = strchr(q, '&');
        const char *eq;
        size_t plen = (amp != NULL) ? (size_t)(amp - q) : strlen(q);
        size_t klen;
        size_t vlen;

        if (plen == 0) {
            if (amp == NULL) {
                break;
            }
            q = amp + 1;
            continue;
        }
        eq = (const char *)memchr(q, '=', plen);
        klen = (eq != NULL) ? (size_t)(eq - q) : plen;
        vlen = (eq != NULL) ? plen - klen - 1 : 0;
        if (eq != NULL && totp_key_is(q, klen, "secret")) {
            if (secret_param == NULL) {
                secret_param = totp_pct_decode_n(eq + 1, vlen);
            }
        } else if (eq != NULL && totp_key_is(q, klen, "issuer")) {
            if (issuer_param == NULL) {
                issuer_param = totp_pct_decode_n(eq + 1, vlen);
            }
        } else if (eq != NULL && totp_key_is(q, klen, "algorithm")) {
            algo = totp_algo_of(eq + 1, vlen);
            if (algo < 0) {
                goto fail;
            }
        } else if (eq != NULL && totp_key_is(q, klen, "digits")) {
            if (!totp_parse_num(eq + 1, vlen, &v) || (v != 6 && v != 8)) {
                goto fail;
            }
            digits = (int)v;
        } else if (eq != NULL && totp_key_is(q, klen, "period")) {
            if (!totp_parse_num(eq + 1, vlen, &v) || v < 1 || v > 86400) {
                goto fail;
            }
            period = (int)v;
        }
        if (amp == NULL) {
            break;
        }
        q = amp + 1;
    }

    if (secret_param == NULL || secret_param[0] == '\0') {
        goto fail; /* a decodable, non-empty secret is required */
    }
    secret_len = rb_base32_decode(secret_param, NULL, 0);
    if (secret_len <= 0) {
        goto fail;
    }
    secret = (unsigned char *)totp_xmalloc((size_t)secret_len);
    rb_base32_decode(secret_param, secret, (size_t)secret_len);

    /* "issuer:account" label: issuer falls back to the prefix before ':' and
     * the label becomes the account part after it. */
    colon = strchr(label_dec, ':');
    rest = NULL;
    if (colon != NULL) {
        *colon = '\0';
        rest = colon + 1;
    }

    p->issuer = totp_xstrdup((issuer_param != NULL) ? issuer_param
                             : ((colon != NULL) ? label_dec : ""));
    p->label = totp_xstrdup((rest != NULL) ? rest : label_dec);
    p->secret = secret;
    p->secret_len = (size_t)secret_len;
    p->algo = algo;
    p->digits = digits;
    p->period = period;
    ok = 1;

fail:
    if (!ok) {
        free(secret);
    }
    free(label_dec);
    free(issuer_param);
    free(secret_param);
    return ok;
}

/* -------------------------------- the store ------------------------------- */

struct rb_totp_store {
    rb_totp_account *items; /* id ASC by construction */
    int count;
    int cap;
    long next_id;
};

static void totp_release(rb_totp_account *a)
{
    free(a->issuer);
    free(a->label);
    free(a->secret);
    memset(a, 0, sizeof(*a));
}

static void totp_grow(rb_totp_store *s)
{
    int ncap;
    rb_totp_account *grown;

    if (s->count < s->cap) {
        return;
    }
    ncap = (s->cap > 0) ? s->cap * 2 : 8;
    grown = (rb_totp_account *)totp_xrealloc(
        s->items, (size_t)ncap * sizeof(rb_totp_account));
    s->items = grown;
    s->cap = ncap;
}

/* Stable insertion sort by id, so a hand-edited file cannot break the
 * "id ASC by construction" invariant the rest of the module relies on. */
static void totp_sort_by_id(rb_totp_store *s)
{
    int i;

    for (i = 1; i < s->count; i++) {
        rb_totp_account key = s->items[i];
        int j = i - 1;

        while (j >= 0 && s->items[j].id > key.id) {
            s->items[j + 1] = s->items[j];
            j--;
        }
        s->items[j + 1] = key;
    }
}

rb_totp_store *rb_totp_new(void)
{
    rb_totp_store *s = (rb_totp_store *)totp_xmalloc(sizeof(*s));

    memset(s, 0, sizeof(*s));
    s->next_id = 1; /* ids start at 1; 0 means "no id" everywhere */
    return s;
}

void rb_totp_free(rb_totp_store *s)
{
    int i;

    if (s == NULL) {
        return;
    }
    for (i = 0; i < s->count; i++) {
        totp_release(&s->items[i]);
    }
    free(s->items);
    free(s);
}

int rb_totp_count(const rb_totp_store *s)
{
    return (s != NULL) ? s->count : 0;
}

const rb_totp_account *rb_totp_at(const rb_totp_store *s, int index)
{
    if (s == NULL || index < 0 || index >= s->count) {
        return NULL;
    }
    return &s->items[index];
}

long rb_totp_add(rb_totp_store *s, const char *issuer, const char *label,
                 const unsigned char *secret, size_t secret_len, int algo,
                 int digits, int period, long long now_ms)
{
    rb_totp_account *a;

    if (s == NULL || secret == NULL || secret_len == 0) {
        return 0;
    }
    totp_grow(s);
    a = &s->items[s->count];
    memset(a, 0, sizeof(*a));
    a->id = s->next_id++;
    a->issuer = totp_xstrdup((issuer != NULL) ? issuer : "");
    a->label = totp_xstrdup((label != NULL) ? label : "");
    a->secret = (unsigned char *)totp_xmalloc(secret_len);
    memcpy(a->secret, secret, secret_len);
    a->secret_len = secret_len;
    a->algo = algo;
    a->digits = digits;
    a->period = period;
    a->created_at = now_ms;
    s->count++;
    return a->id;
}

rb_totp_account *rb_totp_get(rb_totp_store *s, long id)
{
    int i;

    if (s == NULL) {
        return NULL;
    }
    for (i = 0; i < s->count; i++) {
        if (s->items[i].id == id) {
            return &s->items[i];
        }
    }
    return NULL;
}

int rb_totp_remove(rb_totp_store *s, long id)
{
    int i;

    if (s == NULL) {
        return 0;
    }
    for (i = 0; i < s->count; i++) {
        if (s->items[i].id == id) {
            totp_release(&s->items[i]);
            if (i + 1 < s->count) {
                memmove(&s->items[i], &s->items[i + 1],
                        (size_t)(s->count - i - 1) * sizeof(rb_totp_account));
            }
            s->count--;
            return 1; /* next_id is not rewound: ids are never reused */
        }
    }
    return 0;
}

/* ------------------------------ persistence ------------------------------- */

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} totp_buf;

static void totp_buf_init(totp_buf *b)
{
    b->buf = NULL;
    b->len = 0;
    b->cap = 0;
}

static void totp_buf_addn(totp_buf *b, const char *s, size_t n)
{
    if (b->len + n + 1 > b->cap) {
        size_t ncap = (b->cap > 0) ? b->cap : 64;

        while (ncap < b->len + n + 1) {
            ncap *= 2;
        }
        b->buf = (char *)totp_xrealloc(b->buf, ncap);
        b->cap = ncap;
    }
    if (n > 0 && s != NULL) {
        memcpy(b->buf + b->len, s, n);
    }
    b->len += n;
    b->buf[b->len] = '\0';
}

static void totp_buf_add_str(totp_buf *b, const char *s)
{
    if (s != NULL) {
        totp_buf_addn(b, s, strlen(s));
    }
}

/* Appends s as a quoted, escaped JSON string. */
static void totp_buf_add_json(totp_buf *b, const char *s)
{
    char *esc = rb_json_escape((s != NULL) ? s : "");

    totp_buf_add_str(b, "\"");
    totp_buf_add_str(b, esc);
    totp_buf_add_str(b, "\"");
    free(esc);
}

/* One account as one JSON line (no trailing newline) — the exact text both
 * rb_totp_save writes and the encrypted export encrypts. */
static char *totp_row_line(const rb_totp_account *a)
{
    totp_buf b;
    char num[64];
    char *b32;
    size_t b32_need;

    totp_buf_init(&b);
    totp_buf_add_str(&b, "{\"id\":");
    snprintf(num, sizeof(num), "%ld", a->id);
    totp_buf_add_str(&b, num);
    totp_buf_add_str(&b, ",\"issuer\":");
    totp_buf_add_json(&b, a->issuer);
    totp_buf_add_str(&b, ",\"label\":");
    totp_buf_add_json(&b, a->label);
    totp_buf_add_str(&b, ",\"secret\":");
    b32_need = (a->secret_len * 8 + 4) / 5 + 1;
    b32 = (char *)totp_xmalloc(b32_need);
    if (rb_base32_encode(a->secret, a->secret_len, b32, b32_need)) {
        totp_buf_add_json(&b, b32);
    } else {
        totp_buf_add_json(&b, ""); /* unreachable: b32_need is exact */
    }
    free(b32);
    totp_buf_add_str(&b, ",\"algo\":");
    snprintf(num, sizeof(num), "%d", a->algo);
    totp_buf_add_str(&b, num);
    totp_buf_add_str(&b, ",\"digits\":");
    snprintf(num, sizeof(num), "%d", a->digits);
    totp_buf_add_str(&b, num);
    totp_buf_add_str(&b, ",\"period\":");
    snprintf(num, sizeof(num), "%d", a->period);
    totp_buf_add_str(&b, num);
    totp_buf_add_str(&b, ",\"created_at\":");
    snprintf(num, sizeof(num), "%lld", a->created_at);
    totp_buf_add_str(&b, num);
    totp_buf_add_str(&b, "}");
    return b.buf;
}

static char *totp_field_str(const char *line, const char *key)
{
    size_t pos;
    char *out = NULL;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] != '"' || !rb_json_parse_string(line, &pos, &out)) {
        return NULL;
    }
    return out;
}

static long long totp_field_num(const char *line, const char *key,
                                long long fallback)
{
    size_t pos;
    long long v = fallback;

    if (!rb_json_find_key(line, key, &pos)) {
        return fallback;
    }
    if (!rb_json_parse_number(line, &pos, &v)) {
        return fallback;
    }
    return v;
}

/* Parses one saved row; 1 and a filled `out` (with owned members) on
 * success, 0 on a malformed line (nothing allocated). */
static int totp_parse_line(const char *line, rb_totp_account *out)
{
    char *issuer = NULL;
    char *label = NULL;
    char *secret_s = NULL;
    unsigned char *secret = NULL;
    long long id;
    long long algo;
    long long digits;
    long long period;
    long long created;
    int secret_len;
    int ok = 0;

    memset(out, 0, sizeof(*out));
    issuer = totp_field_str(line, "issuer");
    label = totp_field_str(line, "label");
    secret_s = totp_field_str(line, "secret");
    if (secret_s == NULL || secret_s[0] == '\0') {
        goto fail;
    }
    algo = totp_field_num(line, "algo", -1);
    digits = totp_field_num(line, "digits", -1);
    period = totp_field_num(line, "period", -1);
    if (algo != RB_TOTP_SHA1 && algo != RB_TOTP_SHA256) {
        goto fail;
    }
    if (digits < 1 || digits > 10) {
        goto fail;
    }
    if (period < 1 || period > 86400) {
        goto fail;
    }
    id = totp_field_num(line, "id", 0);
    created = totp_field_num(line, "created_at", 0);

    secret_len = rb_base32_decode(secret_s, NULL, 0);
    if (secret_len <= 0) {
        goto fail;
    }
    secret = (unsigned char *)totp_xmalloc((size_t)secret_len);
    rb_base32_decode(secret_s, secret, (size_t)secret_len);

    out->id = (long)id;
    out->issuer = (issuer != NULL) ? issuer : totp_xstrdup("");
    out->label = (label != NULL) ? label : totp_xstrdup("");
    out->secret = secret;
    out->secret_len = (size_t)secret_len;
    out->algo = (int)algo;
    out->digits = (int)digits;
    out->period = (int)period;
    out->created_at = created;
    ok = 1;

fail:
    if (ok) {
        free(secret_s); /* the Base32 text is no longer needed */
    } else {
        free(issuer);
        free(label);
        free(secret_s);
        free(secret);
    }
    return ok;
}

int rb_totp_save(const rb_totp_store *s, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (s == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < s->count && rc == 0; i++) {
        char *line = totp_row_line(&s->items[i]);

        if (fputs(line, f) == EOF || fputc('\n', f) == EOF) {
            rc = -1;
        }
        free(line);
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

int rb_totp_load(rb_totp_store *s, const char *path)
{
    char buf[RB_TOTP_LINE];
    FILE *f;
    int rc = 0;

    if (s == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        rb_totp_account acct;

        if (len > 0 && buf[len - 1] != '\n' && !feof(f)) {
            int ch; /* line longer than the cap: skip it entirely */

            while ((ch = fgetc(f)) != EOF && ch != '\n') {
                /* drain */
            }
            continue;
        }
        while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
            buf[--len] = '\0';
        }
        if (len == 0) {
            continue;
        }
        if (!totp_parse_line(buf, &acct)) {
            continue; /* malformed lines are skipped */
        }
        if (acct.id <= 0) {
            acct.id = s->next_id;
        }
        if (acct.id >= s->next_id) {
            s->next_id = acct.id + 1;
        }
        totp_grow(s);
        s->items[s->count++] = acct;
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);
    totp_sort_by_id(s);
    return rc;
}

/* --------------------------- encrypted export ----------------------------- */

static void totp_be32(unsigned char *p, unsigned int v)
{
    p[0] = (unsigned char)(v >> 24);
    p[1] = (unsigned char)(v >> 16);
    p[2] = (unsigned char)(v >> 8);
    p[3] = (unsigned char)v;
}

static unsigned int totp_rd32(const unsigned char *p)
{
    return ((unsigned int)p[0] << 24) | ((unsigned int)p[1] << 16) |
           ((unsigned int)p[2] << 8) | (unsigned int)p[3];
}

static void totp_be64(unsigned char *p, unsigned long long v)
{
    int i;

    for (i = 0; i < 8; i++) {
        p[i] = (unsigned char)(v >> (56 - 8 * i));
    }
}

static int totp_b64_value(unsigned char c)
{
    if (c >= 'A' && c <= 'Z') {
        return c - 'A';
    }
    if (c >= 'a' && c <= 'z') {
        return c - 'a' + 26;
    }
    if (c >= '0' && c <= '9') {
        return c - '0' + 52;
    }
    if (c == '+') {
        return 62;
    }
    if (c == '/') {
        return 63;
    }
    return -1;
}

/* Standard Base64, unpadded both ways; decode rejects any non-alphabet
 * character (the payload is exactly one Base64 word) and truncated groups. */
static long totp_b64_decode(const char *in, unsigned char *out, size_t out_cap)
{
    unsigned int buf = 0;
    int bits = 0;
    size_t o = 0;
    const char *p = in;

    if (in == NULL) {
        return -1;
    }
    while (*p != '\0') {
        int v = totp_b64_value((unsigned char)*p);

        if (v < 0) {
            return -1;
        }
        p++;
        buf = ((buf << 6) | (unsigned int)v) & 0xffffu;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (out != NULL) {
                if (o >= out_cap) {
                    return -1;
                }
                out[o] = (unsigned char)((buf >> bits) & 0xff);
            }
            o++;
        }
    }
    if (bits >= 6) {
        return -1; /* a 1-char tail is a truncated group */
    }
    return (long)o;
}

static int totp_b64_encode(const unsigned char *in, size_t len, char *out,
                           size_t out_cap)
{
    static const char alpha[65] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    unsigned int buf = 0;
    int bits = 0;
    size_t o = 0;
    size_t i;

    if (out == NULL) {
        return 0;
    }
    if ((len * 8 + 5) / 6 + 1 > out_cap) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        buf = ((buf << 8) | (unsigned int)in[i]) & 0xffffu;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            out[o++] = alpha[(buf >> bits) & 63];
        }
    }
    if (bits > 0) {
        out[o++] = alpha[(buf << (6 - bits)) & 63];
    }
    out[o] = '\0';
    return 1;
}

/* A cheap non-cryptographic entropy mix for the salt and nonce: wall clock,
 * CPU clock, an address and a counter, XOR-folded through an avalanche
 * step.  Only needs to make two exports differ; the tag catches tampering. */
static unsigned int totp_mix(unsigned int x)
{
    x ^= x >> 16;
    x *= 2654435761u;
    x ^= x >> 13;
    x *= 2654435761u;
    x ^= x >> 16;
    return x;
}

static void totp_entropy(unsigned char *out, size_t n)
{
    static unsigned int counter = 0x2545F491u;
    unsigned int seed;
    size_t i;

    counter += 0x9E3779B9u;
    seed = (unsigned int)time(NULL);
    seed = totp_mix(seed ^ (unsigned int)clock());
    seed = totp_mix(seed ^ (unsigned int)(size_t)(const void *)&counter);
    seed = totp_mix(seed ^ counter);
    for (i = 0; i < n; i++) {
        seed = totp_mix(seed);
        out[i] = (unsigned char)((seed >> 24) & 0xff);
    }
}

int rb_totp_export_encrypted(const rb_totp_store *s, const char *passphrase,
                             char **out_blob)
{
    totp_buf pt;
    unsigned char rnd[32]; /* salt[16] || nonce[16] */
    unsigned char key[64]; /* key_enc[32] || key_mac[32] */
    unsigned char ks[32];
    unsigned char ctr[24];
    unsigned char want[32];
    unsigned char *payload;
    unsigned char *mac_in;
    char *b64;
    char *blob;
    size_t pt_len;
    size_t ct_len;
    size_t payload_len;
    size_t b64_len;
    size_t off;
    size_t take;
    size_t j;
    unsigned long long blk;
    int i;
    int rc = 1;

    if (out_blob != NULL) {
        *out_blob = NULL;
    }
    if (s == NULL || out_blob == NULL || passphrase == NULL ||
        passphrase[0] == '\0') {
        return 0;
    }

    /* The plaintext is exactly what rb_totp_save writes: one JSON line per
     * account, each terminated by '\n'. */
    totp_buf_init(&pt);
    for (i = 0; i < s->count; i++) {
        char *line = totp_row_line(&s->items[i]);

        totp_buf_add_str(&pt, line);
        totp_buf_add_str(&pt, "\n");
        free(line);
    }
    pt_len = pt.len;

    totp_entropy(rnd, sizeof(rnd));
    totp_pbkdf2((const unsigned char *)passphrase, strlen(passphrase), rnd,
                16, TOTP_EXPORT_ITERS, key, sizeof(key));

    ct_len = pt_len;
    payload_len = 16 + 4 + 16 + ct_len + 32;
    payload = (unsigned char *)totp_xmalloc(payload_len);
    memcpy(payload, rnd, 16); /* salt */
    totp_be32(payload + 16, TOTP_EXPORT_ITERS);
    memcpy(payload + 20, rnd + 16, 16); /* nonce */

    /* ct = plaintext XOR HMAC-SHA256(key_enc, nonce || be64(block)) */
    off = 0;
    blk = 0;
    while (off < ct_len) {
        take = ct_len - off;
        if (take > 32) {
            take = 32;
        }
        memcpy(ctr, rnd + 16, 16);
        totp_be64(ctr + 16, blk);
        rb_hmac_sha256(key, 32, ctr, sizeof(ctr), ks);
        for (j = 0; j < take; j++) {
            payload[36 + off + j] = (unsigned char)(pt.buf[off + j] ^ ks[j]);
        }
        off += take;
        blk++;
    }

    /* Encrypt-then-MAC: tag covers "RB2FA1" || nonce || ct. */
    mac_in = (unsigned char *)totp_xmalloc(22 + ct_len);
    memcpy(mac_in, "RB2FA1", 6);
    memcpy(mac_in + 6, rnd + 16, 16);
    if (ct_len > 0) {
        memcpy(mac_in + 22, payload + 36, ct_len);
    }
    rb_hmac_sha256(key + 32, 32, mac_in, 22 + ct_len, want);
    free(mac_in);
    memcpy(payload + 36 + ct_len, want, 32);

    b64_len = (payload_len * 8 + 5) / 6 + 1;
    b64 = (char *)totp_xmalloc(b64_len);
    if (!totp_b64_encode(payload, payload_len, b64, b64_len)) {
        rc = 0; /* unreachable: b64_len is exact */
    }
    if (rc) {
        blob = (char *)totp_xmalloc(7 + strlen(b64) + 1);
        memcpy(blob, "RB2FA1 ", 7);
        memcpy(blob + 7, b64, strlen(b64) + 1);
        *out_blob = blob;
    }
    free(b64);
    free(payload);
    free(pt.buf);
    return rc;
}

/* Index of the account holding this (issuer, label) pair, or -1.  Both
 * strings are never NULL: add() and totp_parse_line() store "" instead. */
static int totp_pair_index(const rb_totp_store *s, const char *issuer,
                           const char *label)
{
    int i;

    for (i = 0; i < s->count; i++) {
        if (strcmp(s->items[i].issuer, issuer) == 0 &&
            strcmp(s->items[i].label, label) == 0) {
            return i;
        }
    }
    return -1;
}

/* Merges one decrypted line into s; used by rb_totp_import_encrypted. */
static void totp_import_line(rb_totp_store *s, const char *line, int *merged)
{
    rb_totp_account acct;

    if (!totp_parse_line(line, &acct)) {
        return;
    }
    if (totp_pair_index(s, acct.issuer, acct.label) >= 0) {
        totp_release(&acct); /* (issuer, label) already held: skip */
        return;
    }
    acct.id = s->next_id++; /* fresh id keeps "id ASC by construction" */
    totp_grow(s);
    s->items[s->count++] = acct;
    (*merged)++;
}

int rb_totp_import_encrypted(rb_totp_store *s, const char *blob,
                             const char *passphrase)
{
    unsigned char key[64];
    unsigned char ks[32];
    unsigned char ctr[24];
    unsigned char want[32];
    unsigned char *payload = NULL;
    unsigned char *mac_in;
    unsigned char *pt = NULL;
    const unsigned char *nonce;
    const unsigned char *ct;
    const char *b64;
    size_t payload_len;
    size_t ct_len;
    size_t off;
    size_t take;
    size_t j;
    size_t i;
    size_t start;
    unsigned long long blk;
    long n;
    int merged = 0;
    int bad = 0;

    if (s == NULL || blob == NULL || passphrase == NULL ||
        passphrase[0] == '\0') {
        return -1;
    }
    if (strncmp(blob, "RB2FA1 ", 7) != 0) {
        return -1;
    }
    b64 = blob + 7;
    n = totp_b64_decode(b64, NULL, 0);
    if (n < 16 + 4 + 16 + 32) {
        return -1;
    }
    payload_len = (size_t)n;
    payload = (unsigned char *)totp_xmalloc(payload_len);
    totp_b64_decode(b64, payload, payload_len); /* cannot fail now */
    if (totp_rd32(payload + 16) != TOTP_EXPORT_ITERS) {
        free(payload);
        return -1; /* unknown iteration count: not our format */
    }

    nonce = payload + 20;
    ct_len = payload_len - 68;
    ct = payload + 36;

    totp_pbkdf2((const unsigned char *)passphrase, strlen(passphrase),
                payload /* salt */, 16, TOTP_EXPORT_ITERS, key, sizeof(key));

    /* Verify the tag BEFORE decrypting anything. */
    mac_in = (unsigned char *)totp_xmalloc(22 + ct_len);
    memcpy(mac_in, "RB2FA1", 6);
    memcpy(mac_in + 6, nonce, 16);
    if (ct_len > 0) {
        memcpy(mac_in + 22, ct, ct_len);
    }
    rb_hmac_sha256(key + 32, 32, mac_in, 22 + ct_len, want);
    free(mac_in);
    for (j = 0; j < 32; j++) {
        if (want[j] != payload[36 + ct_len + j]) {
            bad = 1;
        }
    }
    if (bad) {
        free(payload);
        return -1; /* wrong passphrase or tampered blob */
    }

    /* Tag verified: decrypt into lines and merge. */
    pt = (unsigned char *)totp_xmalloc(ct_len + 1);
    off = 0;
    blk = 0;
    while (off < ct_len) {
        take = ct_len - off;
        if (take > 32) {
            take = 32;
        }
        memcpy(ctr, nonce, 16);
        totp_be64(ctr + 16, blk);
        rb_hmac_sha256(key, 32, ctr, sizeof(ctr), ks);
        for (j = 0; j < take; j++) {
            pt[off + j] = (unsigned char)(ct[off + j] ^ ks[j]);
        }
        off += take;
        blk++;
    }
    pt[ct_len] = '\0';

    start = 0;
    for (i = 0; i < ct_len; i++) {
        if (pt[i] == '\n') {
            pt[i] = '\0';
            totp_import_line(s, (const char *)pt + start, &merged);
            start = i + 1;
        }
    }
    totp_import_line(s, (const char *)pt + start, &merged);

    free(pt);
    free(payload);
    return merged;
}
