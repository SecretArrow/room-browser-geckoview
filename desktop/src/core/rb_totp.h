/*
 * rb_totp.h — TOTP (RFC 6238) engine and account store for Room Browser core.
 *
 * Pure C11, libc only.  The module carries its own SHA-1/SHA-256 (FIPS
 * 180-4), HMAC (RFC 2104/6231), PBKDF2 (RFC 8018) and Base32 (RFC 4648)
 * because the core deliberately links no crypto library: two digests cover
 * everything the browser needs — the codes themselves and the encrypted
 * backup — and two hand-rolled primitives used this narrowly are easier to
 * audit than a dependency is to pin.
 *
 * Every function is total: NULL/0 inputs are handled, malformed input yields
 * 0/-1, never a crash.  The clock is passed in rather than read here, so
 * codes are testable — the same reason rb_bookmarks takes now_ms.
 *
 * Accounts persist as JSON lines (rb_json.h escaping, one object per line,
 * field order id, issuer, label, secret, algo, digits, period, created_at)
 * with the secret Base32, which is exactly what an otpauth:// URI carries,
 * so a stored row and a scanned QR code agree without conversion.
 *
 * The encrypted export is encrypt-then-MAC over those same primitives:
 * PBKDF2-HMAC-SHA256 derives an encryption and a MAC key, an HMAC-SHA256
 * keystream XORs the plaintext, and the tag covers the ciphertext — so a
 * wrong passphrase or a tampered blob is rejected before a single byte is
 * decrypted, let alone merged.
 */

#ifndef RB_TOTP_H
#define RB_TOTP_H

#include <sys/types.h> /* size_t */

