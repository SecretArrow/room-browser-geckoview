/*
 * Room Browser (desktop) - Windows 2FA (TOTP) window.
 *
 * The 2FA manager over the ACTIVE profile's totp.jsonl, built to the same
 * shape as the Downloads window: a plain window, one instance, owner-
 * disabled modality, all WM_COMMAND handling in the window proc below.
 * Rows carry a live code, recomputed once a second on a WM_TIMER that
 * belongs to the window; the secrets themselves never leave the core
 * store except through rb_totp_export_encrypted.
 */
#ifndef RB_WIN_TOTP_H
#define RB_WIN_TOTP_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the window, or focuses it when it is already open. */
void rb_show_totp(App *app);

/* Writes the store to the active profile's totp.jsonl.  The profile-switch
 * protocol calls this while the OLD profile is still active, right where
 * rb_data_shutdown flushes the other stores.  A no-op when nothing is
 * open. */
void rb_totp_flush(App *app);

/* Drops the in-memory store and re-reads it from the NEW active profile's
 * totp.jsonl, rebuilding the open window.  The switch protocol calls
 * this once the active profile has changed, next to the step that
 * re-reads everything else the new profile owns. */
void rb_totp_reload(App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_TOTP_H */
