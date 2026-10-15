/*
 * rb_notes.c — notes for Room Browser core.
 * Pure C11; only the C standard library is used.
 *
 * INVARIANT: `items[0 .. count-1]` are in display order — updated_at DESC,
 * then id DESC (see rb_note_before).  Every mutation below restores it,
 * which is what keeps rb_notes_at() an array read rather than a sort per
 * call.
 *
 * The JSON-lines reader/writer uses the shared primitives in rb_json.h, so
 * the escaping and unescaping rules are the same ones every other store in
 * the browser applies.  The write side cannot borrow the read side's fixed
 * line buffer: a body is unbounded, so each line is assembled by
 * concatenation into a growable buffer instead.
 */

#include "rb_notes.h"

#include "rb_json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Longest line the reader will take from a notes file; a longer line is
 * skipped whole rather than parsed in pieces.  The write side has no such
 * cap — bodies are unbounded, and the line buffer grows instead. */
#define RB_NOTES_LINE 65536

struct rb_notes {
    rb_note *items; /* display order */
    int count;
    int cap;
    long next_id;   /* ids from 1, never reused */
};

/* ------------------------------- helpers -------------------------------- */

static void rb_note_release(rb_note *row)
{
    free(row->title);
    free(row->body);
    memset(row, 0, sizeof(*row));
}

static void rb_notes_grow(rb_notes *n)
{
    int ncap;
    rb_note *grown;

    if (n->count < n->cap) {
        return;
    }
    ncap = (n->cap > 0) ? n->cap * 2 : 8;
    grown = (rb_note *)realloc(n->items, (size_t)ncap * sizeof(rb_note));
    if (grown == NULL) {
        fprintf(stderr, "rb_notes: out of memory\n");
        exit(1);
    }
    n->items = grown;
    n->cap = ncap;
}

/* 1 when `a` sorts before `b`: the newest update first, with the id as the
 * final tie-break.  Two notes stamped in the same millisecond would
 * otherwise sit in whatever order the storage happened to hold, and an
 * order that depends on storage layout is not something a UI or a test can
 * rely on. */
static int rb_note_before(const rb_note *a, const rb_note *b)
{
    if (a->updated_at != b->updated_at) {
        return a->updated_at > b->updated_at;
    }
    return a->id > b->id;
}

/* Stable insertion sort.  Note lists are short and nearly sorted between
 * mutations, which is the case this is built for. */
static void rb_notes_sort(rb_notes *n)
{
    int i;

    for (i = 1; i < n->count; i++) {
        rb_note key = n->items[i];
        int j = i - 1;

        while (j >= 0 && rb_note_before(&key, &n->items[j])) {
            n->items[j + 1] = n->items[j];
            j--;
        }
        n->items[j + 1] = key;
    }
}

static int rb_notes_index_of_id(const rb_notes *n, long id)
{
    int i;

    if (n == NULL) {
        return -1;
    }
    for (i = 0; i < n->count; i++) {
        if (n->items[i].id == id) {
            return i;
        }
    }
    return -1;
}

/* Drops the row at `i`, keeping the display order of everything else. */
static void rb_notes_drop(rb_notes *n, int i)
{
    rb_note_release(&n->items[i]);
    if (i + 1 < n->count) {
        memmove(&n->items[i], &n->items[i + 1],
                (size_t)(n->count - i - 1) * sizeof(rb_note));
    }
    n->count--;
}

/* Frees every row but keeps the store itself — load() starts from here,
 * because a load is a whole-store swap. */
static void rb_notes_clear(rb_notes *n)
{
    int i;

    for (i = 0; i < n->count; i++) {
        rb_note_release(&n->items[i]);
    }
    n->count = 0;
}

/* ASCII lower case, and nothing else: the search is deliberately the same
 * ASCII-only rule as rb_history_search (SQLite's LIKE), so 'A' matches 'a'
 * and no Unicode folding happens at all. */
