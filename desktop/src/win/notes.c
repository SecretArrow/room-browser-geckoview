/*
 * Room Browser (desktop) - Windows notes window.
 *
 * The notes manager over the ACTIVE profile's notes.jsonl.  The store is
 * the core's rb_notes, owned by the window while it is open: loaded when
 * the window opens, written through on every mutation, and handed back to
 * the profile switch by rb_notes_flush()/rb_notes_reload() at the two
 * points the switch protocol already uses for the other per-profile
 * state.  A window that is not open owns nothing, which is why both of
 * those are no-ops then.
 *
 * It is a plain window rather than a DLGTEMPLATE one, exactly like the
 * Downloads window, and it is modal the same way: by disabling its owner
 * instead of by a system modal loop.
 *
 * Keyboard note, shared with the Downloads window: the aux windows get no
 * IsDialogMessageW pass because the message loop belongs to main.c, so
 * Tab does not walk the controls and Enter does not press a default
 * button.  Everything is reachable with the mouse, the search field
 * filters as it is typed, and Escape closes while the window itself holds
 * the focus.
 */
#include "notes.h"

/* rb_core.h does not pull the notes store in, so this layer names it
 * directly - the same include path the chrome header uses for the rest of
 * the core. */
#include "core/rb_notes.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

/* Window metrics, fixed like the Downloads window's (it does not resize). */
#define RB_NT_WIN_W    780
#define RB_NT_WIN_H    480
#define RB_NT_MARGIN   12
#define RB_NT_BTNH     26
#define RB_NT_GAP      6
#define RB_NT_LIST_W   280

/* Control ids.  They are window-local: the main window's command ranges
 * (RB_ID_BM_FIRST and up) are dispatched by chrome.c's proc, never by this
 * one, which is the same reason the Downloads window may sit on 4001. */
#define RB_NT_SEARCH 4201
#define RB_NT_LIST   4202
#define RB_NT_TITLE  4203
#define RB_NT_BODY   4204
#define RB_NT_NEW    4205
#define RB_NT_DUP    4206
#define RB_NT_DEL    4207
#define RB_NT_SAVE   4208
#define RB_NT_CLOSE  4209

typedef struct {
    App *app;
    HWND dlg, search, list, title, body;
    HWND b_new, b_dup, b_del, b_save;
    HFONT fnt;

    rb_notes *store;   /* the active profile's notes, loaded with the window */
    char *path;        /* notes.jsonl of the profile the window opened on */
    long *ids;         /* list row -> note id */
    int n_ids;
    long current_id;   /* the note in the editor; 0 = none */
    int dirty;         /* the editor holds text not yet in the store */
    int suppress;      /* set while the code fills the controls itself */
} RbNotes;

static RbNotes *g_notes = NULL;

/* ------------------------------------------------------------------ */
/* Small helpers, the same shape the Downloads window's carry */

static wchar_t *rb_nt_wide(const char *s)
{
    return rb_utf8_to_wide(s != NULL ? s : "");
}

/* A control's text as UTF-8.  Never NULL: an empty field reads as "", the
 * same way the core stores a missing title or body. */
static char *rb_nt_get_text(HWND c)
{
    int n;
    wchar_t *w;
    char *u8;

    if (c == NULL) return rb_strdup("");
    n = GetWindowTextLengthW(c);
    if (n <= 0) return rb_strdup("");
    w = (wchar_t *)malloc(((size_t)n + 1) * sizeof(wchar_t));
    if (w == NULL) return rb_strdup("");
    GetWindowTextW(c, w, n + 1);
    u8 = rb_wide_to_utf8(w);
    free(w);
    return (u8 != NULL) ? u8 : rb_strdup("");
}

static void rb_nt_set_text(HWND c, const char *s)
{
    wchar_t *w;

    if (c == NULL) return;
    w = rb_nt_wide(s);
    SendMessageW(c, WM_SETTEXT, 0, (LPARAM)((w != NULL) ? w : L""));
    free(w);
}

