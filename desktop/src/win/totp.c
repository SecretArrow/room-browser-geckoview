/*
 * Room Browser (desktop) - Windows 2FA (TOTP) window.
 *
 * The 2FA manager over the ACTIVE profile's totp.jsonl.  The store is the
 * core's rb_totp_store, owned by the window while it is open: loaded when
 * the window opens, written through on every mutation, and handed back to
 * the profile switch by rb_totp_flush()/rb_totp_reload() at the two points
 * the switch protocol already uses for the other per-profile state.  A
 * window that is not open owns nothing, which is why both of those are
 * no-ops then.
 *
 * The secrets never leave the core store.  The rows show codes, computed
 * once a second on the window's own WM_TIMER; the rebuild keeps the row
 * the user has picked by re-applying the id, the same way the Downloads
 * window re-snaps its rows on its timer without losing the selection.
 *
 * Backup is the core's encrypted one-line blob (rb_totp_export_encrypted /
 * rb_totp_import_encrypted).  The blob leaves and enters through a small
 * prompt rather than a file dialog: comdlg32 is not in the link line, and
 * adding a link dependency for one string is not a trade this codebase
 * makes - the Downloads window already spells that out for shlwapi.
 *
 * It is a plain window rather than a DLGTEMPLATE one, exactly like the
 * Downloads window, and it is modal the same way: by disabling its owner
 * instead of by a system modal loop.  The Add-account and backup prompts
 * are owned popups over this window, and disable it while they are open.
 *
 * Keyboard note, shared with the Downloads window: the aux windows get no
 * IsDialogMessageW pass because the message loop belongs to main.c, so
 * Tab does not walk the controls and Enter does not press the default
 * button.  Everything is reachable with the mouse, and Escape closes the
 * focused window while the window itself holds the focus.
 */
#include "totp.h"

/* rb_core.h does not pull the TOTP store in, so this layer names it
 * directly - the same include path the chrome header uses for the rest of
 * the core. */
#include "core/rb_totp.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wchar.h>

/* Window metrics, fixed like the Downloads window's (it does not resize). */
#define RB_TT_WIN_W    780
#define RB_TT_WIN_H    300
#define RB_TT_MARGIN   12
#define RB_TT_BTNH     26
#define RB_TT_GAP      6

/* The Add-account dialog's metrics. */
#define RB_TA_WIN_W    470
#define RB_TA_WIN_H    360
#define RB_TA_MARGIN   12
#define RB_TA_LABEL_W  124
#define RB_TA_TOP      12
#define RB_TA_STRIDE   32
#define RB_TA_BTNH     26
#define RB_TA_GAP      6

#define RB_TA_ROW(i)   (RB_TA_TOP + (i) * RB_TA_STRIDE)

/* Control ids.  They are window-local: the main window's command ranges
 * (RB_ID_BM_FIRST and up) are dispatched by chrome.c's proc, never by
 * these, which is the same reason the Downloads window may sit on 4001. */
#define RB_TT_LIST     4301
#define RB_TT_TIMER    4302
#define RB_TT_ADD      4303
#define RB_TT_COPY     4304
#define RB_TT_DEL      4305
#define RB_TT_EXPORT   4306
#define RB_TT_IMPORT   4307
#define RB_TT_CLOSE    4308

#define RB_TA_ISSUER   4321
#define RB_TA_LABEL    4322
#define RB_TA_SECRET   4323
#define RB_TA_URI      4324
#define RB_TA_FILL     4325
#define RB_TA_ALGO     4326
#define RB_TA_DIGITS   4327
#define RB_TA_PERIOD   4328
#define RB_TA_ERROR    4329
#define RB_TA_OK       4330
#define RB_TA_CANCEL   4331

#define RB_TP_PATH     4341
#define RB_TP_PASS     4342
#define RB_TP_PASS2    4343
#define RB_TP_ERROR    4344
#define RB_TP_OK       4345
#define RB_TP_CANCEL   4346

typedef struct {
    App *app;
    HWND dlg, list;
    HWND b_copy, b_del;
    HFONT fnt;

    rb_totp_store *store;   /* the active profile's accounts */
    char *path;             /* totp.jsonl of the profile the window opened on */
    long *ids;              /* list row -> account id */
    int n_ids;
    long current_id;        /* the picked account; 0 = none */
} RbTotp;

static RbTotp *g_totp = NULL;

/* ------------------------------------------------------------------ */
/* Small helpers, the same shape the Downloads window's carry */

static wchar_t *rb_tt_wide(const char *s)
{
    return rb_utf8_to_wide(s != NULL ? s : "");
}

/* A control's text as UTF-8.  Never NULL: an empty field reads as "", the
 * same way the core stores a missing issuer or label. */
static char *rb_tt_get_text(HWND c)
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

static void rb_tt_set_text(HWND c, const char *s)
{
    wchar_t *w;

    if (c == NULL) return;
    w = rb_tt_wide(s);
    SendMessageW(c, WM_SETTEXT, 0, (LPARAM)((w != NULL) ? w : L""));
    free(w);
}

static HWND rb_tt_ctl(HWND parent, HINSTANCE hi, HFONT fnt,
                      const wchar_t *cls, const wchar_t *text, DWORD style,
                      int x, int y, int w, int h, int id)
{
    HWND c = CreateWindowExW(0, cls, text, WS_CHILD | WS_VISIBLE | style,
                             x, y, w, h, parent, (HMENU)(INT_PTR)id,
                             hi, NULL);
    if (c != NULL && fnt != NULL) {
        SendMessageW(c, WM_SETFONT, (WPARAM)fnt, TRUE);
    }
    return c;
}

