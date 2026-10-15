/*
 * frag_notes.c — checks for core/rb_notes, #included into tests/test_core.c.
 *
 * One translation unit, so everything here is static and prefixed notes_;
 * the single external symbol is rb_test_notes_all(), which runs every check
 * and returns the number of failures (0 = all green).  A failing
 * RB_NOTES_CHECK prints "FAIL notes: ..." and returns 1, which the entry
 * point propagates unchanged — a red run stops at the first broken
 * invariant instead of cascading.
 *
 * The save/load roundtrips go through "rb-notes-test.jsonl" in the cwd and
 * remove() it when done — the same discipline as test_core.c's
 * rb-test-tmp.txt.
 */

#include "core/rb_notes.h"

static int g_notes_checks = 0;

#define RB_NOTES_CHECK(cond)                                                 \
    do {                                                                     \
        g_notes_checks++;                                                    \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL notes: %s (%s:%d)\n", #cond,               \
                    __FILE__, __LINE__);                                     \
            return 1;                                                        \
        }                                                                    \
    } while (0)

#define NOTES_TMP "rb-notes-test.jsonl"

/* ---------------------- add / count / at and ordering -------------------- */

static int notes_add_count_at(void)
{
    rb_notes *n = rb_notes_new();
    char scratch[] = "scratch";
    const rb_note *row;
    long a, b, c, t1, t2;

    RB_NOTES_CHECK(n != NULL);
    RB_NOTES_CHECK(rb_notes_count(n) == 0);
    RB_NOTES_CHECK(rb_notes_at(n, 0) == NULL);
    RB_NOTES_CHECK(rb_notes_at(n, -1) == NULL);

    /* Total functions: a NULL store reads as empty, and add() hands back 0
     * rather than an id. */
    RB_NOTES_CHECK(rb_notes_count(NULL) == 0);
    RB_NOTES_CHECK(rb_notes_at(NULL, 0) == NULL);
    RB_NOTES_CHECK(rb_notes_add(NULL, "t", "b", 1) == 0);

    a = rb_notes_add(n, "alpha", "first body", 1000);
    b = rb_notes_add(n, "beta", "second body", 2000);
    c = rb_notes_add(n, "gamma", "third body", 3000);
    RB_NOTES_CHECK(a == 1 && b == 2 && c == 3); /* ids from 1, never reused */
    RB_NOTES_CHECK(rb_notes_count(n) == 3);

    /* Newest updated first: the third note is at index 0. */
    row = rb_notes_at(n, 0);
    RB_NOTES_CHECK(row != NULL && row->id == c);
    RB_NOTES_CHECK(row->created_at == 3000 && row->updated_at == 3000);
    RB_NOTES_CHECK(strcmp(row->title, "gamma") == 0);
    RB_NOTES_CHECK(strcmp(row->body, "third body") == 0);
    RB_NOTES_CHECK(rb_notes_at(n, 1)->id == b);
    RB_NOTES_CHECK(rb_notes_at(n, 2)->id == a);
    RB_NOTES_CHECK(rb_notes_at(n, 3) == NULL); /* out of range reads as NULL */

    /* A tie on updated_at: the higher id sorts first (id DESC). */
    t1 = rb_notes_add(n, "tie-a", "", 4000);
    t2 = rb_notes_add(n, "tie-b", "", 4000);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->id == t2);
    RB_NOTES_CHECK(rb_notes_at(n, 1)->id == t1);
    RB_NOTES_CHECK(rb_notes_at(n, 2)->id == c);

    /* A NULL title/body is stored as "", and the strings are copied, so
     * the caller can scribble over what it passed. */
    rb_notes_add(n, scratch, NULL, 5000);
    scratch[0] = 'X';
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 0)->title, "scratch") == 0);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 0)->body, "") == 0);
    rb_notes_add(n, NULL, "titleless", 6000);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 0)->title, "") == 0);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 0)->body, "titleless") == 0);

    rb_notes_free(n);
    rb_notes_free(NULL); /* double free must stay safe */
    return 0;
}

/* ------------------------- edit / get / remove --------------------------- */