#ifdef __cplusplus
extern "C" {
#endif

/* Hash choices for rb_totp_code() and the store's algo field. */
#define RB_TOTP_SHA1   0
#define RB_TOTP_SHA256 1

#define RB_TOTP_DEFAULT_DIGITS 6
#define RB_TOTP_DEFAULT_PERIOD 30

/* ---------------------------- crypto primitives -------------------------- */

/* FIPS 180-4 digests.  data may be NULL when len is 0; out is written in
 * full (20 / 32 bytes). */
void rb_sha1(const unsigned char *data, size_t len, unsigned char out[20]);
void rb_sha256(const unsigned char *data, size_t len, unsigned char out[32]);

/* RFC 2104 HMAC (RFC 6231 for TOTP); a key longer than the 64-byte block is
 * hashed first, exactly as both RFCs require. */
void rb_hmac_sha1(const unsigned char *key, size_t key_len,
                  const unsigned char *data, size_t data_len,
                  unsigned char out[20]);
void rb_hmac_sha256(const unsigned char *key, size_t key_len,
                    const unsigned char *data, size_t data_len,
                    unsigned char out[32]);

/* RFC 4648 Base32.  decode: skips spaces, tabs and '-', accepts upper and
 * lower case, ignores trailing '=' padding; returns the byte count written
 * or -1 (invalid character, or output too small).  out may be NULL to just
 * measure (returns the length, or -1). */
int  rb_base32_decode(const char *in, unsigned char *out, size_t out_cap);

/* encode without padding; out needs (len*8+4)/5 + 1 bytes; returns 1 on
 * success, 0 when out_cap is too small. */
int  rb_base32_encode(const unsigned char *in, size_t len, char *out,
                      size_t out_cap);

/* ------------------------------- the store ------------------------------- */

/* One TOTP account.  issuer/label/secret are owned by the store and stay
 * valid only until the next mutation or rb_totp_free(). */
typedef struct {
    long id;
    char *issuer;
    char *label;
    unsigned char *secret;
    size_t secret_len;
    int algo;
    int digits;
    int period;
    long long created_at; /* ms since the epoch, like every other store */
} rb_totp_account;

/* Opaque, ordered account store; accounts are kept in creation order
 * (id ASC), so rb_totp_at() is an array read, not a sort per call. */
typedef struct rb_totp_store rb_totp_store;

rb_totp_store *rb_totp_new(void);
void           rb_totp_free(rb_totp_store *s);
int            rb_totp_count(const rb_totp_store *s);

/* Accounts in creation order (id ASC).  NULL out of range. */
const rb_totp_account *rb_totp_at(const rb_totp_store *s, int index);

/* Adds a copy of the secret; empty (or NULL) issuer/label are stored as "".
 * Returns the new id, or 0 when s is NULL or the secret is empty. */
long rb_totp_add(rb_totp_store *s, const char *issuer, const char *label,
                 const unsigned char *secret, size_t secret_len, int algo,
                 int digits, int period, long long now_ms);

/* The account with this id (mutable, like rb_bookmarks_by_id), or NULL. */
rb_totp_account *rb_totp_get(rb_totp_store *s, long id);

/* Removes the account.  Returns 1 when it was there.  The id counter is
 * not rewound: a removed id is never reused. */
int  rb_totp_remove(rb_totp_store *s, long id);

/* JSON lines, one object per account, field order
 *   id, issuer, label, secret, algo, digits, period, created_at
 * with the secret Base32.  0 ok, -1 on I/O error. */
int  rb_totp_save(const rb_totp_store *s, const char *path);

/* Appends every valid row of a JSON-lines file.  A missing file is fine
 * (0, state kept); malformed lines are skipped; the id counter advances
 * past any id in the file. */
int  rb_totp_load(rb_totp_store *s, const char *path);

/* --------------------------------- TOTP ---------------------------------- */

/* Writes the OTP as digits ASCII + NUL into out (size digits+1).  Returns 1,
 * or 0 on bad args (NULL/empty secret, digits outside 1..10, period < 1,
 * unknown algo, unix_now < 0).  unix_now is seconds since the epoch; the
 * counter is unix_now / period as an 8-byte big-endian message and the code
 * is the standard RFC 4226 dynamic truncation.  algo selects SHA-1 (0) or
 * SHA-256 (1). */
int  rb_totp_code(const unsigned char *secret, size_t secret_len, int algo,
                  int digits, int period, long long unix_now, char *out);

/* Seconds until the current window ends (period - unix_now % period), never
 * 0: in [1, period].  0 when period < 1. */
int  rb_totp_seconds_remaining(int period, long long unix_now);

/* ----------------------------- otpauth:// URIs ---------------------------- */

/* What a parsed otpauth:// URI yields; the members are malloc'd and owned
 * by the caller (hand them to rb_totp_pending_free). */
typedef struct {
    char *issuer;
    char *label;
    unsigned char *secret;
    size_t secret_len;
    int algo;
    int digits;
    int period;
} rb_totp_pending;

/* Frees the members and zeroes the struct; safe on an all-NULL struct, so
 * a failed parse can simply be handed over as-is. */
void rb_totp_pending_free(rb_totp_pending *p);

/* Parses otpauth://totp/<label>?secret=..&issuer=..&algorithm=..&digits=..&period=..
 * The label may be "issuer:account" or percent-encoded; when the issuer
 * query parameter is missing it falls back to the label prefix before ':'.
 * Percent-decoding (%XX always) is applied to the label path and the
 * parameter values.  A non-empty secret that Base32-decodes is required.
 * algorithm is case-insensitive SHA1/SHA-1/SHA256/SHA-256 (default SHA1);
 * digits defaults to 6 and accepts 6 or 8 (other values rejected); period
 * defaults to 30 and must be 1..86400.  Returns 1 and fills p (malloc'd
 * members) on success, 0 on failure — p then holds no allocations needing
 * free, and rb_totp_pending_free stays safe to call. */
int  rb_totp_parse_uri(const char *uri, rb_totp_pending *p);

/* --------------------------- encrypted export ----------------------------- */

/* Export format, one line:
 *   "RB2FA1 " + base64( salt[16] || be32(iters=120000) || nonce[16] || ct || tag[32] )
 * key = PBKDF2-HMAC-SHA256(passphrase, salt, iters, 64 bytes);
 * key_enc = key[0..31], key_mac = key[32..63].
 * ct = plaintext XOR keystream, keystream block i =
 *      HMAC-SHA256(key_enc, nonce || be64(i))   (counter from 0)
 * tag = HMAC-SHA256(key_mac, "RB2FA1" || nonce || ct)   (encrypt-then-MAC)
 * The plaintext is the store's JSON lines exactly as rb_totp_save writes
 * them.  On success stores a malloc'd one-line string (no trailing
 * newline) in *out_blob and returns 1; returns 0 on error or an empty
 * passphrase. */
int  rb_totp_export_encrypted(const rb_totp_store *s, const char *passphrase,
                              char **out_blob);

/* Verifies the tag BEFORE decrypting (wrong passphrase or tampering: -1,
 * nothing merged).  Parses the plaintext lines and merges them, skipping
 * accounts whose (issuer, label) pair already exists.  Returns the number
 * merged, -1 on a bad blob or passphrase, 0 when nothing was new. */
int  rb_totp_import_encrypted(rb_totp_store *s, const char *blob,
                              const char *passphrase);

#ifdef __cplusplus
}
#endif

#endif /* RB_TOTP_H */