/* <active profile's settings dir>/<name>, built the way chrome.c builds
 * history.jsonl - rb_paths_join over a directory - but over the
 * per-profile settings directory rb_profile.h documents as the home of
 * profile-owned files ("settings.txt, history.jsonl").  The directory is
 * made here too: a first save must not depend on the profile having been
 * created with its sub-directories intact. */
static char *rb_tt_store_path(App *app, const char *name)
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
    path = rb_paths_join(dir, name);
    free(dir);
    return path;
}

/* ------------------------------------------------------------------ */
/* Rows and codes */

/* "123 456" for 6 digits, "1234 5678" for 8: a space every 3 or 4 digits
 * from the left, which is how the codes are printed everywhere else in
 * the product. */
static void rb_tt_group(const char *code, int digits, char *out, size_t cap)
{
    int step = (digits == 8) ? 4 : 3;
    size_t o = 0;
    int i;

    for (i = 0; code[i] != '\0' && o + 2 < cap; i++) {
        if (i > 0 && i % step == 0) out[o++] = ' ';
        out[o++] = code[i];
    }
    out[o] = '\0';
}

/* The current code for one account, grouped.  A failure is spelled out
 * rather than hidden - an account whose code cannot be computed is a
 * problem the user should see, not a blank row. */
static void rb_tt_code_of(const rb_totp_account *a, long long now,
                          char *out, size_t cap)
{
    char code[16];
    char grouped[24];

    if (!rb_totp_code(a->secret, a->secret_len, a->algo, a->digits,
                      a->period, now, code)) {
        snprintf(out, cap, "--------");
        return;
    }
    rb_tt_group(code, a->digits, grouped, sizeof grouped);
    snprintf(out, cap, "%s", grouped);
}

/* One row: "issuer - label   123 456   12s".  The dash is spelled out as
 * UTF-8 bytes rather than a narrow literal, because MSVC reads this file
 * in the system codepage - the same reason the Downloads window prints
 * " - ". */
static void rb_tt_row_text(const rb_totp_account *a, long long now,
                           char *out, size_t cap)
{
    char code[24];
    int secs;
    const char *issuer;
    const char *label;

    rb_tt_code_of(a, now, code, sizeof code);
    secs = rb_totp_seconds_remaining(a->period, now);
    if (secs <= 0) secs = 0;
    issuer = (a->issuer != NULL && a->issuer[0] != '\0') ? a->issuer : "-";
    label = (a->label != NULL && a->label[0] != '\0') ? a->label : "-";
    snprintf(out, cap, "%s \xE2\x80\x94 %s   %s   %ds", issuer, label,
             code, secs);
}

static void rb_tt_save(RbTotp *d)
{
    if (d->store != NULL && d->path != NULL) {
        rb_totp_save(d->store, d->path);
    }
}

/* The selected account, or NULL.  Walked by id rather than held as a
 * pointer: a refill moves rows, and a pointer into the store can be stale
 * by the time a click lands - the same reason the Downloads window
 * carries ids, not pointers. */
static const rb_totp_account *rb_tt_selected(RbTotp *d)
{
    int i;
    int n;

    if (d->store == NULL || d->current_id == 0) return NULL;
    n = rb_totp_count(d->store);
    for (i = 0; i < n; i++) {
        const rb_totp_account *a = rb_totp_at(d->store, i);
        if (a != NULL && a->id == d->current_id) return a;
    }
    return NULL;
}

static void rb_tt_sync_actions(RbTotp *d)
{
    BOOL has = (d->current_id != 0) ? TRUE : FALSE;

    if (d->b_copy != NULL) EnableWindow(d->b_copy, has);
    if (d->b_del != NULL) EnableWindow(d->b_del, has);
}

/* Rebuilds the rows from the store.  The WM_TIMER calls this every second,
 * so the rebuild is written to be cheap and to keep the row the user has
 * picked: the selection is remembered by id and re-applied. */