static HWND rb_nt_ctl(RbNotes *d, const wchar_t *cls, const wchar_t *text,
                      DWORD style, int x, int y, int w, int h, int id)
{
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style,
                             x, y, w, h, d->dlg, (HMENU)(INT_PTR)id,
                             d->app->hinst, NULL);
    if (c != NULL && d->fnt != NULL) {
        SendMessageW(c, WM_SETFONT, (WPARAM)d->fnt, TRUE);
    }
    return c;
}

/* ------------------------------------------------------------------ */
/* The store and its file */

/* <active profile's settings dir>/notes.jsonl, built the way chrome.c
 * builds history.jsonl - rb_paths_join over a directory - but over the
 * per-profile settings directory rb_profile.h documents as the home of
 * profile-owned files ("settings.txt, history.jsonl").  The directory is
 * made here too: a first save must not depend on the profile having been
 * created with its sub-directories intact. */
static char *rb_nt_notes_path(App *app)
{
    const rb_profile *p;
    const char *pid;
    char *data_dir;
    char *dir;
    char *path;

    p = rb_active_profile(app);
    pid = (p != NULL && p->id != NULL) ? p->id : "";
    data_dir = rb_paths_data_dir();
    if (data_dir == NULL) return NULL;
    dir = rb_profile_subdir(data_dir, pid, RB_PROFILE_DIR_SETTINGS);
    rb_paths_free(data_dir);
    if (dir == NULL) return NULL;
    rb_mkdirs_utf8(dir);
    path = rb_paths_join(dir, "notes.jsonl");
    free(dir);
    return path;
}

static void rb_nt_save(RbNotes *d)
{
    if (d->store != NULL && d->path != NULL) {
        rb_notes_save(d->store, d->path);
    }
}

/* Fills the editor from a note (or empties it).  The suppress flag keeps
 * the programmatic fill's EN_CHANGE from marking a note the user has not
 * touched as dirty. */
static void rb_nt_editor_load(RbNotes *d, const rb_note *note)
{
    d->suppress = 1;
    rb_nt_set_text(d->title,
                   (note != NULL && note->title != NULL) ? note->title : "");
    rb_nt_set_text(d->body,
                   (note != NULL && note->body != NULL) ? note->body : "");
    d->suppress = 0;
    d->dirty = 0;
}

static void rb_nt_sync_actions(RbNotes *d)
{
    BOOL has = (d->current_id != 0) ? TRUE : FALSE;

    if (d->b_dup != NULL) EnableWindow(d->b_dup, has);
    if (d->b_del != NULL) EnableWindow(d->b_del, has);
    if (d->b_save != NULL) EnableWindow(d->b_save, has);
}

/* One row: "title - date", the date the note was last touched, local like
 * every other stamp this edition prints.  The dash is spelled out as UTF-8
 * bytes rather than a narrow literal, because MSVC reads this file in the
 * system codepage - the same reason the Downloads window prints " - ". */
static void rb_nt_row_text(const rb_note *note, char *out, size_t cap)
{
    time_t t;
    struct tm *parts;
    char when[32];
    const char *title;

    t = (time_t)(note->updated_at / 1000);
    parts = localtime(&t);
    if (parts == NULL ||
        strftime(when, sizeof when, "%Y-%m-%d %H:%M", parts) == 0) {
        snprintf(when, sizeof when, "unknown");
    }
    title = (note->title != NULL && note->title[0] != '\0')
                ? note->title : "(untitled)";
    snprintf(out, cap, "%s \xE2\x80\x94 %s", title, when);
}

/* Rebuilds the list from the store through the search field.  Rows are
 * re-read rather than patched one by one because rb_notes' display order
 * (updated_at DESC) and the filter can both move one; the note the editor
 * holds is re-selected when the filter still shows it, and the editor is
 * emptied only when it holds nothing unsaved. */