static int notes_edit_get_remove(void)
{
    rb_notes *n = rb_notes_new();
    rb_note *slot;
    char *handed_over;
    long a, b, c;

    a = rb_notes_add(n, "a", "body-a", 100);
    b = rb_notes_add(n, "b", "body-b", 200);
    c = rb_notes_add(n, "c", "body-c", 300);
    RB_NOTES_CHECK(rb_notes_count(n) == 3);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->id == c); /* newest updated first */

    /* Editing stamps updated_at, which moves the row to the front — and
     * leaves created_at alone. */
    RB_NOTES_CHECK(rb_notes_edit(n, a, "a2", "body-a2", 400) == 1);
    RB_NOTES_CHECK(rb_notes_count(n) == 3);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->id == a);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->updated_at == 400);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->created_at == 100);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 0)->title, "a2") == 0);
    RB_NOTES_CHECK(rb_notes_at(n, 1)->id == c);
    RB_NOTES_CHECK(rb_notes_at(n, 2)->id == b);

    /* An unknown id is a no-op, not a crash. */
    RB_NOTES_CHECK(rb_notes_edit(n, 999, "x", "y", 500) == 0);
    RB_NOTES_CHECK(rb_notes_count(n) == 3);

    /* A NULL title/body reads as "" (and the row re-sorts by its new
     * updated_at). */
    RB_NOTES_CHECK(rb_notes_edit(n, b, NULL, NULL, 250) == 1);
    RB_NOTES_CHECK(rb_notes_at(n, 2)->id == b);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 2)->title, "") == 0);
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 2)->body, "") == 0);
    RB_NOTES_CHECK(rb_notes_at(n, 2)->updated_at == 250);

    /* get() is the mutable, open slot: hand over a malloc'd title — the
     * module frees it with the row. */
    slot = rb_notes_get(n, c);
    RB_NOTES_CHECK(slot != NULL && slot->id == c);
    handed_over = (char *)malloc(7);
    RB_NOTES_CHECK(handed_over != NULL);
    memcpy(handed_over, "edited", 7);
    free(slot->title);
    slot->title = handed_over;
    RB_NOTES_CHECK(strcmp(rb_notes_at(n, 1)->title, "edited") == 0);
    RB_NOTES_CHECK(rb_notes_get(n, 999) == NULL);
    RB_NOTES_CHECK(rb_notes_get(NULL, c) == NULL);

    /* remove() drops exactly the row asked for. */
    RB_NOTES_CHECK(rb_notes_remove(n, b) == 1);
    RB_NOTES_CHECK(rb_notes_count(n) == 2);
    RB_NOTES_CHECK(rb_notes_remove(n, b) == 0); /* already gone */
    RB_NOTES_CHECK(rb_notes_remove(NULL, b) == 0);
    RB_NOTES_CHECK(rb_notes_edit(NULL, b, "x", "y", 1) == 0);
    RB_NOTES_CHECK(rb_notes_at(n, 0)->id == a);
    RB_NOTES_CHECK(rb_notes_at(n, 1)->id == c);
    RB_NOTES_CHECK(rb_notes_at(n, 1)->updated_at == 300);

    rb_notes_free(n);
    return 0;
}

/* -------------------------------- search --------------------------------- */

static int notes_search(void)
{
    rb_notes *n = rb_notes_new();
    const rb_note *hits[8];
    long g, w, s;

    g = rb_notes_add(n, "Groceries", "milk and eggs", 300);
    w = rb_notes_add(n, "Work", "MILK delivery rota", 200);
    s = rb_notes_add(n, "shopping list", "bread and butter", 100);
    RB_NOTES_CHECK(rb_notes_count(n) == 3);

    /* A needle in the body only, newest first. */
    RB_NOTES_CHECK(rb_notes_search(n, "milk", hits, 8) == 2);
    RB_NOTES_CHECK(hits[0]->id == g && hits[1]->id == w);

    /* Case-insensitive for ASCII, like rb_history_search (SQLite's LIKE). */
    RB_NOTES_CHECK(rb_notes_search(n, "MILK", hits, 8) == 2);
    RB_NOTES_CHECK(hits[0]->id == g);

    /* A needle in the title only. */
    RB_NOTES_CHECK(rb_notes_search(n, "SHOPPING", hits, 8) == 1);
    RB_NOTES_CHECK(hits[0]->id == s);
    RB_NOTES_CHECK(rb_notes_search(n, "rota", hits, 8) == 1);
    RB_NOTES_CHECK(hits[0]->id == w);

    /* A miss writes nothing. */
    RB_NOTES_CHECK(rb_notes_search(n, "zebra", hits, 8) == 0);

    /* An empty or NULL needle matches everything, in display order. */
    RB_NOTES_CHECK(rb_notes_search(n, "", hits, 8) == 3);
    RB_NOTES_CHECK(hits[0]->id == g && hits[1]->id == w && hits[2]->id == s);
    RB_NOTES_CHECK(rb_notes_search(n, NULL, hits, 8) == 3);

    /* The pointers alias the store — hits[] and rb_notes_at() are one
     * array, so a caller can compare them. */
    RB_NOTES_CHECK(hits[0] == rb_notes_at(n, 0));

    /* max caps the write; the guards are total. */
    RB_NOTES_CHECK(rb_notes_search(n, "", hits, 2) == 2);
    RB_NOTES_CHECK(rb_notes_search(n, "milk", hits, 0) == 0);
    RB_NOTES_CHECK(rb_notes_search(n, "milk", NULL, 8) == 0);
    RB_NOTES_CHECK(rb_notes_search(NULL, "milk", hits, 8) == 0);

    rb_notes_free(n);
    return 0;
}