static void rb_tt_refill(RbTotp *d)
{
    long long now = (long long)time(NULL);
    int n;
    int i;
    int sel;

    free(d->ids);
    d->ids = NULL;
    d->n_ids = 0;
    n = (d->store != NULL) ? rb_totp_count(d->store) : 0;
    if (n > 0) {
        d->ids = (long *)malloc((size_t)n * sizeof(long));
    }

    SendMessageW(d->list, WM_SETREDRAW, FALSE, 0);
    SendMessageW(d->list, LB_RESETCONTENT, 0, 0);
    for (i = 0; i < n; i++) {
        const rb_totp_account *a = rb_totp_at(d->store, i);
        char line[512];
        wchar_t *wl;

        if (a == NULL) continue;
        rb_tt_row_text(a, now, line, sizeof line);
        wl = rb_tt_wide(line);
        if (wl != NULL) {
            SendMessageW(d->list, LB_ADDSTRING, 0, (LPARAM)wl);
            free(wl);
            if (d->ids != NULL) d->ids[d->n_ids++] = a->id;
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
    } else {
        d->current_id = 0;
    }
    SendMessageW(d->list, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(d->list, NULL, TRUE);
    rb_tt_sync_actions(d);
}

/* Copies the selected row's current code, as CF_UNICODETEXT, in the
 * grouped form the row shows - what the user pastes is what they read.
 * Local, like the Downloads window's copy: the clipboard block is built
 * here and handed over, with ownership only leaving on success. */
static void rb_tt_copy(RbTotp *d)
{
    const rb_totp_account *a = rb_tt_selected(d);
    char code[24];
    wchar_t *w;
    size_t bytes;
    void *dst;

    if (a == NULL) return;
    rb_tt_code_of(a, (long long)time(NULL), code, sizeof code);
    w = rb_tt_wide(code);
    if (w == NULL) return;
    bytes = (wcslen(w) + 1) * sizeof(wchar_t);

    if (OpenClipboard(d->dlg)) {
        HGLOBAL block = GlobalAlloc(GMEM_MOVEABLE, bytes);

        if (block != NULL) {
            dst = GlobalLock(block);
            if (dst != NULL) {
                memcpy(dst, w, bytes);
                GlobalUnlock(block);
                EmptyClipboard();
                /* On success the clipboard owns the block; on failure it
                 * still belongs to us and has to be freed. */
                if (SetClipboardData(CF_UNICODETEXT, block) == NULL) {
                    GlobalFree(block);
                }
            } else {
                GlobalFree(block);
            }
        }
        CloseClipboard();
    }
    free(w);
}

static void rb_tt_delete(RbTotp *d)
{
    const rb_totp_account *a = rb_tt_selected(d);

    if (a == NULL || d->store == NULL) return;
    if (MessageBoxW(d->dlg,
                    L"Remove this account? Its secret cannot be recovered.",
                    L"Remove account",
                    MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2) != IDYES) {
        return;
    }
    rb_totp_remove(d->store, a->id);
    d->current_id = 0;
    rb_tt_save(d);
    rb_tt_refill(d);
}

static void rb_tt_close(RbTotp *d)
{
    App *app = d->app;

    g_totp = NULL;
    if (d->dlg != NULL) DestroyWindow(d->dlg);
    /* Modality was the owner being disabled; giving it back is what closes
     * the dialog as far as the user is concerned. */
    EnableWindow(app->hwnd, TRUE);
    SetForegroundWindow(app->hwnd);
    if (d->fnt != NULL) DeleteObject(d->fnt);
    free(d->ids);
    if (d->store != NULL) rb_totp_free(d->store);
    free(d->path);
    free(d);
}

/* ------------------------------------------------------------------ */
/* The Add-account dialog: an owned popup that disables the 2FA window
 * while it is open, the same modality the main windows use. */

typedef struct {
    RbTotp *t;
    HWND dlg;
    HWND e_issuer, e_label, e_secret, e_uri, e_period;
    HWND cb_algo, cb_digits;
    HWND st_error;
    HFONT fnt;
} RbTotpAdd;

static RbTotpAdd *g_add = NULL;

/* The inline error line.  Errors live in the window rather than in
 * message boxes, so a typoed Base32 string can be fixed without a round
 * trip through a modal click. */
static void rb_ta_error(RbTotpAdd *a, const wchar_t *msg)
{
    if (a->st_error == NULL) return;
    if (msg == NULL) {
        ShowWindow(a->st_error, SW_HIDE);
    } else {
        SetWindowTextW(a->st_error, msg);
        ShowWindow(a->st_error, SW_SHOWNORMAL);
    }
}

static void rb_ta_close(RbTotpAdd *a)
{
    RbTotp *t = a->t;

    g_add = NULL;
    if (a->dlg != NULL) DestroyWindow(a->dlg);
    EnableWindow(t->dlg, TRUE);
    SetForegroundWindow(t->dlg);
    if (a->fnt != NULL) DeleteObject(a->fnt);
    free(a);
}

/* "Fill" over a pasted otpauth:// URI.  The secret is written back through
 * Base32 so the field stays the one place the secret lives: whatever the
 * user saves is exactly what the editor shows, typed or filled. */
static void rb_ta_fill(RbTotpAdd *a)
{
    char *uri;
    rb_totp_pending p;
    int ok;

    memset(&p, 0, sizeof p);
    uri = rb_tt_get_text(a->e_uri);
    ok = rb_totp_parse_uri(uri, &p);
    free(uri);
    if (!ok) {
        rb_ta_error(a,
                    L"Error: that is not an otpauth:// URI with a valid secret.");
        return;
    }
    rb_tt_set_text(a->e_issuer, (p.issuer != NULL) ? p.issuer : "");
    rb_tt_set_text(a->e_label, (p.label != NULL) ? p.label : "");
    if (p.secret_len > 0) {
        size_t cap = ((p.secret_len * 8) + 4) / 5 + 1;
        char *b32 = (char *)malloc(cap);

        if (b32 != NULL &&
            rb_base32_encode(p.secret, p.secret_len, b32, cap)) {
            rb_tt_set_text(a->e_secret, b32);
        }
        free(b32);
    }
    SendMessageW(a->cb_algo, CB_SETCURSEL,
                 (WPARAM)((p.algo == RB_TOTP_SHA256) ? 1 : 0), 0);
    SendMessageW(a->cb_digits, CB_SETCURSEL,
                 (WPARAM)((p.digits == 8) ? 1 : 0), 0);
    {
        char per[16];

        snprintf(per, sizeof per, "%d",
                 (p.period > 0) ? p.period : RB_TOTP_DEFAULT_PERIOD);
        rb_tt_set_text(a->e_period, per);
    }
    rb_totp_pending_free(&p);
    rb_ta_error(a, NULL);
}

static void rb_ta_ok(RbTotpAdd *a)
{
    char *issuer;
    char *label;
    char *secret;
    char *period_s;
    unsigned char *raw;
    int raw_len;
    int period;
    int algo;
    int digits;
    long id;

    issuer = rb_tt_get_text(a->e_issuer);
    label = rb_tt_get_text(a->e_label);
    secret = rb_tt_get_text(a->e_secret);
    period_s = rb_tt_get_text(a->e_period);
    raw = NULL;
    id = 0;

    /* The secret has to Base32-decode before anything else is believed: a
     * typoed secret would store an account that can never produce the
     * right code.  It is measured with a NULL buffer, per the decoder's
     * contract. */
    raw_len = (secret[0] != '\0') ? rb_base32_decode(secret, NULL, 0) : -1;
    if (raw_len <= 0) {
        rb_ta_error(a, L"Error: the secret is not valid Base32.");
        goto done;
    }
    raw = (unsigned char *)malloc((size_t)raw_len);
    if (raw == NULL ||
        rb_base32_decode(secret, raw, (size_t)raw_len) != raw_len) {
        rb_ta_error(a, L"Error: the secret is not valid Base32.");
        goto done;
    }
    period = atoi(period_s);
    if (period < 1 || period > 86400) {
        rb_ta_error(a, L"Error: the period must be 1 to 86400 seconds.");
        goto done;
    }
    algo = ((int)SendMessageW(a->cb_algo, CB_GETCURSEL, 0, 0) == 1)
               ? RB_TOTP_SHA256 : RB_TOTP_SHA1;
    digits = ((int)SendMessageW(a->cb_digits, CB_GETCURSEL, 0, 0) == 1)
                 ? 8 : RB_TOTP_DEFAULT_DIGITS;

    id = rb_totp_add(a->t->store, issuer, label, raw, (size_t)raw_len,
                     algo, digits, period, rb_profile_now_ms());
    if (id == 0) {
        rb_ta_error(a, L"Error: the account could not be added.");
        goto done;
    }
    a->t->current_id = id;   /* the new account becomes the picked row */
    rb_tt_save(a->t);
    rb_tt_refill(a->t);
    rb_ta_close(a);

done:
    free(raw);
    free(issuer);
    free(label);
    free(secret);
    free(period_s);
}

static LRESULT CALLBACK rb_ta_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbTotpAdd *a =
        (RbTotpAdd *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_ERASEBKGND:
        if (a != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, a->t->app->br_chrome);
            return 1;
        }
        break;
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORLISTBOX:
    case WM_CTLCOLORBTN:
        if (a != NULL) {
            SetBkMode((HDC)wp, OPAQUE);
            SetBkColor((HDC)wp, rb_col(a->t->app->pal.background));
            SetTextColor((HDC)wp, rb_col(a->t->app->pal.text_primary));
            return (LRESULT)a->t->app->br_chrome;
        }
        break;
    case WM_KEYDOWN:
        /* Arrives only while the dialog itself - not a control - holds the
         * focus; see the keyboard note at the top of the file. */
        if (a != NULL && wp == VK_ESCAPE) {
            rb_ta_close(a);
            return 0;
        }
        break;
    case WM_COMMAND:
        if (a != NULL) {
            int id = (int)LOWORD(wp);

            if (id == RB_TA_FILL) { rb_ta_fill(a); return 0; }
            if (id == RB_TA_OK) { rb_ta_ok(a); return 0; }
            if (id == RB_TA_CANCEL) { rb_ta_close(a); return 0; }
        }
        break;
    case WM_CLOSE:
        if (a != NULL) { rb_ta_close(a); return 0; }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void rb_ta_show(RbTotp *t)
{
    RbTotpAdd *a;
    RECT want;
    RECT rc;
    int cx;
    int fw;

    if (t == NULL || t->dlg == NULL) return;
    if (g_add != NULL) {
        SetForegroundWindow(g_add->dlg);
        return;
    }

    a = (RbTotpAdd *)calloc(1, sizeof *a);
    if (a == NULL) return;
    a->t = t;
    /* Not at the profile's font scale, for the reason rb_show_prefs gives:
     * the layout is written against fixed metrics. */
    a->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                         CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                         L"Segoe UI");

    {
        WNDCLASSEXW wc;
        memset(&wc, 0, sizeof wc);
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = rb_ta_proc;
        wc.hInstance = t->app->hinst;
        wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
        wc.hbrBackground = NULL;
        wc.lpszClassName = L"RoomBrowserTotpAdd";
        if (!RegisterClassExW(&wc) &&
            GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            if (a->fnt != NULL) DeleteObject(a->fnt);
            free(a);
            return;
        }
    }

    want.left = 0;
    want.top = 0;
    want.right = RB_TA_WIN_W;
    want.bottom = RB_TA_WIN_H;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_add = a;
    a->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                             L"RoomBrowserTotpAdd", L"Add account",
                             WS_POPUP | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT,
                             want.right - want.left, want.bottom - want.top,
                             t->dlg, NULL, t->app->hinst, NULL);
    if (a->dlg == NULL) {
        rb_ta_close(a);
        return;
    }
    SetWindowLongPtrW(a->dlg, GWLP_USERDATA, (LONG_PTR)a);
    rb_apply_dark_titlebar(a->dlg);

    GetClientRect(a->dlg, &rc);
    cx = RB_TA_MARGIN + RB_TA_LABEL_W + RB_TA_GAP;
    fw = rc.right - cx - RB_TA_MARGIN;

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Issuer", 0,
              RB_TA_MARGIN, RB_TA_ROW(0) + 4, RB_TA_LABEL_W, 16, 0);
    a->e_issuer = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"EDIT", L"",
                            ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                            cx, RB_TA_ROW(0), fw, 24, RB_TA_ISSUER);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Label (account)", 0,
              RB_TA_MARGIN, RB_TA_ROW(1) + 4, RB_TA_LABEL_W, 16, 0);
    a->e_label = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"EDIT", L"",
                           ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                           cx, RB_TA_ROW(1), fw, 24, RB_TA_LABEL);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Secret (Base32)", 0,
              RB_TA_MARGIN, RB_TA_ROW(2) + 4, RB_TA_LABEL_W, 16, 0);
    a->e_secret = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"EDIT", L"",
                            ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                            cx, RB_TA_ROW(2), fw, 24, RB_TA_SECRET);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"otpauth:// URI", 0,
              RB_TA_MARGIN, RB_TA_ROW(3) + 4, RB_TA_LABEL_W, 16, 0);
    a->e_uri = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"EDIT", L"",
                         ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                         cx, RB_TA_ROW(3), fw - 76 - RB_TA_GAP, 24,
                         RB_TA_URI);
    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"BUTTON", L"Fill",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TA_MARGIN - 76, RB_TA_ROW(3), 76, 24,
              RB_TA_FILL);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Algorithm", 0,
              RB_TA_MARGIN, RB_TA_ROW(4) + 4, RB_TA_LABEL_W, 16, 0);
    a->cb_algo = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"COMBOBOX", L"",
                           CBS_DROPDOWNLIST | WS_TABSTOP | WS_VSCROLL,
                           cx, RB_TA_ROW(4), fw, 120, RB_TA_ALGO);
    SendMessageW(a->cb_algo, CB_ADDSTRING, 0, (LPARAM)L"SHA-1");
    SendMessageW(a->cb_algo, CB_ADDSTRING, 0, (LPARAM)L"SHA-256");
    SendMessageW(a->cb_algo, CB_SETCURSEL, 0, 0);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Digits", 0,
              RB_TA_MARGIN, RB_TA_ROW(5) + 4, RB_TA_LABEL_W, 16, 0);
    a->cb_digits = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"COMBOBOX", L"",
                             CBS_DROPDOWNLIST | WS_TABSTOP | WS_VSCROLL,
                             cx, RB_TA_ROW(5), fw, 120, RB_TA_DIGITS);
    SendMessageW(a->cb_digits, CB_ADDSTRING, 0, (LPARAM)L"6");
    SendMessageW(a->cb_digits, CB_ADDSTRING, 0, (LPARAM)L"8");
    SendMessageW(a->cb_digits, CB_SETCURSEL, 0, 0);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"Period (seconds)",
              0, RB_TA_MARGIN, RB_TA_ROW(6) + 4, RB_TA_LABEL_W, 16, 0);
    a->e_period = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"EDIT", L"",
                            ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                            cx, RB_TA_ROW(6), 80, 24, RB_TA_PERIOD);
    rb_tt_set_text(a->e_period, "30");

    /* The inline error line, hidden until something is wrong with what
     * the user typed. */
    a->st_error = rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"STATIC", L"",
                            0, RB_TA_MARGIN, RB_TA_ROW(6) + 36,
                            rc.right - 2 * RB_TA_MARGIN, 32, RB_TA_ERROR);
    ShowWindow(a->st_error, SW_HIDE);

    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"BUTTON", L"OK",
              WS_TABSTOP | BS_DEFPUSHBUTTON,
              rc.right - RB_TA_MARGIN - 76 - RB_TA_GAP - 76,
              rc.bottom - RB_TA_MARGIN - RB_TA_BTNH, 76, RB_TA_BTNH,
              RB_TA_OK);
    rb_tt_ctl(a->dlg, t->app->hinst, a->fnt, L"BUTTON", L"Cancel",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TA_MARGIN - 76,
              rc.bottom - RB_TA_MARGIN - RB_TA_BTNH, 76, RB_TA_BTNH,
              RB_TA_CANCEL);

    EnableWindow(t->dlg, FALSE);
    ShowWindow(a->dlg, SW_SHOW);
    SetForegroundWindow(a->dlg);
}

