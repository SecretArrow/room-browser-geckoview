/*
 * Room Browser (desktop) - GTK notes window header.
 *
 * The notes manager over the ACTIVE profile's notes.jsonl, one window
 * per app, in the same shape as the Downloads window.  The store itself
 * lives in the core (rb_notes in core/rb_notes.h); this layer only
 * decides when the store reaches disk.
 */
#ifndef RB_GTK_NOTES_H
#define RB_GTK_NOTES_H

struct App;

#ifdef __cplusplus
extern "C" {
#endif

/* Opens the notes window, or presents it when it is already open.
 * Returns 0 once the window is up, -1 when it could not be built. */
int rb_open_notes_window(struct App *app);

/* Commits the note sitting in the editor (if any) and writes the store
 * to the active profile's notes.jsonl.  The profile-switch protocol
 * calls this while the OLD profile is still active, right where
 * rb_data_shutdown flushes the other stores.  A no-op when the window
 * is closed. */
void rb_notes_flush(struct App *app);

/* Drops the in-memory store and re-reads it from the NEW active
 * profile's notes.jsonl, refreshing the open window.  The switch
 * protocol calls this once the active profile has changed, next to the
 * step that re-reads everything else the new profile owns.  A no-op
 * when the window is closed. */
void rb_notes_reload(struct App *app);

#ifdef __cplusplus
}
#endif

#endif /* RB_GTK_NOTES_H */