/* ----------------------------- save / load ------------------------------- */

static int notes_save_load(void)
{
    const char id4_prefix[] = "{\"id\":4,\"title\":\"fourth\"";
    const char id3_prefix[] = "{\"id\":3,";
    const char id2_line[] =
        "{\"id\":2,\"title\":\"second \\\"quoted\\\"\",\"body\":"
        "\"tab\\tback\\\\slash\",\"created_at\":200,\"updated_at\":200}\n";
    rb_notes *n = rb_notes_new();
    rb_notes *back;
    const rb_note *row;
    long fresh;
    FILE *f;
    char line[512];
    int lines = 0;
    int is_first = 1;
    int saw_two = 0;
    int saw_three = 0;

    rb_notes_add(n, "first", "line1\nline2", 100);                  /* id 1 */
    rb_notes_add(n, "second \"quoted\"", "tab\tback\\slash", 200);  /* id 2 */
    rb_notes_add(n, "third", "plain body", 300);                    /* id 3 */
    /* Drop the plain-bodied row: the file must then hold exactly the
     * multi-line body (id 1) and the escaped one (id 2), plus id 4. */
    RB_NOTES_CHECK(rb_notes_remove(n, 3) == 1);
    RB_NOTES_CHECK(rb_notes_add(n, "fourth", "newest", 400) == 4);
    RB_NOTES_CHECK(rb_notes_count(n) == 3);

    RB_NOTES_CHECK(rb_notes_save(n, NOTES_TMP) == 0);

    /* The file holds one JSON line per note in display order, with the
     * field order id, title, body, created_at, updated_at; the removed row
     * is gone, and the quotes, tab and backslash went out escaped. */
    f = fopen(NOTES_TMP, "rb");
    RB_NOTES_CHECK(f != NULL);
    while (fgets(line, (int)sizeof(line), f) != NULL) {
        if (is_first) {
            RB_NOTES_CHECK(strncmp(line, id4_prefix,
                                   sizeof(id4_prefix) - 1) == 0);
            is_first = 0;
        }
        if (strncmp(line, id2_line, sizeof(id2_line) - 1) == 0) {
            saw_two = 1;
        }
        if (strncmp(line, id3_prefix, sizeof(id3_prefix) - 1) == 0) {
            saw_three = 1;
        }
        lines++;
    }
    fclose(f);
    RB_NOTES_CHECK(lines == 3);
    RB_NOTES_CHECK(saw_two == 1);
    RB_NOTES_CHECK(saw_three == 0);

    back = rb_notes_new();
    rb_notes_add(back, "stale", "replaced by the load", 1);
    RB_NOTES_CHECK(rb_notes_load(back, NOTES_TMP) == 0);

    /* A load is a whole-store swap: the stale row is gone. */
    RB_NOTES_CHECK(rb_notes_count(back) == 3);

    row = rb_notes_at(back, 0);
    RB_NOTES_CHECK(row != NULL && row->id == 4);
    RB_NOTES_CHECK(row->created_at == 400 && row->updated_at == 400);
    RB_NOTES_CHECK(strcmp(row->title, "fourth") == 0);
    RB_NOTES_CHECK(strcmp(row->body, "newest") == 0);

    /* Ids and stamps survive the roundtrip, together with the escapes: the
     * quotes, tab and backslash in this body come back as themselves. */
    row = rb_notes_at(back, 1);
    RB_NOTES_CHECK(row->id == 2);
    RB_NOTES_CHECK(row->created_at == 200 && row->updated_at == 200);
    RB_NOTES_CHECK(strcmp(row->title, "second \"quoted\"") == 0);
    RB_NOTES_CHECK(strcmp(row->body, "tab\tback\\slash") == 0);

    /* So does a multi-line body: the "\n" on disk is a newline again. */
    row = rb_notes_at(back, 2);
    RB_NOTES_CHECK(row->id == 1);
    RB_NOTES_CHECK(row->created_at == 100 && row->updated_at == 100);
    RB_NOTES_CHECK(strcmp(row->title, "first") == 0);
    RB_NOTES_CHECK(strcmp(row->body, "line1\nline2") == 0);
    RB_NOTES_CHECK(rb_notes_get(back, 3) == NULL); /* removed row stays gone */

    /* The id counter advanced past the highest id in the file, so a new
     * add cannot collide with a restored row. */
    fresh = rb_notes_add(back, "after load", "", 500);
    RB_NOTES_CHECK(fresh == 5);
    RB_NOTES_CHECK(rb_notes_at(back, 0)->id == fresh);

    rb_notes_free(back);
    rb_notes_free(n);
    remove(NOTES_TMP);
    return 0;
}