static void rb_nt_refill(RbNotes *d)
{
    char *needle;
    const rb_note **rows;
    int cap;
    int n;
    int i;
    int sel;

    needle = rb_nt_get_text(d->search);
    rows = NULL;
    n = 0;
    cap = (d->store != NULL) ? rb_notes_count(d->store) : 0;
    if (cap > 0) {
        rows = (const rb_note **)malloc((size_t)cap * sizeof *rows);
        if (rows != NULL) {
            n = rb_notes_search(d->store, needle, rows, cap);
        }
    }
    free(needle);

    free(d->ids);
    d->ids = NULL;
    d->n_ids = 0;
    if (n > 0) {
        d->ids = (long *)malloc((size_t)n * sizeof(long));
    }

    SendMessageW(d->list, WM_SETREDRAW, FALSE, 0);
    SendMessageW(d->list, LB_RESETCONTENT, 0, 0);
    for (i = 0; i < n; i++) {
        const rb_note *note = rows[i];
        char line[512];
        wchar_t *wl;

        if (note == NULL) continue;
        rb_nt_row_text(note, line, sizeof line);
        wl = rb_nt_wide(line);
        if (wl != NULL) {
            SendMessageW(d->list, LB_ADDSTRING, 0, (LPARAM)wl);
            free(wl);
            if (d->ids != NULL) d->ids[d->n_ids++] = note->id;
        }
    }

    sel = -1;
    for (i = 0; i < d->n_ids; i++) {
        if (d->ids[i] == d->current_id) {
            sel = i;
            break;
        }
    }
    if (sel >= 0) {
        SendMessageW(d->list, LB_SETCURSEL, (WPARAM)sel, 0);
    } else if (d->current_id != 0 && !d->dirty) {
        /* Filtered away or deleted, and the editor holds nothing unsaved:
         * empty it rather than leave it pointed at a row the list no
         * longer shows. */
        d->current_id = 0;
        rb_nt_editor_load(d, NULL);
    }
    SendMessageW(d->list, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(d->list, NULL, TRUE);
    rb_nt_sync_actions(d);
    free(rows);
}

/* The editor's text becomes the note, stamped now - the whole reason
 * rb_notes_edit exists.  A note with no changes is left alone: selecting
 * around the list must not bump every note the cursor passed over. */
static void rb_nt_commit(RbNotes *d)
{
    char *t;
    char *b;

    if (d->store == NULL || d->current_id == 0 || !d->dirty) return;
    t = rb_nt_get_text(d->title);
    b = rb_nt_get_text(d->body);
    rb_notes_edit(d->store, d->current_id, t, b, rb_profile_now_ms());
    free(t);
    free(b);
    d->dirty = 0;
    rb_nt_save(d);
    rb_nt_refill(d);
}

static void rb_nt_load_selection(RbNotes *d)
{
    int i;
    long id;
    const rb_note *note;
    int k;

    i = (int)SendMessageW(d->list, LB_GETCURSEL, 0, 0);
    if (i < 0 || i >= d->n_ids) return;
    id = d->ids[i];
    if (id == d->current_id) return;
    /* The note that is leaving the editor commits first; the refill that
     * follows re-selects ITS row, so the caret is put back where the user
     * actually clicked afterwards. */
    rb_nt_commit(d);
    d->current_id = id;
    note = (d->store != NULL) ? rb_notes_get(d->store, id) : NULL;
    rb_nt_editor_load(d, note);
    for (k = 0; k < d->n_ids; k++) {
        if (d->ids[k] == id) {
            SendMessageW(d->list, LB_SETCURSEL, (WPARAM)k, 0);
            break;
        }
    }
    rb_nt_sync_actions(d);
}

/* ------------------------------------------------------------------ */
/* The actions.  Duplicate/Delete/Save follow the note the editor holds,
 * which is why they start disabled when there is none. */

static void rb_nt_new(RbNotes *d)
{
    long id;

    if (d->store == NULL) return;
    rb_nt_commit(d);   /* the note in the editor first, or it is overwritten */
    id = rb_notes_add(d->store, "", "", rb_profile_now_ms());
    if (id == 0) return;
    d->current_id = id;
    rb_nt_editor_load(d, NULL);   /* a fresh note starts empty */
    rb_nt_save(d);
    rb_nt_refill(d);
}

static void rb_nt_duplicate(RbNotes *d)
{
    const rb_note *note;
    const char *t;
    char *copy;
    size_t n;
    long id;

    if (d->store == NULL || d->current_id == 0) return;
    rb_nt_commit(d);
    note = rb_notes_get(d->store, d->current_id);
    if (note == NULL) return;
    t = (note->title != NULL && note->title[0] != '\0') ? note->title : "";
    n = strlen(t) + sizeof " (copy)";
    copy = (char *)malloc(n);
    if (copy == NULL) return;
    snprintf(copy, n, "%s (copy)", t);
    id = rb_notes_add(d->store, copy,
                      (note->body != NULL) ? note->body : "",
                      rb_profile_now_ms());
    free(copy);
    if (id == 0) return;
    d->current_id = id;
    rb_nt_editor_load(d, rb_notes_get(d->store, id));
    rb_nt_save(d);
    rb_nt_refill(d);
}

static void rb_nt_delete(RbNotes *d)
{
    if (d->store == NULL || d->current_id == 0) return;
    if (MessageBoxW(d->dlg, L"Delete this note? This cannot be undone.",
                    L"Delete note",
                    MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) != IDYES) {
        return;
    }
    rb_notes_remove(d->store, d->current_id);
    d->current_id = 0;
    rb_nt_editor_load(d, NULL);
    rb_nt_save(d);
    rb_nt_refill(d);
}

static void rb_nt_save_clicked(RbNotes *d)
{
    if (d->store == NULL || d->current_id == 0) return;
    /* An explicit Save writes even when nothing changed: the user is
     * asking for the file to be current, not for a diff. */
    d->dirty = 1;
    rb_nt_commit(d);
}

static void rb_nt_close(RbNotes *d)
{
    App *app = d->app;

    g_notes = NULL;
    /* Closing is a flush: the editor's text lands in the store and the
     * store on disk, under the profile the window was opened for. */
    rb_nt_commit(d);
    rb_nt_save(d);
    if (d->dlg != NULL) DestroyWindow(d->dlg);
    /* Modality was the owner being disabled; giving it back is what closes
     * the dialog as far as the user is concerned. */
    EnableWindow(app->hwnd, TRUE);
    SetForegroundWindow(app->hwnd);
    if (d->fnt != NULL) DeleteObject(d->fnt);
    free(d->ids);
    if (d->store != NULL) rb_notes_free(d->store);
    free(d->path);
    free(d);
}

/* ------------------------------------------------------------------ */

static LRESULT CALLBACK rb_nt_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbNotes *d = (RbNotes *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_ERASEBKGND:
        if (d != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, d->app->br_chrome);
            return 1;
        }
        break;
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX:
    case WM_CTLCOLORBTN:
        if (d != NULL) {
            SetBkMode((HDC)wp, OPAQUE);
            SetBkColor((HDC)wp, rb_col(d->app->pal.background));
            SetTextColor((HDC)wp, rb_col(d->app->pal.text_primary));
            return (LRESULT)d->app->br_chrome;
        }
        break;
    case WM_KEYDOWN:
        /* Arrives only while the window itself - not a control - holds the
         * focus; see the keyboard note at the top of the file. */
        if (d != NULL && wp == VK_ESCAPE) {
            rb_nt_close(d);
            return 0;
        }
        break;
    case WM_COMMAND:
        if (d != NULL) {
            int id = (int)LOWORD(wp);
            int notify = (int)HIWORD(wp);

            if (id == RB_NT_SEARCH && notify == EN_CHANGE && !d->suppress) {
                rb_nt_refill(d);
                return 0;
            }
            if ((id == RB_NT_TITLE || id == RB_NT_BODY) &&
                notify == EN_CHANGE && !d->suppress) {
                d->dirty = 1;
                return 0;
            }
            if (id == RB_NT_LIST && notify == LBN_SELCHANGE) {
                rb_nt_load_selection(d);
                return 0;
            }
            if (id == RB_NT_NEW) { rb_nt_new(d); return 0; }
            if (id == RB_NT_DUP) { rb_nt_duplicate(d); return 0; }
            if (id == RB_NT_DEL) { rb_nt_delete(d); return 0; }
            if (id == RB_NT_SAVE) { rb_nt_save_clicked(d); return 0; }
            if (id == RB_NT_CLOSE) { rb_nt_close(d); return 0; }
        }
        break;
    case WM_CLOSE:
        if (d != NULL) { rb_nt_close(d); return 0; }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void rb_show_notes(App *app)
{
    RbNotes *d;
    RECT want;
    RECT rc;
    int x;
    int y_btn;
    int list_h;
    int ed_x;
    int body_h;

    if (app == NULL || app->hwnd == NULL) return;
    if (g_notes != NULL) {
        SetForegroundWindow(g_notes->dlg);
        return;
    }

    d = (RbNotes *)calloc(1, sizeof *d);
    if (d == NULL) return;
    d->app = app;

    /* Not at the profile's font scale, for the reason rb_show_prefs gives:
     * the layout is written against fixed metrics, and scaling the font
     * alone would move the controls out of a box that did not move with
     * them. */
    d->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                         CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                         L"Segoe UI");

    {
        WNDCLASSEXW wc;
        memset(&wc, 0, sizeof wc);
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = rb_nt_proc;
        wc.hInstance = app->hinst;
        wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
        wc.hbrBackground = NULL;
        wc.lpszClassName = L"RoomBrowserNotes";
        if (!RegisterClassExW(&wc) &&
            GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            if (d->fnt != NULL) DeleteObject(d->fnt);
            free(d);
            return;
        }
    }

    want.left = 0;
    want.top = 0;
    want.right = RB_NT_WIN_W;
    want.bottom = RB_NT_WIN_H;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_notes = d;
    d->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                             L"RoomBrowserNotes", L"Notes",
                             WS_POPUP | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT,
                             want.right - want.left, want.bottom - want.top,
                             app->hwnd, NULL, app->hinst, NULL);
    if (d->dlg == NULL) {
        rb_nt_close(d);
        return;
    }
    SetWindowLongPtrW(d->dlg, GWLP_USERDATA, (LONG_PTR)d);
    rb_apply_dark_titlebar(d->dlg);

    /* The store belongs to the ACTIVE profile: loaded once here, written
     * through on every mutation, flushed on close and on a profile
     * switch. */
    d->path = rb_nt_notes_path(app);
    d->store = rb_notes_new();
    if (d->store != NULL && d->path != NULL) {
        rb_notes_load(d->store, d->path);
    }

    GetClientRect(d->dlg, &rc);
    y_btn = rc.bottom - RB_NT_MARGIN - RB_NT_BTNH;
    ed_x = RB_NT_MARGIN + RB_NT_LIST_W + RB_NT_GAP;
    list_h = y_btn - RB_NT_GAP - (RB_NT_MARGIN + 24 + RB_NT_GAP);

    /* The search row; the filter is live on EN_CHANGE. */
    rb_nt_ctl(d, L"STATIC", L"Search", 0, RB_NT_MARGIN, RB_NT_MARGIN + 4,
              46, 20, 0);
    d->search = rb_nt_ctl(d, L"EDIT", L"",
                          ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                          RB_NT_MARGIN + 52, RB_NT_MARGIN,
                          rc.right - RB_NT_MARGIN - (RB_NT_MARGIN + 52),
                          24, RB_NT_SEARCH);

    /* The list on the left; the editor takes everything right of it. */
    d->list = rb_nt_ctl(d, L"LISTBOX", L"",
                        WS_TABSTOP | WS_VSCROLL | WS_BORDER | LBS_NOTIFY |
                        LBS_NOINTEGRALHEIGHT,
                        RB_NT_MARGIN, RB_NT_MARGIN + 24 + RB_NT_GAP,
                        RB_NT_LIST_W, list_h, RB_NT_LIST);
    d->title = rb_nt_ctl(d, L"EDIT", L"",
                         ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                         ed_x, RB_NT_MARGIN + 24 + RB_NT_GAP,
                         rc.right - RB_NT_MARGIN - ed_x, 24, RB_NT_TITLE);
    body_h = y_btn - RB_NT_GAP -
             (RB_NT_MARGIN + 24 + RB_NT_GAP + 24 + RB_NT_GAP);
    d->body = rb_nt_ctl(d, L"EDIT", L"",
                        ES_MULTILINE | ES_AUTOVSCROLL | ES_WANTRETURN |
                        WS_VSCROLL | WS_BORDER | WS_TABSTOP,
                        ed_x, RB_NT_MARGIN + 24 + RB_NT_GAP + 24 + RB_NT_GAP,
                        rc.right - RB_NT_MARGIN - ed_x, body_h, RB_NT_BODY);

    /* The bottom row.  New note is always offered; the rest follow the
     * note the editor holds, which is why they start disabled. */
    x = RB_NT_MARGIN;
    d->b_new = rb_nt_ctl(d, L"BUTTON", L"New note",
                         WS_TABSTOP | BS_PUSHBUTTON, x, y_btn, 84,
                         RB_NT_BTNH, RB_NT_NEW);
    x += 84 + RB_NT_GAP;
    d->b_dup = rb_nt_ctl(d, L"BUTTON", L"Duplicate",
                         WS_TABSTOP | BS_PUSHBUTTON, x, y_btn, 90,
                         RB_NT_BTNH, RB_NT_DUP);
    x += 90 + RB_NT_GAP;
    d->b_del = rb_nt_ctl(d, L"BUTTON", L"Delete",
                         WS_TABSTOP | BS_PUSHBUTTON, x, y_btn, 76,
                         RB_NT_BTNH, RB_NT_DEL);
    d->b_save = rb_nt_ctl(d, L"BUTTON", L"Save",
                          WS_TABSTOP | BS_PUSHBUTTON,
                          rc.right - RB_NT_MARGIN - 76 - RB_NT_GAP - 76,
                          y_btn, 76, RB_NT_BTNH, RB_NT_SAVE);
    rb_nt_ctl(d, L"BUTTON", L"Close", WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_NT_MARGIN - 76, y_btn, 76, RB_NT_BTNH,
              RB_NT_CLOSE);

    rb_nt_refill(d);

    EnableWindow(app->hwnd, FALSE);
    ShowWindow(d->dlg, SW_SHOW);
    SetForegroundWindow(d->dlg);
}

/* The two hooks the profile switch calls, at the steps its protocol
 * already uses for the other per-profile state. */

void rb_notes_flush(App *app)
{
    (void)app;
    if (g_notes == NULL) return;
    /* The editor's text first, then the file: both under the profile the
     * window was opened for, which is still the active one here. */
    rb_nt_commit(g_notes);
    rb_nt_save(g_notes);
}

void rb_notes_reload(App *app)
{
    RbNotes *d = g_notes;

    (void)app;
    if (d == NULL) return;
    /* The old profile's copy dies here: what the window shows from now on
     * is the new profile's file, the same moment the rest of the
     * profile's state is re-read. */
    if (d->store != NULL) rb_notes_free(d->store);
    d->store = rb_notes_new();
    free(d->path);
    d->path = rb_nt_notes_path(d->app);
    if (d->store != NULL && d->path != NULL) {
        rb_notes_load(d->store, d->path);
    }
    d->current_id = 0;
    rb_nt_editor_load(d, NULL);
    rb_nt_refill(d);
}