/* ------------------------------------------------------------------ */
/* The backup prompt: one window for Export and Import, which differ only
 * in the confirmation field and in what OK does.  It asks for a full path
 * rather than opening a file dialog - comdlg32 is not linked, and the
 * codebase prefers a spelled-out field over a new link dependency. */

typedef struct {
    RbTotp *t;
    int is_export;
    HWND dlg;
    HWND e_path, e_pass, e_pass2, st_error;
    HFONT fnt;
} RbTotpPrompt;

static RbTotpPrompt *g_prompt = NULL;

static void rb_tp_error(RbTotpPrompt *p, const wchar_t *msg)
{
    if (p->st_error == NULL) return;
    if (msg == NULL) {
        ShowWindow(p->st_error, SW_HIDE);
    } else {
        SetWindowTextW(p->st_error, msg);
        ShowWindow(p->st_error, SW_SHOWNORMAL);
    }
}

static void rb_tp_close(RbTotpPrompt *p)
{
    RbTotp *t = p->t;

    g_prompt = NULL;
    if (p->dlg != NULL) DestroyWindow(p->dlg);
    EnableWindow(t->dlg, TRUE);
    SetForegroundWindow(t->dlg);
    if (p->fnt != NULL) DeleteObject(p->fnt);
    free(p);
}