/* ----------------------- load edges (missing/bad) ------------------------ */

static int notes_load_edges(void)
{
    rb_notes *n = rb_notes_new();
    const rb_note *row;
    FILE *f;
    int ok;

    /* A missing file is not an error: load returns 0 and the store is left
     * empty — and it REPLACES what was held, so a stale row cannot ride
     * along. */
    remove(NOTES_TMP);
    rb_notes_add(n, "stale", "gone", 1);
    RB_NOTES_CHECK(rb_notes_load(n, NOTES_TMP) == 0);
    RB_NOTES_CHECK(rb_notes_count(n) == 0);

    /* Half-written files happen: good rows survive, malformed lines (and
     * blank lines) are skipped whole. */
    f = fopen(NOTES_TMP, "wb");
    RB_NOTES_CHECK(f != NULL);
    ok = fputs("{\"id\":10,\"title\":\"good\",\"body\":\"row\","
               "\"created_at\":5,\"updated_at\":6}\n", f) != EOF;
    ok = ok && fputs("not json at all\n", f) != EOF;
    ok = ok && fputs("{\"id\":11,\"title\":\"no body key\"}\n", f) != EOF;
    /* The id-less row sits before the id-12 line, so the counter hands it
     * id 11 — an id the skipped line above never claimed. */
    ok = ok && fputs("{\"title\":\"no id\",\"body\":\"assigned\","
                     "\"created_at\":0,\"updated_at\":0}\n", f) != EOF;
    ok = ok && fputs("{\"id\":12,\"title\":\"esc \\\"q\\\"\",\"body\":"
                     "\"nl\\nhere\",\"created_at\":7,\"updated_at\":8}\n",
                     f) != EOF;
    ok = ok && fputs("{\"id\":13,\"title\":\"last\",\"body\":\"row\","
                     "\"created_at\":9,\"updated_at\":2}\n", f) != EOF;
    ok = ok && fputs("\n", f) != EOF;
    ok = ok && fclose(f) == 0;
    if (!ok) {
        fprintf(stderr, "FAIL notes: could not write %s (%s:%d)\n",
                NOTES_TMP, __FILE__, __LINE__);
        remove(NOTES_TMP);
        return 1;
    }

    RB_NOTES_CHECK(rb_notes_load(n, NOTES_TMP) == 0);

    /* Kept: ids 10 and 12 under their own ids, the id-less row (assigned
     * 11, which the counter had just reached), and id 13.  Skipped: the
     * non-JSON line, the row with no body, the blank line. */
    RB_NOTES_CHECK(rb_notes_count(n) == 4);

    /* Display order is rebuilt from the stamps in the file: 8, 6, 2, 0. */
    row = rb_notes_at(n, 0);
    RB_NOTES_CHECK(row != NULL && row->id == 12);
    RB_NOTES_CHECK(row->created_at == 7 && row->updated_at == 8);
    RB_NOTES_CHECK(strcmp(row->title, "esc \"q\"") == 0);
    RB_NOTES_CHECK(strcmp(row->body, "nl\nhere") == 0);

    row = rb_notes_at(n, 1);
    RB_NOTES_CHECK(row->id == 10);
    RB_NOTES_CHECK(row->created_at == 5 && row->updated_at == 6);

    RB_NOTES_CHECK(rb_notes_at(n, 2)->id == 13);
    RB_NOTES_CHECK(rb_notes_at(n, 3)->id == 11); /* the assigned one */
    RB_NOTES_CHECK(rb_notes_at(n, 3)->updated_at == 0);

    /* The counter advanced past every id the file carried — explicit or
     * assigned — so the next add cannot collide with any of them. */
    RB_NOTES_CHECK(rb_notes_add(n, "after", "", 99) == 14);

    rb_notes_free(n);
    remove(NOTES_TMP);
    return 0;
}

/* ------------------------------ entry point ------------------------------ */

int rb_test_notes_all(void)
{
    int fails = 0;

    if (fails == 0) {
        fails = notes_add_count_at();
    }
    if (fails == 0) {
        fails = notes_edit_get_remove();
    }
    if (fails == 0) {
        fails = notes_search();
    }
    if (fails == 0) {
        fails = notes_save_load();
    }
    if (fails == 0) {
        fails = notes_load_edges();
    }

    /* One line, and only on a green run — the FAIL notes above say it all
     * otherwise. */
    if (fails == 0) {
        printf("notes: %d checks\n", g_notes_checks);
    }
    return fails;
}
