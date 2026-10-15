/*
 * Room Browser (desktop) - Windows notes window.
 *
 * The notes manager over the ACTIVE profile's notes.jsonl, built to the
 * same shape as the Downloads window: a plain window (not a DLGTEMPLATE),
 * one instance per app, owner-disabled modality, its own WM_COMMAND
 * handling in the window proc.  The store itself is the core's rb_notes;
 * this layer only converts at the UTF-8 boundary and decides when the
 * store reaches disk - on every mutation, and once more when the window
 * closes.
 *
 * Keyboard note, shared with the Downloads window: the aux windows get no
 * IsDialogMessageW pass because the message loop belongs to main.c, so Tab
 * does not walk the controls the way it does in the preferences window.
 * Every control is still reachable with the mouse, the search field
 * filters as it is typed, and Escape closes while the window itself holds
 * the focus.
 */
#ifndef RB_WIN_NOTES_H
#define RB_WIN_NOTES_H

#include "chrome.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the window, or focuses it when it is already open. */
void rb_show_notes(App *app);

/* Commits the note in the editor (if any) and writes the store to the
 * active profile's notes.jsonl.  The profile-switch protocol calls this
 * while the OLD profile is still active, right where rb_data_shutdown
 * flushes the other stores.  A no-op when nothing is open. */
void rb_notes_flush(App *app);

/* Drops the in-memory store and re-reads it from the NEW active profile's
 * notes.jsonl, rebuilding the open window.  The switch protocol calls
 * this once the active profile has changed, next to the step that
 * re-reads everything else the new profile owns. */
void rb_notes_reload(App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_NOTES_H */