/* Writes the blob as ONE line - the format is "RB2FA1 ..." and nothing
 * else; the trailing newline is what makes the file a text file a diff
 * tool can stand. */
static void rb_tp_export(RbTotpPrompt *p, const char *path, const char *pass)
{
    RbTotp *t = p->t;
    char *blob = NULL;
    FILE *f;

    if (!rb_totp_export_encrypted(t->store, pass, &blob) || blob == NULL) {
        rb_tp_error(p,
                    L"Error: enter a passphrase of at least one character.");
        return;
    }
    f = fopen(path, "wb");
    if (f == NULL) {
        free(blob);
        rb_tp_error(p,
                    L"Error: Windows would not open that file for writing.");
        return;
    }
    fprintf(f, "%s\n", blob);
    fclose(f);
    free(blob);
    rb_tp_close(p);
    MessageBoxW(t->dlg, L"The encrypted backup was written.", L"Export",
                MB_OK | MB_ICONINFORMATION);
}

static void rb_tp_import(RbTotpPrompt *p, const char *path, const char *pass)
{
    RbTotp *t = p->t;
    FILE *f;
    char *blob;
    long len;
    long got;
    int merged;

    f = fopen(path, "rb");
    if (f == NULL) {
        rb_tp_error(p, L"Error: Windows could not read that file.");
        return;
    }
    blob = NULL;
    len = 0;
    if (fseek(f, 0, SEEK_END) == 0) {
        len = ftell(f);
        fseek(f, 0, SEEK_SET);
    }
    /* A backup is one line; anything huge is not one.  The cap keeps a
     * mis-typed path (a video, an ISO) from being read whole. */
    if (len <= 0 || len > 16 * 1024 * 1024) {
        fclose(f);
        rb_tp_error(p, L"Error: that file is not a Room Browser backup.");
        return;
    }
    blob = (char *)malloc((size_t)len + 1);
    if (blob == NULL) {
        fclose(f);
        rb_tp_error(p, L"Error: the backup could not be read.");
        return;
    }
    got = (long)fread(blob, 1, (size_t)len, f);
    fclose(f);
    blob[got] = '\0';
    {
        char *nl = strpbrk(blob, "\r\n");
        if (nl != NULL) *nl = '\0';
    }

    merged = rb_totp_import_encrypted(t->store, blob, pass);
    free(blob);
    if (merged < 0) {
        /* Wrong passphrase or a damaged blob: the prompt stays up so the
         * passphrase can be corrected without starting over. */
        MessageBoxW(t->dlg, L"Wrong passphrase or damaged file",
                    L"Import failed", MB_OK | MB_ICONWARNING);
        return;
    }
    rb_tt_save(t);
    rb_tt_refill(t);
    rb_tp_close(p);
    if (merged > 0) {
        wchar_t msg[64];

        if (swprintf(msg, 64, L"Accounts imported: %d", merged) <= 0) {
            swprintf(msg, 64, L"Import finished.");
        }
        MessageBoxW(t->dlg, msg, L"Import", MB_OK | MB_ICONINFORMATION);
    } else {
        MessageBoxW(t->dlg,
                    L"Nothing new to import: every account was already there.",
                    L"Import", MB_OK | MB_ICONINFORMATION);
    }
}

