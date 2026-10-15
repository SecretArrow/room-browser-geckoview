/*
 * frag_totp.c — checks for core/rb_totp, #included into tests/test_core.c.
 *
 * One translation unit, so everything here is static and prefixed totp_;
 * the single external symbol is rb_test_totp_all(), which runs every check
 * and returns the number of failures (0 = all green).  A failing
 * RB_TOTP_CHECK prints "FAIL totp: ..." and returns 1, which the entry
 * point propagates unchanged — a red run stops at the first broken
 * invariant instead of cascading.
 *
 * Coverage: FIPS 180-4 SHA-1/SHA-256, RFC 2104 HMAC (short and long keys),
 * RFC 4648 Base32 both ways, RFC 4226/6238 code generation and the window
 * clock, otpauth:// parsing (accepts and rejects), the JSON-lines store
 * roundtrip through "rb-totp-test.jsonl" (removed at the end, like every
 * temp file here), and the "RB2FA1 " encrypt-then-MAC export/import with a
 * wrong passphrase, a flipped payload byte and a re-import.
 */

#include "core/rb_totp.h"

static int g_totp_checks = 0;

#define RB_TOTP_CHECK(cond)                                                  \
    do {                                                                     \
        g_totp_checks++;                                                     \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL totp: %s (%s:%d)\n", #cond,                \
                    __FILE__, __LINE__);                                     \
            return 1;                                                        \
        }                                                                    \
    } while (0)

#define TOTP_TMP "rb-totp-test.jsonl"

#define TOTP_STAMP 1700000000LL

/* Renders n bytes as lowercase hex; 1 when it equals `want`. */
static int totp_hex_eq(const unsigned char *got, size_t n, const char *want)
{
    static const char hexd[] = "0123456789abcdef";
    char buf[129];
    size_t i;

    if (n * 2 + 1 > sizeof(buf)) {
        return 0;
    }
    for (i = 0; i < n; i++) {
        buf[2 * i] = hexd[got[i] >> 4];
        buf[2 * i + 1] = hexd[got[i] & 15];
    }
    buf[2 * n] = '\0';
    return strcmp(buf, want) == 0;
}

/* ------------------------------- digests --------------------------------- */

static int totp_test_digests(void)
{
    unsigned char d[32];
    static const char msg56[] =
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";

    rb_sha1((const unsigned char *)"abc", 3, d);
    RB_TOTP_CHECK(totp_hex_eq(d, 20, "a9993e364706816aba3e25717850c26c9cd0d89d"));
    rb_sha1((const unsigned char *)"", 0, d);
    RB_TOTP_CHECK(totp_hex_eq(d, 20, "da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    rb_sha1(NULL, 0, d); /* NULL with len 0 is the empty message */
    RB_TOTP_CHECK(totp_hex_eq(d, 20, "da39a3ee5e6b4b0d3255bfef95601890afd80709"));

    rb_sha256((const unsigned char *)"abc", 3, d);
    RB_TOTP_CHECK(totp_hex_eq(d, 32,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    RB_TOTP_CHECK((int)strlen(msg56) == 56); /* two-block padding boundary */
    rb_sha256((const unsigned char *)msg56, 56, d);
    RB_TOTP_CHECK(totp_hex_eq(d, 32,
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"));
    return 0;
}

static int totp_test_hmac(void)
{
    unsigned char key[131];
    unsigned char mac[32];

    /* RFC 2202 #1 / RFC 4231 #1: 20-byte 0x0b key, "Hi There". */
    memset(key, 0x0b, 20);
    rb_hmac_sha1(key, 20, (const unsigned char *)"Hi There", 8, mac);
    RB_TOTP_CHECK(totp_hex_eq(mac, 20, "b617318655057264e28bc0b6fb378c8ef146be00"));
    rb_hmac_sha256(key, 20, (const unsigned char *)"Hi There", 8, mac);
    RB_TOTP_CHECK(totp_hex_eq(mac, 32,
        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"));

    /* Long-key branch: RFC 2202 #7 (80-byte key) and RFC 4231 #6 (131). */
    memset(key, 0xaa, sizeof(key));
    rb_hmac_sha1(key, 80,
                 (const unsigned char *)
                     "Test Using Larger Than Block-Size Key - Hash Key First",
                 54, mac);
    RB_TOTP_CHECK(totp_hex_eq(mac, 20, "aa4ae5e15272d00e95705637ce8a3b55ed402112"));
    rb_hmac_sha256(key, 131,
                   (const unsigned char *)
                       "Test Using Larger Than Block-Size Key - Hash Key First",
                   54, mac);
    RB_TOTP_CHECK(totp_hex_eq(mac, 32,
        "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"));
    return 0;
}

/* -------------------------------- Base32 ---------------------------------- */

static int totp_test_base32(void)
{
    unsigned char raw[32];
    char enc[64];
    int n;

    /* RFC 4648 vectors, unpadded. */
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"", 0, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"f", 1, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MY") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"fo", 2, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MZXQ") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"foo", 3, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MZXW6") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"foob", 4, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MZXW6YQ") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"fooba", 5, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MZXW6YTB") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"foobar", 6, enc,
                                   sizeof(enc)) == 1);
    RB_TOTP_CHECK(strcmp(enc, "MZXW6YTBOI") == 0);
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"foobar", 6, enc,
                                   8) == 0); /* cap too small */
    RB_TOTP_CHECK(rb_base32_encode((const unsigned char *)"foobar", 6, NULL,
                                   64) == 0);

    /* Decode: roundtrip, then case/space/'-'/padding tolerance. */
    n = rb_base32_decode("MZXW6YTBOI", raw, sizeof(raw));
    RB_TOTP_CHECK(n == 6 && memcmp(raw, "foobar", 6) == 0);
    n = rb_base32_decode("mZxW6 ytb-oi======", raw, sizeof(raw));
    RB_TOTP_CHECK(n == 6 && memcmp(raw, "foobar", 6) == 0);
    RB_TOTP_CHECK(rb_base32_decode("MY======", NULL, 0) == 1);
    RB_TOTP_CHECK(rb_base32_decode("MZXW6YTB", NULL, 0) == 5);
    RB_TOTP_CHECK(rb_base32_decode("M", NULL, 0) == 0); /* trailing <8 bits */
    RB_TOTP_CHECK(rb_base32_decode(NULL, raw, sizeof(raw)) == -1);
    RB_TOTP_CHECK(rb_base32_decode("MZ1W6YTBOI", raw, sizeof(raw)) == -1);
    RB_TOTP_CHECK(rb_base32_decode("MZXW6YTBO*", raw, sizeof(raw)) == -1);
    RB_TOTP_CHECK(rb_base32_decode("MZXW6YTBOI", raw, 3) == -1); /* too small */
    return 0;
}

/* --------------------------------- TOTP ----------------------------------- */

static int totp_test_code(void)
{
    static const char seed20[] = "12345678901234567890";
    static const char seed32[] = "12345678901234567890123456789012";
    char code[16];

    /* RFC 6238 vectors, SHA-1 seed, 8 digits. */
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 8, 30, 59, code) == 1);
    RB_TOTP_CHECK(strcmp(code, "94287082") == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 8, 30, 1111111109LL,
                               code) == 1);
    RB_TOTP_CHECK(strcmp(code, "07081804") == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 8, 30, 1234567890LL,
                               code) == 1);
    RB_TOTP_CHECK(strcmp(code, "89005924") == 0);
    /* 6-digit variant is the tail of the same truncation. */
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 6, 30, 59, code) == 1);
    RB_TOTP_CHECK(strcmp(code, "287082") == 0);
    /* RFC 6238 SHA-256 seed, T=59. */
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed32, 32,
                               RB_TOTP_SHA256, 8, 30, 59, code) == 1);
    RB_TOTP_CHECK(strcmp(code, "46119246") == 0);

    /* Bad arguments yield 0 and an empty string. */
    code[0] = 'x';
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 6, 30, -1, code) == 0);
    RB_TOTP_CHECK(code[0] == '\0');
    RB_TOTP_CHECK(rb_totp_code(NULL, 0, RB_TOTP_SHA1, 6, 30, 59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 0,
                               RB_TOTP_SHA1, 6, 30, 59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 5, 30, 59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 11, 30, 59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 6, 0, 59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20, 7, 6, 30,
                               59, code) == 0);
    RB_TOTP_CHECK(rb_totp_code((const unsigned char *)seed20, 20,
                               RB_TOTP_SHA1, 6, 30, 59, NULL) == 0);

    /* Window clock: period - now % period, never 0. */
    RB_TOTP_CHECK(rb_totp_seconds_remaining(30, 59) == 1);
    RB_TOTP_CHECK(rb_totp_seconds_remaining(30, 60) == 30);
    RB_TOTP_CHECK(rb_totp_seconds_remaining(30, 91) == 29);
    RB_TOTP_CHECK(rb_totp_seconds_remaining(60, 0) == 60);
    RB_TOTP_CHECK(rb_totp_seconds_remaining(7, 13) == 1);
    RB_TOTP_CHECK(rb_totp_seconds_remaining(0, 59) == 0);
    return 0;
}

