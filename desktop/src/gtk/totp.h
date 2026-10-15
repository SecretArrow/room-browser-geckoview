/*
 * Room Browser (desktop) - GTK 2FA (TOTP) window header.
 *
 * The 2FA manager over the ACTIVE profile's totp.jsonl, one window per
 * app, in the same shape as the Downloads window.  The store and the
 * RFC 6238 engine live in the core (core/rb_totp.h); the secrets never
 * leave it except through rb_totp_export_encrypted.
 */
#ifndef RB_GTK_TOTP_H
#define RB_GTK_TOTP_H

struct App;

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the 2FA window, or presents it when it is already open.
 * Returns 0 once the window is up, -1 when it could not be built. */
int rb_open_totp_window(struct App *app);

/* Writes the store to the active profile's totp.jsonl.  The profile-
 * switch protocol calls this while the OLD profile is still active,
 * right where rb_data_shutdown flushes the other stores.  A no-op when
 * the window is closed. */
void rb_totp_flush(struct App *app);

/* Drops the in-memory store and re-reads it from the NEW active
 * profile's totp.jsonl, refreshing the open window.  The switch protocol
 * calls this once the active profile has changed, next to the step that
 * re-reads everything else the new profile owns.  A no-op when the
 * window is closed. */
void rb_totp_reload(struct App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_GTK_TOTP_H */