static void rb_tp_ok(RbTotpPrompt *p)
{
    char *path;
    char *pass;
    char *pass2;

    path = rb_tt_get_text(p->e_path);
    pass = rb_tt_get_text(p->e_pass);
    pass2 = (p->e_pass2 != NULL) ? rb_tt_get_text(p->e_pass2) : rb_strdup("");

    if (path[0] == '\0') {
        rb_tp_error(p, L"Error: give the backup a file path.");
    } else if (p->is_export && strcmp(pass, pass2) != 0) {
        rb_tp_error(p, L"Error: the passphrases do not match.");
    } else if (p->is_export) {
        rb_tp_export(p, path, pass);
    } else {
        rb_tp_import(p, path, pass);
    }
    free(path);
    free(pass);
    free(pass2);
}

static LRESULT CALLBACK rb_tp_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbTotpPrompt *p =
        (RbTotpPrompt *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_ERASEBKGND:
        if (p != NULL) {
            RECT r;
            GetClientRect(hwnd, &r);
            FillRect((HDC)wp, &r, p->t->app->br_chrome);
            return 1;
        }
        break;
    case WM_CTLCOLORSTATIC:
    case WM_CTLCOLOREDIT:
    case WM_CTLCOLORBTN:
        if (p != NULL) {
            SetBkMode((HDC)wp, OPAQUE);
            SetBkColor((HDC)wp, rb_col(p->t->app->pal.background));
            SetTextColor((HDC)wp, rb_col(p->t->app->pal.text_primary));
            return (LRESULT)p->t->app->br_chrome;
        }
        break;
    case WM_KEYDOWN:
        if (p != NULL && wp == VK_ESCAPE) {
            rb_tp_close(p);
            return 0;
        }
        break;
    case WM_COMMAND:
        if (p != NULL) {
            int id = (int)LOWORD(wp);

            if (id == RB_TP_OK) { rb_tp_ok(p); return 0; }
            if (id == RB_TP_CANCEL) { rb_tp_close(p); return 0; }
        }
        break;
    case WM_CLOSE:
        if (p != NULL) { rb_tp_close(p); return 0; }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void rb_tp_show(RbTotp *t, int is_export)
{
    RbTotpPrompt *p;
    RECT want;
    RECT rc;
    const wchar_t *title;
    int h;
    int err_y;
    int btn_y;

    if (t == NULL || t->dlg == NULL) return;
    if (g_prompt != NULL) {
        SetForegroundWindow(g_prompt->dlg);
        return;
    }

    p = (RbTotpPrompt *)calloc(1, sizeof *p);
    if (p == NULL) return;
    p->t = t;
    p->is_export = is_export;
    p->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                         CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                         L"Segoe UI");

    {
        WNDCLASSEXW wc;
        memset(&wc, 0, sizeof wc);
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = rb_tp_proc;
        wc.hInstance = t->app->hinst;
        wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
        wc.hbrBackground = NULL;
        wc.lpszClassName = L"RoomBrowserTotpBackup";
        if (!RegisterClassExW(&wc) &&
            GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            if (p->fnt != NULL) DeleteObject(p->fnt);
            free(p);
            return;
        }
    }

    /* The layout is a vertical stack, so its height is computed here:
     * Import has one field fewer than Export and the window does not
     * carry an empty row for it. */
    err_y = RB_TA_TOP + 18 + 24 + RB_TA_GAP + 18 + 24 + RB_TA_GAP;
    if (is_export) {
        err_y += 18 + 24 + RB_TA_GAP;   /* the confirmation field */
    }
    h = err_y + 40 + RB_TA_GAP;
    btn_y = h;
    h += RB_TA_BTNH + RB_TA_MARGIN;

    want.left = 0;
    want.top = 0;
    want.right = RB_TA_WIN_W;
    want.bottom = h;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_prompt = p;
    title = is_export ? L"Export backup" : L"Import backup";
    p->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                             L"RoomBrowserTotpBackup", title,
                             WS_POPUP | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT,
                             want.right - want.left, want.bottom - want.top,
                             t->dlg, NULL, t->app->hinst, NULL);
    if (p->dlg == NULL) {
        rb_tp_close(p);
        return;
    }
    SetWindowLongPtrW(p->dlg, GWLP_USERDATA, (LONG_PTR)p);
    rb_apply_dark_titlebar(p->dlg);

    GetClientRect(p->dlg, &rc);
    rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"STATIC",
              L"File (full path)", 0,
              RB_TA_MARGIN, RB_TA_TOP, 200, 16, 0);
    p->e_path = rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"EDIT", L"",
                          ES_AUTOHSCROLL | WS_BORDER | WS_TABSTOP,
                          RB_TA_MARGIN, RB_TA_TOP + 18,
                          rc.right - 2 * RB_TA_MARGIN, 24, RB_TP_PATH);

    rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"STATIC", L"Passphrase", 0,
              RB_TA_MARGIN, RB_TA_TOP + 50, 200, 16, 0);
    p->e_pass = rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"EDIT", L"",
                          ES_PASSWORD | ES_AUTOHSCROLL | WS_BORDER |
                          WS_TABSTOP,
                          RB_TA_MARGIN, RB_TA_TOP + 68,
                          rc.right - 2 * RB_TA_MARGIN, 24, RB_TP_PASS);

    if (is_export) {
        rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"STATIC",
                  L"Confirm passphrase", 0,
                  RB_TA_MARGIN, RB_TA_TOP + 100, 200, 16, 0);
        p->e_pass2 = rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"EDIT", L"",
                               ES_PASSWORD | ES_AUTOHSCROLL | WS_BORDER |
                               WS_TABSTOP,
                               RB_TA_MARGIN, RB_TA_TOP + 118,
                               rc.right - 2 * RB_TA_MARGIN, 24, RB_TP_PASS2);
    } else {
        p->e_pass2 = NULL;
    }

    p->st_error = rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"STATIC", L"",
                            0, RB_TA_MARGIN, err_y,
                            rc.right - 2 * RB_TA_MARGIN, 32, RB_TP_ERROR);
    ShowWindow(p->st_error, SW_HIDE);

    rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"BUTTON",
              is_export ? L"Export" : L"Import",
              WS_TABSTOP | BS_DEFPUSHBUTTON,
              rc.right - RB_TA_MARGIN - 76 - RB_TA_GAP - 76, btn_y,
              76, RB_TA_BTNH, RB_TP_OK);
    rb_tt_ctl(p->dlg, t->app->hinst, p->fnt, L"BUTTON", L"Cancel",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TA_MARGIN - 76, btn_y, 76, RB_TA_BTNH,
              RB_TP_CANCEL);

    EnableWindow(t->dlg, FALSE);
    ShowWindow(p->dlg, SW_SHOW);
    SetForegroundWindow(p->dlg);
}