static char rb_notes_lower(char c)
{
    return (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
}

/* SQLite's LIKE, for the ASCII subset: a case-insensitive substring test. */
static int rb_notes_contains_ci(const char *hay, const char *needle)
{
    size_t nlen = strlen(needle);
    size_t i;

    if (nlen == 0) {
        return 1;
    }
    for (i = 0; hay[i] != '\0'; i++) {
        size_t k = 0;
        while (k < nlen && hay[i + k] != '\0' &&
               rb_notes_lower(hay[i + k]) == rb_notes_lower(needle[k])) {
            k++;
        }
        if (k == nlen) {
            return 1;
        }
    }
    return 0;
}

/* ------------------------------- parsing -------------------------------- */

/* Reads a string field; NULL when the key is absent or not a string. */
static char *rb_notes_field_str(const char *line, const char *key)
{
    size_t pos;
    char *out = NULL;

    if (!rb_json_find_key(line, key, &pos)) {
        return NULL;
    }
    if (line[pos] != '"' || !rb_json_parse_string(line, &pos, &out)) {
        return NULL; /* null, a number, or malformed: not a string */
    }
    return out;
}

static long long rb_notes_field_num(const char *line, const char *key,
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

/* ------------------------------ public API ------------------------------ */

rb_notes *rb_notes_new(void)
{
    rb_notes *n = (rb_notes *)calloc(1, sizeof(*n));

    if (n == NULL) {
        fprintf(stderr, "rb_notes: out of memory\n");
        exit(1);
    }
    n->next_id = 1;
    return n;
}

void rb_notes_free(rb_notes *n)
{
    int i;

    if (n == NULL) {
        return;
    }
    for (i = 0; i < n->count; i++) {
        rb_note_release(&n->items[i]);
    }
    free(n->items);
    free(n);
}

int rb_notes_count(const rb_notes *n)
{
    return (n != NULL) ? n->count : 0;
}

const rb_note *rb_notes_at(const rb_notes *n, int index)
{
    if (n == NULL || index < 0 || index >= n->count) {
        return NULL;
    }
    return &n->items[index];
}

long rb_notes_add(rb_notes *n, const char *title, const char *body,
                  long long now_ms)
{
    rb_note row;
    long id;

    if (n == NULL) {
        return 0;
    }
    memset(&row, 0, sizeof(row));
    id = n->next_id++;
    row.id = id;
    row.title = rb_json_strdup(title); /* a NULL reads as "" */
    row.body = rb_json_strdup(body);
    row.created_at = now_ms;
    row.updated_at = now_ms;

    rb_notes_grow(n);
    n->items[n->count++] = row;
    /* A fresh note's updated_at is the newest stamp, so the row can only
     * need to travel to the front — but equal stamps happen (two notes in
     * the same millisecond), so sort rather than insert. */
    rb_notes_sort(n);
    return id;
}

rb_note *rb_notes_get(rb_notes *n, long id)
{
    int i = rb_notes_index_of_id(n, id);

    return (i >= 0) ? &n->items[i] : NULL;
}

int rb_notes_edit(rb_notes *n, long id, const char *title, const char *body,
                  long long now_ms)
{
    int i = rb_notes_index_of_id(n, id);
    rb_note *row;

    if (i < 0) {
        return 0;
    }
    row = &n->items[i];
    free(row->title);
    row->title = rb_json_strdup(title);
    free(row->body);
    row->body = rb_json_strdup(body);
    /* updated_at is the primary sort key, so this can move the row — the
     * note a user just edited is the one the screen shows first. */
    row->updated_at = now_ms;
    rb_notes_sort(n);
    return 1;
}

int rb_notes_remove(rb_notes *n, long id)
{
    int i = rb_notes_index_of_id(n, id);

    if (i < 0) {
        return 0;
    }
    rb_notes_drop(n, i);
    return 1;
}

int rb_notes_search(const rb_notes *n, const char *needle,
                    const rb_note **out, int max)
{
    int filled = 0;
    int i;

    if (n == NULL || out == NULL || max <= 0) {
        return 0;
    }
    for (i = 0; i < n->count && filled < max; i++) {
        /* The items are already newest-first, so filling in index order is
         * the search order too.  A NULL/empty needle matches every row. */
        int hit = (needle == NULL || needle[0] == '\0' ||
                   rb_notes_contains_ci(n->items[i].title, needle) ||
                   rb_notes_contains_ci(n->items[i].body, needle));

        if (hit) {
            out[filled++] = &n->items[i];
        }
    }
    return filled;
}

/* ------------------------------ persistence ----------------------------- */

/* One JSON line under assembly. */
struct rb_notes_line {
    char *data;
    size_t len;
    size_t cap;
};

/* Doubles the buffer until `extra` more bytes fit.  A note's body is
 * unbounded, so the write side grows instead of capping, and dies on
 * allocation failure like every other store. */
static void rb_line_reserve(struct rb_notes_line *b, size_t extra)
{
    size_t need = b->len + extra + 1;
    size_t ncap = (b->cap > 0) ? b->cap : 128;
    char *grown;

    if (need <= b->cap) {
        return;
    }
    while (ncap < need) {
        ncap *= 2;
    }
    grown = (char *)realloc(b->data, ncap);
    if (grown == NULL) {
        fprintf(stderr, "rb_notes: out of memory\n");
        exit(1);
    }
    b->data = grown;
    b->cap = ncap;
}

static void rb_line_puts(struct rb_notes_line *b, const char *s)
{
    size_t len = strlen(s);

    rb_line_reserve(b, len);
    memcpy(b->data + b->len, s, len);
    b->len += len;
    b->data[b->len] = '\0';
}

/* One JSON string value: quotes around rb_json_escape'd content, so a
 * body's newlines go to disk as "\n" escapes and one row stays one line. */
static void rb_line_put_str(struct rb_notes_line *b, const char *value)
{
    char *escaped = rb_json_escape(value);

    rb_line_puts(b, "\"");
    rb_line_puts(b, escaped);
    rb_line_puts(b, "\"");
    free(escaped);
}

static void rb_line_put_num(struct rb_notes_line *b, long long v)
{
    char num[32];

    (void)snprintf(num, sizeof(num), "%lld", v);
    rb_line_puts(b, num);
}

/* Assembles and writes one row in the file's field order:
 * id, title, body, created_at, updated_at. */
static int rb_notes_write_row(FILE *f, const rb_note *row)
{
    struct rb_notes_line line;
    int rc = 0;

    memset(&line, 0, sizeof(line));
    rb_line_puts(&line, "{\"id\":");
    rb_line_put_num(&line, (long long)row->id);
    rb_line_puts(&line, ",\"title\":");
    rb_line_put_str(&line, row->title);
    rb_line_puts(&line, ",\"body\":");
    rb_line_put_str(&line, row->body);
    rb_line_puts(&line, ",\"created_at\":");
    rb_line_put_num(&line, row->created_at);
    rb_line_puts(&line, ",\"updated_at\":");
    rb_line_put_num(&line, row->updated_at);
    rb_line_puts(&line, "}\n");
    if (fputs(line.data, f) == EOF) {
        rc = -1;
    }
    free(line.data);
    return rc;
}

int rb_notes_save(const rb_notes *n, const char *path)
{
    FILE *f;
    int i;
    int rc = 0;

    if (n == NULL || path == NULL) {
        return -1;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    for (i = 0; i < n->count && rc == 0; i++) {
        rc = rb_notes_write_row(f, &n->items[i]);
    }
    if (rc == 0 && ferror(f)) {
        rc = -1;
    }
    if (fclose(f) != 0) {
        rc = -1;
    }
    return rc;
}

int rb_notes_load(rb_notes *n, const char *path)
{
    char buf[RB_NOTES_LINE];
    FILE *f;
    int rc = 0;

    if (n == NULL || path == NULL) {
        return -1;
    }
    /* A load is a whole-store swap, not an append like rb_bookmarks_load:
     * the notes file is the one truth for the profile, so rows from an
     * older file must not survive it — not even when the file is missing. */
    rb_notes_clear(n);

    f = fopen(path, "rb");
    if (f == NULL) {
        return 0; /* missing file is fine: an empty store */
    }
    while (fgets(buf, (int)sizeof(buf), f) != NULL) {
        size_t len = strlen(buf);
        char *title;
        char *body;
        rb_note row;

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
        title = rb_notes_field_str(buf, "title");
        body = rb_notes_field_str(buf, "body");
        if (title == NULL || body == NULL) {
            /* A note row always carries both strings; without them the
             * line is not one of ours, or is half-written. */
            free(title);
            free(body);
            continue;
        }

        memset(&row, 0, sizeof(row));
        row.id = (long)rb_notes_field_num(buf, "id", 0);
        row.title = title;
        row.body = body;
        row.created_at = rb_notes_field_num(buf, "created_at", 0);
        row.updated_at = rb_notes_field_num(buf, "updated_at", 0);

        rb_notes_grow(n);
        if (row.id <= 0) {
            row.id = n->next_id; /* a row that predates ids */
        }
        if (row.id >= n->next_id) {
            n->next_id = row.id + 1;
        }
        n->items[n->count++] = row;
    }
    if (ferror(f)) {
        rc = -1;
    }
    fclose(f);

    /* The file is written newest-first, but a hand-edited or older file
     * may not be — restore the display order either way. */
    rb_notes_sort(n);
    return rc;
}