/* ------------------------------ otpauth:// -------------------------------- */

static int totp_test_uri(void)
{
    rb_totp_pending p;
    int rc;

    rc = rb_totp_parse_uri(
        "otpauth://totp/ACME%20Co:john@example.com"
        "?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co", &p);
    RB_TOTP_CHECK(rc == 1);
    RB_TOTP_CHECK(strcmp(p.issuer, "ACME Co") == 0);
    RB_TOTP_CHECK(strcmp(p.label, "john@example.com") == 0);
    RB_TOTP_CHECK(p.algo == RB_TOTP_SHA1);
    RB_TOTP_CHECK(p.digits == 6);
    RB_TOTP_CHECK(p.period == 30);
    RB_TOTP_CHECK(p.secret_len == 10);
    RB_TOTP_CHECK(memcmp(p.secret, "Hello!\xde\xad\xbe\xef", 10) == 0);
    rb_totp_pending_free(&p);
    RB_TOTP_CHECK(p.issuer == NULL && p.label == NULL && p.secret == NULL);

    /* Issuer falls back to the label prefix before ':'. */
    rc = rb_totp_parse_uri("otpauth://totp/Bob:bob@x?secret=JBSWY3DPEHPK3PXP",
                           &p);
    RB_TOTP_CHECK(rc == 1);
    RB_TOTP_CHECK(strcmp(p.issuer, "Bob") == 0);
    RB_TOTP_CHECK(strcmp(p.label, "bob@x") == 0);
    rb_totp_pending_free(&p);

    /* Case-insensitive scheme/host, lowercase secret, SHA-256, 8/60. */
    rc = rb_totp_parse_uri(
        "OTPAUTH://TOTP/x?secret=jbswy3dpehpk3pxp&algorithm=SHA-256"
        "&digits=8&period=60", &p);
    RB_TOTP_CHECK(rc == 1);
    RB_TOTP_CHECK(p.algo == RB_TOTP_SHA256);
    RB_TOTP_CHECK(p.digits == 8);
    RB_TOTP_CHECK(p.period == 60);
    rb_totp_pending_free(&p);

    /* "sha1" (lowercase, no dash) and "SHA1" both select SHA-1. */
    rc = rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=sha1", &p);
    RB_TOTP_CHECK(rc == 1 && p.algo == RB_TOTP_SHA1);
    rb_totp_pending_free(&p);

    /* Rejections. */
    RB_TOTP_CHECK(rb_totp_parse_uri(NULL, &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("https://totp/x?secret=JBSWY3DPEHPK3PXP",
                                    &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP",
                                    &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("otpauth://totp/x?issuer=Acme", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("otpauth://totp/x?secret=", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri("otpauth://totp/x?secret=MZXW6YTB!",
                                    &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=7", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=abc", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=86401", &p) == 0);
    RB_TOTP_CHECK(rb_totp_parse_uri(
        "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5", &p) == 0);
    rb_totp_pending_free(&p); /* safe on the zeroed failed-parse struct */
    return 0;
}

/* -------------------------------- the store -------------------------------- */

static int totp_test_store(void)
{
    const rb_totp_account *a;
    rb_totp_account *m;
    rb_totp_store *s;
    rb_totp_store *s2;
    unsigned char sec1[10];
    unsigned char sec2[5];
    char c1[16];
    char c2[16];
    long id1;
    long id2;
    long id3;
    int k;
    FILE *f;

    memcpy(sec1, "Hello!\xde\xad\xbe\xef", 10);
    memcpy(sec2, "abcde", 5);

    s = rb_totp_new();
    RB_TOTP_CHECK(s != NULL);
    RB_TOTP_CHECK(rb_totp_count(s) == 0);
    RB_TOTP_CHECK(rb_totp_at(s, 0) == NULL);
    RB_TOTP_CHECK(rb_totp_at(NULL, 0) == NULL);

    /* add(): 0 for a NULL store or an empty secret, nothing else. */
    RB_TOTP_CHECK(rb_totp_add(s, "x", "y", sec1, 0, RB_TOTP_SHA1, 6, 30,
                              1000) == 0);
    RB_TOTP_CHECK(rb_totp_add(NULL, "x", "y", sec1, 10, RB_TOTP_SHA1, 6, 30,
                              1000) == 0);
    RB_TOTP_CHECK(rb_totp_count(s) == 0);

    id1 = rb_totp_add(s, "Acme", "john@x", sec1, 10, RB_TOTP_SHA1, 6, 30,
                      1000);
    RB_TOTP_CHECK(id1 > 0);
    id2 = rb_totp_add(s, "", NULL, sec2, 5, RB_TOTP_SHA256, 8, 60, 2000);
    RB_TOTP_CHECK(id2 == id1 + 1);
    id3 = rb_totp_add(s, "A\"b\\c", "l\nx", sec1, 10, RB_TOTP_SHA1, 6, 30,
                      3000); /* JSON escaping roundtrip */
    RB_TOTP_CHECK(id3 == id2 + 1);
    RB_TOTP_CHECK(rb_totp_count(s) == 3);

    a = rb_totp_at(s, 0);
    RB_TOTP_CHECK(a != NULL && a->id == id1);
    RB_TOTP_CHECK(strcmp(a->issuer, "Acme") == 0);
    RB_TOTP_CHECK(strcmp(a->label, "john@x") == 0);
    RB_TOTP_CHECK(a->secret_len == 10 && memcmp(a->secret, sec1, 10) == 0);
    RB_TOTP_CHECK(a->created_at == 1000);
    a = rb_totp_at(s, 1);
    RB_TOTP_CHECK(a != NULL && strcmp(a->issuer, "") == 0);
    RB_TOTP_CHECK(strcmp(a->label, "") == 0); /* NULL stored as "" */
    RB_TOTP_CHECK(a->algo == RB_TOTP_SHA256 && a->digits == 8 &&
                  a->period == 60);
    RB_TOTP_CHECK(rb_totp_at(s, 3) == NULL && rb_totp_at(s, -1) == NULL);
    m = rb_totp_get(s, id3);
    RB_TOTP_CHECK(m != NULL && strcmp(m->issuer, "A\"b\\c") == 0);
    RB_TOTP_CHECK(rb_totp_get(s, 999) == NULL && rb_totp_get(NULL, 1) == NULL);

    /* Save/load roundtrip through the temp file. */
    RB_TOTP_CHECK(rb_totp_save(s, TOTP_TMP) == 0);
    RB_TOTP_CHECK(rb_totp_save(NULL, TOTP_TMP) == -1);
    s2 = rb_totp_new();
    RB_TOTP_CHECK(rb_totp_load(s2, TOTP_TMP) == 0);
    RB_TOTP_CHECK(rb_totp_load(NULL, TOTP_TMP) == -1);
    RB_TOTP_CHECK(rb_totp_count(s2) == 3);
    a = rb_totp_at(s2, 0);
    RB_TOTP_CHECK(a->id == id1 && a->secret_len == 10);
    RB_TOTP_CHECK(memcmp(a->secret, sec1, 10) == 0);
    RB_TOTP_CHECK(a->algo == RB_TOTP_SHA1 && a->digits == 6 &&
                  a->period == 30 && a->created_at == 1000);
    a = rb_totp_at(s2, 2);
    RB_TOTP_CHECK(strcmp(a->issuer, "A\"b\\c") == 0);
    RB_TOTP_CHECK(strcmp(a->label, "l\nx") == 0);
    m = rb_totp_get(s2, id1);
    RB_TOTP_CHECK(m != NULL);
    RB_TOTP_CHECK(rb_totp_code(m->secret, m->secret_len, m->algo, m->digits,
                               m->period, TOTP_STAMP, c1) == 1);
    RB_TOTP_CHECK(rb_totp_code(sec1, 10, RB_TOTP_SHA1, 6, 30, TOTP_STAMP,
                               c2) == 1);
    RB_TOTP_CHECK(strcmp(c1, c2) == 0); /* codes survive the roundtrip */
    rb_totp_free(s2);

    /* A missing file loads as empty without error. */
    remove(TOTP_TMP);
    s2 = rb_totp_new();
    RB_TOTP_CHECK(rb_totp_load(s2, TOTP_TMP) == 0);
    RB_TOTP_CHECK(rb_totp_count(s2) == 0);

    /* Malformed rows are skipped; the id counter advances past the highest
     * id seen; an overlong line is skipped whole. */
    f = fopen(TOTP_TMP, "wb");
    RB_TOTP_CHECK(f != NULL);
    fprintf(f, "not json at all\n");
    fprintf(f, "{\"id\":77,\"issuer\":\"X\",\"label\":\"y\","
               "\"secret\":\"MZXW6YTB\",\"algo\":1,\"digits\":8,"
               "\"period\":60,\"created_at\":9}\n");
    fprintf(f, "{\"issuer\":\"no-secret\"}\n");
    fprintf(f, "{\"id\":5,\"label\":\"bad-algo\",\"secret\":\"MZXW6YTB\","
               "\"algo\":9,\"digits\":6,\"period\":30,\"created_at\":1}\n");
    fprintf(f, "\n");
    fprintf(f, "{\"id\":1,\"issuer\":\"");
    for (k = 0; k < 17000; k++) {
        fputc('x', f);
    }
    fprintf(f, "\"}\n");
    fclose(f);
    RB_TOTP_CHECK(rb_totp_load(s2, TOTP_TMP) == 0);
    RB_TOTP_CHECK(rb_totp_count(s2) == 1);
    a = rb_totp_at(s2, 0);
    RB_TOTP_CHECK(a->id == 77 && strcmp(a->issuer, "X") == 0);
    RB_TOTP_CHECK(a->secret_len == 5 && memcmp(a->secret, "fooba", 5) == 0);
    RB_TOTP_CHECK(rb_totp_add(s2, "Z", "z", sec1, 10, RB_TOTP_SHA1, 6, 30,
                              4000) == 78);

    /* Removal never rewinds the id counter. */
    RB_TOTP_CHECK(rb_totp_remove(s2, 77) == 1);
    RB_TOTP_CHECK(rb_totp_remove(s2, 77) == 0);
    RB_TOTP_CHECK(rb_totp_count(s2) == 1);
    RB_TOTP_CHECK(rb_totp_add(s2, "W", "w", sec1, 10, RB_TOTP_SHA1, 6, 30,
                              5000) == 79);
    RB_TOTP_CHECK(rb_totp_remove(NULL, 1) == 0);

    rb_totp_free(s);
    rb_totp_free(s2);
    s2 = NULL;
    remove(TOTP_TMP);
    return 0;
}

/* --------------------------- encrypted export ------------------------------ */

static int totp_test_crypt(void)
{
    const rb_totp_account *a;
    rb_totp_store *s;
    rb_totp_store *s2;
    rb_totp_store *s3;
    unsigned char sec1[10];
    unsigned char sec2[5];
    char c1[16];
    char c2[16];
    char c3[16];
    char c4[16];
    char *blob = NULL;
    char *empty_blob = NULL;
    char orig;

    memcpy(sec1, "Hello!\xde\xad\xbe\xef", 10);
    memcpy(sec2, "abcde", 5);

    s = rb_totp_new();
    RB_TOTP_CHECK(rb_totp_add(s, "Acme", "john@x", sec1, 10, RB_TOTP_SHA1, 6,
                              30, 1000) > 0);
    RB_TOTP_CHECK(rb_totp_add(s, "Beta", "eve@y", sec2, 5, RB_TOTP_SHA256, 8,
                              60, 2000) > 0);
    RB_TOTP_CHECK(rb_totp_code(sec1, 10, RB_TOTP_SHA1, 6, 30, TOTP_STAMP,
                               c1) == 1);
    RB_TOTP_CHECK(rb_totp_code(sec2, 5, RB_TOTP_SHA256, 8, 60, TOTP_STAMP,
                               c2) == 1);

    /* Export guards: NULL store, NULL out, empty passphrase. */
    RB_TOTP_CHECK(rb_totp_export_encrypted(NULL, "hunter2", &blob) == 0);
    RB_TOTP_CHECK(rb_totp_export_encrypted(s, "hunter2", NULL) == 0);
    RB_TOTP_CHECK(rb_totp_export_encrypted(s, "", &blob) == 0);
    RB_TOTP_CHECK(blob == NULL);

    RB_TOTP_CHECK(rb_totp_export_encrypted(s, "hunter2", &blob) == 1);
    RB_TOTP_CHECK(blob != NULL && strncmp(blob, "RB2FA1 ", 7) == 0);

    s2 = rb_totp_new();
    /* Wrong passphrase: -1, nothing merged. */
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, "hunter3") == -1);
    RB_TOTP_CHECK(rb_totp_count(s2) == 0);
    /* Bad prefix, garbage, short and NULL blobs. */
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob + 1, "hunter2") == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, "RB2FA1 ", "hunter2") == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, "RB2FA1 !!!!", "hunter2") == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, NULL, "hunter2") == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, NULL) == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, "") == -1);
    RB_TOTP_CHECK(rb_totp_import_encrypted(NULL, blob, "hunter2") == -1);

    /* One flipped payload byte must fail the tag. */
    orig = blob[7];
    blob[7] = (orig == 'A') ? 'B' : 'A';
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, "hunter2") == -1);
    RB_TOTP_CHECK(rb_totp_count(s2) == 0);
    blob[7] = orig;

    /* Honest import: both accounts merge and the codes still match. */
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, "hunter2") == 2);
    RB_TOTP_CHECK(rb_totp_count(s2) == 2);
    a = rb_totp_at(s2, 0);
    RB_TOTP_CHECK(strcmp(a->issuer, "Acme") == 0);
    RB_TOTP_CHECK(strcmp(a->label, "john@x") == 0);
    RB_TOTP_CHECK(a->secret_len == 10 && memcmp(a->secret, sec1, 10) == 0);
    RB_TOTP_CHECK(a->algo == RB_TOTP_SHA1 && a->digits == 6 &&
                  a->period == 30);
    RB_TOTP_CHECK(rb_totp_code(a->secret, a->secret_len, a->algo, a->digits,
                               a->period, TOTP_STAMP, c3) == 1);
    RB_TOTP_CHECK(strcmp(c1, c3) == 0);
    a = rb_totp_at(s2, 1);
    RB_TOTP_CHECK(strcmp(a->issuer, "Beta") == 0);
    RB_TOTP_CHECK(a->secret_len == 5 && memcmp(a->secret, sec2, 5) == 0);
    RB_TOTP_CHECK(rb_totp_code(a->secret, a->secret_len, a->algo, a->digits,
                               a->period, TOTP_STAMP, c4) == 1);
    RB_TOTP_CHECK(strcmp(c2, c4) == 0);

    /* Re-import into the merged store: every pair exists, 0 merged. */
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, blob, "hunter2") == 0);
    RB_TOTP_CHECK(rb_totp_count(s2) == 2);

    /* An empty store exports a valid blob that merges nothing. */
    s3 = rb_totp_new();
    RB_TOTP_CHECK(rb_totp_export_encrypted(s3, "hunter2", &empty_blob) == 1);
    RB_TOTP_CHECK(empty_blob != NULL);
    RB_TOTP_CHECK(rb_totp_import_encrypted(s2, empty_blob, "hunter2") == 0);
    RB_TOTP_CHECK(rb_totp_count(s2) == 2);

    free(blob);
    free(empty_blob);
    rb_totp_free(s);
    rb_totp_free(s2);
    rb_totp_free(s3);
    return 0;
}

/* ------------------------------- entry point ------------------------------- */

int rb_test_totp_all(void)
{
    int fails = 0;

    fails += totp_test_digests();
    fails += totp_test_hmac();
    fails += totp_test_base32();
    fails += totp_test_code();
    fails += totp_test_uri();
    fails += totp_test_store();
    fails += totp_test_crypt();
    if (fails == 0) {
        printf("totp: %d checks\n", g_totp_checks);
    }
    return fails;
}