/* ------------------------------------------------------------------ */

static LRESULT CALLBACK rb_tt_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    RbTotp *d = (RbTotp *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

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
    case WM_TIMER:
        if (d != NULL && wp == RB_TT_TIMER) {
            rb_tt_refill(d);
            return 0;
        }
        break;
    case WM_KEYDOWN:
        /* Arrives only while the window itself - not a control - holds the
         * focus; see the keyboard note at the top of the file. */
        if (d != NULL && wp == VK_ESCAPE) {
            rb_tt_close(d);
            return 0;
        }
        break;
    case WM_COMMAND:
        if (d != NULL) {
            int id = (int)LOWORD(wp);

            if (id == RB_TT_ADD) { rb_ta_show(d); return 0; }
            if (id == RB_TT_COPY) { rb_tt_copy(d); return 0; }
            if (id == RB_TT_DEL) { rb_tt_delete(d); return 0; }
            if (id == RB_TT_EXPORT) { rb_tp_show(d, 1); return 0; }
            if (id == RB_TT_IMPORT) { rb_tp_show(d, 0); return 0; }
            if (id == RB_TT_CLOSE) { rb_tt_close(d); return 0; }
            if (id == RB_TT_LIST && HIWORD(wp) == LBN_SELCHANGE) {
                int i = (int)SendMessageW(d->list, LB_GETCURSEL, 0, 0);

                d->current_id = (i >= 0 && i < d->n_ids) ? d->ids[i] : 0;
                rb_tt_sync_actions(d);
                return 0;
            }
        }
        break;
    case WM_CLOSE:
        if (d != NULL) { rb_tt_close(d); return 0; }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void rb_show_totp(App *app)
{
    RbTotp *d;
    RECT want;
    RECT rc;
    int x;
    int y_btn;
    int list_h;

    if (app == NULL || app->hwnd == NULL) return;
    if (g_totp != NULL) {
        SetForegroundWindow(g_totp->dlg);
        return;
    }

    d = (RbTotp *)calloc(1, sizeof *d);
    if (d == NULL) return;
    d->app = app;

    /* Not at the profile's font scale, for the reason rb_show_prefs gives:
     * the layout is written against fixed metrics, and scaling the font
     * alone would move the rows out of a box that did not move with
     * them. */
    d->fnt = CreateFontW(-15, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                         OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                         CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE,
                         L"Segoe UI");

    {
        WNDCLASSEXW wc;
        memset(&wc, 0, sizeof wc);
        wc.cbSize = sizeof wc;
        wc.lpfnWndProc = rb_tt_proc;
        wc.hInstance = app->hinst;
        wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
        wc.hbrBackground = NULL;
        wc.lpszClassName = L"RoomBrowserTotp";
        if (!RegisterClassExW(&wc) &&
            GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
            if (d->fnt != NULL) DeleteObject(d->fnt);
            free(d);
            return;
        }
    }

    want.left = 0;
    want.top = 0;
    want.right = RB_TT_WIN_W;
    want.bottom = RB_TT_WIN_H;
    AdjustWindowRectEx(&want, WS_POPUP | WS_CAPTION | WS_SYSMENU, FALSE, 0);

    g_totp = d;
    d->dlg = CreateWindowExW(WS_EX_DLGMODALFRAME | WS_EX_CONTROLPARENT,
                             L"RoomBrowserTotp", L"2FA Management",
                             WS_POPUP | WS_CAPTION | WS_SYSMENU,
                             CW_USEDEFAULT, CW_USEDEFAULT,
                             want.right - want.left, want.bottom - want.top,
                             app->hwnd, NULL, app->hinst, NULL);
    if (d->dlg == NULL) {
        rb_tt_close(d);
        return;
    }
    SetWindowLongPtrW(d->dlg, GWLP_USERDATA, (LONG_PTR)d);
    rb_apply_dark_titlebar(d->dlg);

    /* The store belongs to the ACTIVE profile: loaded once here, written
     * through on every mutation, flushed on close and on a profile
     * switch. */
    d->path = rb_tt_store_path(app, "totp.jsonl");
    d->store = rb_totp_new();
    if (d->store != NULL && d->path != NULL) {
        rb_totp_load(d->store, d->path);
    }

    GetClientRect(d->dlg, &rc);
    y_btn = rc.bottom - RB_TT_MARGIN - RB_TT_BTNH;
    list_h = y_btn - RB_TT_GAP - RB_TT_MARGIN;

    d->list = rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"LISTBOX", L"",
                        WS_TABSTOP | WS_VSCROLL | WS_BORDER | LBS_NOTIFY |
                        LBS_NOINTEGRALHEIGHT,
                        RB_TT_MARGIN, RB_TT_MARGIN,
                        rc.right - 2 * RB_TT_MARGIN, list_h, RB_TT_LIST);

    /* The bottom row.  Add/Export/Import follow the store; Copy/Delete
     * follow the picked row, which is why they start disabled. */
    x = RB_TT_MARGIN;
    rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON", L"Add account...",
              WS_TABSTOP | BS_PUSHBUTTON, x, y_btn, 112, RB_TT_BTNH,
              RB_TT_ADD);
    x += 112 + RB_TT_GAP;
    d->b_copy = rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON",
                          L"Copy code", WS_TABSTOP | BS_PUSHBUTTON,
                          x, y_btn, 94, RB_TT_BTNH, RB_TT_COPY);
    x += 94 + RB_TT_GAP;
    d->b_del = rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON", L"Delete",
                         WS_TABSTOP | BS_PUSHBUTTON, x, y_btn, 76,
                         RB_TT_BTNH, RB_TT_DEL);
    rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON", L"Export...",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TT_MARGIN - 76 - RB_TT_GAP - 84 - RB_TT_GAP - 84,
              y_btn, 84, RB_TT_BTNH, RB_TT_EXPORT);
    rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON", L"Import...",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TT_MARGIN - 76 - RB_TT_GAP - 84,
              y_btn, 84, RB_TT_BTNH, RB_TT_IMPORT);
    rb_tt_ctl(d->dlg, app->hinst, d->fnt, L"BUTTON", L"Close",
              WS_TABSTOP | BS_PUSHBUTTON,
              rc.right - RB_TT_MARGIN - 76, y_btn, 76, RB_TT_BTNH,
              RB_TT_CLOSE);

    rb_tt_refill(d);

    EnableWindow(app->hwnd, FALSE);
    ShowWindow(d->dlg, SW_SHOW);
    SetForegroundWindow(d->dlg);
    /* The timer belongs to the window, so DestroyWindow in rb_tt_close
     * takes it down with the window; there is nothing to kill by hand. */
    SetTimer(d->dlg, RB_TT_TIMER, 1000, NULL);
}

/* The two hooks the profile switch calls, at the steps its protocol
 * already uses for the other per-profile state. */

void rb_totp_flush(App *app)
{
    (void)app;
    if (g_totp == NULL) return;
    /* Every mutation already wrote the file; the flush is the belt to
     * that braces, under the profile the window was opened for. */
    rb_tt_save(g_totp);
}

void rb_totp_reload(App *app)
{
    RbTotp *d = g_totp;

    (void)app;
    if (d == NULL) return;
    /* The old profile's copy dies here: what the window shows from now on
     * is the new profile's file, the same moment the rest of the
     * profile's state is re-read. */
    if (d->store != NULL) rb_totp_free(d->store);
    d->store = rb_totp_new();
    free(d->path);
    d->path = rb_tt_store_path(d->app, "totp.jsonl");
    if (d->store != NULL && d->path != NULL) {
        rb_totp_load(d->store, d->path);
    }
    d->current_id = 0;
    rb_tt_refill(d);
}
