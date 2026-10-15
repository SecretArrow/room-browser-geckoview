/*
 * rb_notes.h — notes for Room Browser core.
 *
 * Ports NoteEntity and NoteDao.  A note is a row with an id, a title and a
 * body, and the body is free text — not a bounded field — because that is
 * what the Android edition stores and what its screens render.  A body may
 * hold newlines, which is why persistence escapes it as JSON instead of
 * treating the file as one record per raw line of text.
 *
 * DISPLAY ORDER IS AN INVARIANT, like rb_bookmarks' and rb_tabs':
 * `items[0 .. count-1]` are always in the DAO's order (NoteDao.observeAll)
 *
 *     ORDER BY updated_at DESC, id DESC
 *
 * so rb_notes_at() is an array read rather than a sort per call.  What that
 * order means for a user: the note they edited last is the one they see
 * first.  The id is the final tie-break so equal stamps (two notes saved in
 * the same millisecond) still land in one deterministic order — an order
 * that depends on storage layout is not something a UI or a test can rely
 * on.
 *
 * The clock is passed in rather than read here, so created_at and
 * updated_at are testable.  All strings are UTF-8.  Persistence is JSON
 * lines, one row per note:
 *   {"id":1,"title":"...","body":"...","created_at":0,"updated_at":0}
 * rb_json_escape turns a body's newlines into "\n" escapes, so one row is
 * always exactly one physical line and a multi-line note survives a round
 * trip through the file.
 */

#ifndef RB_NOTES_H
#define RB_NOTES_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    long id;
    char *title;          /* module-owned; replace only with a malloc'd string */
    char *body;           /* module-owned free text; may hold newlines */
    long long created_at; /* ms since the epoch */
    long long updated_at; /* ms since the epoch; the primary sort key */
} rb_note;

/* Opaque, ordered note list for one profile. */
typedef struct rb_notes rb_notes;

rb_notes *rb_notes_new(void);
void      rb_notes_free(rb_notes *n);

/* How many notes are held. */
int       rb_notes_count(const rb_notes *n);

/* The index'th note, newest-updated first; NULL when out of range. */
const rb_note *rb_notes_at(const rb_notes *n, int index);

/* NoteDao.insert: appends with a fresh id and stamps created_at and
 * updated_at with now_ms.  The strings are copied (a NULL title/body is
 * stored as ""), so the caller keeps what it passed.  Returns the new id,
 * or 0 when `n` is NULL. */
long      rb_notes_add(rb_notes *n, const char *title, const char *body,
                       long long now_ms);

/* Mutable slot for the id, or NULL when there is no such note.
 *
 * This is open storage: the module owns title and body and frees them with
 * the row, so a caller that replaces one must hand over a malloc'd string
 * (or NULL, which reads as "").  Writing here does NOT restore the display
 * order — updated_at is the sort key, so anything that touches it goes
 * through rb_notes_edit() instead, which re-sorts as well. */
rb_note  *rb_notes_get(rb_notes *n, long id);

/* NoteDao.update: replaces title and body and stamps updated_at = now_ms,
 * which can move the row to the front — the note a user just edited is the
 * one the screen shows first.  A NULL title/body reads as "".  Returns 1
 * when the id existed. */
int       rb_notes_edit(rb_notes *n, long id, const char *title,
                        const char *body, long long now_ms);

/* NoteDao.delete.  Returns 1 when the note was there. */
int       rb_notes_remove(rb_notes *n, long id);

/* NoteDao.search: case-insensitive ASCII substring match over title AND
 * body — the same rule as rb_history_search (SQLite's LIKE, not a
 * Unicode-aware search).  Newest first, filling `out` with up to `max`
 * pointers into the store; the pointers alias the store, so they die at
 * the next mutation — copy an id out before editing.  A NULL/empty needle
 * matches everything, which is what the screen shows before the user
 * types.  Returns how many were written. */
int       rb_notes_search(const rb_notes *n, const char *needle,
                          const rb_note **out, int max);

/* Rewrites the whole file, one JSON line per note in display order.
 * 0 ok, -1 on I/O error. */
int       rb_notes_save(const rb_notes *n, const char *path);

/* Replaces the store's contents from a JSON-lines file.  A load is a
 * whole-store swap, not an append: the notes file is the one truth for the
 * profile, so rows must not survive it.  A missing file is fine — the
 * store is left empty and 0 is returned, because a first run simply has no
 * notes file yet.  Malformed lines are skipped, not fatal: a half-written
 * last row from a crash must not lose the rows before it.  Ids are
 * preserved, and the id counter advances past the highest id seen so a
 * later rb_notes_add cannot collide with a restored row.  0 ok, -1 on I/O
 * error. */
int       rb_notes_load(rb_notes *n, const char *path);

#ifdef __cplusplus
}
#endif

#endif /* RB_NOTES_H */
