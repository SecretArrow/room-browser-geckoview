/*
 * Room Browser (desktop) - GTK 2FA (TOTP) window.
 *
 * The 2FA manager over the ACTIVE profile's totp.jsonl, in the same
 * shape as the Downloads window: one instance, and opening it again
 * presents the one that is open.  It is deliberately NOT modal - a modal
 * dialog would make "present it again" unreachable, because the menu
 * that opens it lives behind the grab.  The profile-switch lifecycle
 * mirrors the downloads one: flush while the old profile is still the
 * destination, reload next to the step that re-reads everything else the
 * new profile owns (see rb_do_switch_profile in chrome.c).
 *
 * The window file path is built the same way the chrome builds
 * history.jsonl's (rb_paths_data_dir plus a join), re-resolved on every
 * load and save so the window carries no profile state of its own.
 *
 * Rows carry a live code, recomputed once a second by updating the rows
 * in place - never rebuilt while the clock moves, which is what keeps
 * the selection put.  Secrets never leave the core store except through
 * rb_totp_export_encrypted.
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>

#include "chrome.h"
#include "totp.h"

/* One window, so one instance of its state.  g_totp == NULL whenever the
 * window is closed; every hook below checks it first. */
typedef struct {
    App *app;
    GtkWidget *win;
    GtkWidget *stack;       /* list / empty placeholder */
    GtkWidget *list;        /* GtkListBox; row order == store order */
    GtkWidget *btn_copy;
    GtkWidget *btn_del;
    rb_totp_store *store;
    char *path;             /* the active profile's totp.jsonl */
    guint timer;            /* the once-a-second refresh */
    int silent;             /* suppress selection handling while refilling */
} RbTotpWin;

static RbTotpWin *g_totp = NULL;

static long long rb_totp_now_s(void)
{
    return (long long)(g_get_real_time() / G_USEC_PER_SEC);
}

static long long rb_totp_now_ms(void)
{
    return (long long)g_get_real_time() / 1000LL;
}

/* The active profile's totp.jsonl, built the same way the chrome builds
 * history.jsonl's path: rb_paths_data_dir() plus a join.  Resolved fresh
 * on every load and save, so the window never carries profile state of
 * its own. */
static char *rb_totp_path(void)
{
    char *dir = rb_paths_data_dir();
    char *p;

    if (dir == NULL) return NULL;
    p = rb_paths_join(dir, "totp.jsonl");
    rb_paths_free(dir);
    return p;
}

/* "issuer — label" for the left of the row, with the same fallbacks the
 * Android edition uses: either half may be empty. */
static char *rb_totp_row_name(const rb_totp_account *a)
{
    const char *iss = (a->issuer != NULL) ? a->issuer : "";
    const char *lbl = (a->label != NULL) ? a->label : "";

    if (iss[0] != '\0' && lbl[0] != '\0') {
        return g_strdup_printf("%s — %s", iss, lbl);
    }
    if (iss[0] != '\0') return g_strdup(iss);
    if (lbl[0] != '\0') return g_strdup(lbl);
    return g_strdup("(unnamed account)");
}

/* "123456" prints as "123 456"; 8 digits split 4/4.  Codes shorter than
 * five pass through unsplit - there is no honest midpoint to split at. */
static void rb_totp_group(const char *code, char *out, size_t cap)
{
    size_t n = strlen(code);

    if (n < 5) {
        snprintf(out, cap, "%s", code);
        return;
    }
    snprintf(out, cap, "%.*s %s", (int)(n / 2), code, code + n / 2);
}

/* The current code of one account, grouped for display.  A failure is
 * shown, not swallowed: "(error)" beats a silently stale number. */
static void rb_totp_code_text(const rb_totp_account *a, long long now,
                              char *out, size_t cap)
{
    char code[16];   /* the core writes digits ASCII + NUL, digits <= 10 */

    if (rb_totp_code(a->secret, a->secret_len, a->algo, a->digits,
                     a->period, now, code) == 1) {
        rb_totp_group(code, out, cap);
    } else {
        snprintf(out, cap, "(error)");
    }
}

/* Recomputes one row's figures.  The tick walks every row through here,
 * which is how the selection survives: the rows are never rebuilt while
 * the clock moves. */
static void rb_totp_refresh_row(GtkWidget *row, const rb_totp_account *a,
                                long long now)
{
    GtkLabel *code = GTK_LABEL(g_object_get_data(G_OBJECT(row), "rb-code"));
    GtkLabel *remain = GTK_LABEL(g_object_get_data(G_OBJECT(row), "rb-remain"));
    char grouped[16];
    char txt[16];
    int secs;

    if (code == NULL || remain == NULL || a == NULL) return;
    rb_totp_code_text(a, now, grouped, sizeof grouped);
    gtk_label_set_text(code, grouped);
    secs = rb_totp_seconds_remaining(a->period, now);
    snprintf(txt, sizeof txt, "%ds", secs);
    gtk_label_set_text(remain, txt);
}

static void rb_totp_select_id(RbTotpWin *st, long id)
{
    int i;

    for (i = 0; ; i++) {
        GtkListBoxRow *row =
            gtk_list_box_get_row_at_index(GTK_LIST_BOX(st->list), i);

        if (row == NULL) return;
        if ((long)GPOINTER_TO_SIZE(
                g_object_get_data(G_OBJECT(row), "rb-id")) == id) {
            gtk_list_box_select_row(GTK_LIST_BOX(st->list), row);
            return;
        }
    }
}

/* A button that would do nothing is insensitive rather than hidden, so
 * the row of actions stays put - same rule as the downloads window. */
static void rb_totp_sync_actions(RbTotpWin *st)
{
    GtkListBoxRow *row =
        gtk_list_box_get_selected_row(GTK_LIST_BOX(st->list));
    int have = (row != NULL);

    gtk_widget_set_sensitive(st->btn_copy, have);
    gtk_widget_set_sensitive(st->btn_del, have);
}

static GtkWidget *rb_totp_build_row(const rb_totp_account *a)
{
    GtkWidget *row, *box, *name, *code, *remain;
    char *text;
    char grouped[16];
    char txt[16];
    int secs;

    row = gtk_list_box_row_new();
    box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_widget_set_margin_start(box, 6);
    gtk_widget_set_margin_end(box, 6);
    gtk_widget_set_margin_top(box, 4);
    gtk_widget_set_margin_bottom(box, 4);
    gtk_container_add(GTK_CONTAINER(row), box);

    text = rb_totp_row_name(a);
    name = gtk_label_new(text);
    g_free(text);
    gtk_widget_set_halign(name, GTK_ALIGN_START);
    gtk_label_set_ellipsize(GTK_LABEL(name), PANGO_ELLIPSIZE_END);
    gtk_box_pack_start(GTK_BOX(box), name, TRUE, TRUE, 0);

    code = gtk_label_new(NULL);
    gtk_label_set_width_chars(GTK_LABEL(code), 12);
    gtk_widget_set_halign(code, GTK_ALIGN_END);
    gtk_box_pack_start(GTK_BOX(box), code, FALSE, FALSE, 0);

    remain = gtk_label_new(NULL);
    gtk_label_set_width_chars(GTK_LABEL(remain), 5);
    gtk_widget_set_halign(remain, GTK_ALIGN_END);
    gtk_box_pack_start(GTK_BOX(box), remain, FALSE, FALSE, 0);

    g_object_set_data(G_OBJECT(row), "rb-id",
                      GSIZE_TO_POINTER((gsize)a->id));
    g_object_set_data(G_OBJECT(row), "rb-code", code);
    g_object_set_data(G_OBJECT(row), "rb-remain", remain);
    rb_totp_code_text(a, rb_totp_now_s(), grouped, sizeof grouped);
    gtk_label_set_text(GTK_LABEL(code), grouped);
    secs = rb_totp_seconds_remaining(a->period, rb_totp_now_s());
    snprintf(txt, sizeof txt, "%ds", secs);
    gtk_label_set_text(GTK_LABEL(remain), txt);
    return row;
}

/* Rebuilds the rows.  The selection follows the row it was on when its
 * id survives the rebuild (an add or an import), and drops when it does
 * not (a delete or a reload). */
static void rb_totp_refill(RbTotpWin *st)
{
    long keep = 0;
    GtkListBoxRow *sel;
    int n, i;

    st->silent = 1;
    sel = gtk_list_box_get_selected_row(GTK_LIST_BOX(st->list));
    if (sel != NULL) {
        keep = (long)GPOINTER_TO_SIZE(
            g_object_get_data(G_OBJECT(sel), "rb-id"));
    }
    gtk_container_foreach(GTK_CONTAINER(st->list),
                          (GtkCallback)gtk_widget_destroy, NULL);

    n = rb_totp_count(st->store);
    for (i = 0; i < n; i++) {
        const rb_totp_account *a = rb_totp_at(st->store, i);
        GtkWidget *row = rb_totp_build_row(a);

        gtk_container_add(GTK_CONTAINER(st->list), row);
        gtk_widget_show_all(row);
    }
    gtk_stack_set_visible_child_name(GTK_STACK(st->stack),
                                     (n == 0) ? "empty" : "list");
    if (keep != 0) rb_totp_select_id(st, keep);
    st->silent = 0;
    rb_totp_sync_actions(st);
}

static void on_totp_selection_changed(GtkListBox *box, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;

    (void)box;
    if (st->silent) return;
    rb_totp_sync_actions(st);
}

/* Once a second: recompute every visible code.  Rows are updated in
 * place, never rebuilt - that is what keeps the selection put while the
 * clock moves. */
static gboolean on_totp_tick(gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    long long now = rb_totp_now_s();
    int n = rb_totp_count(st->store);
    int i;

    for (i = 0; i < n; i++) {
        GtkWidget *row =
            gtk_list_box_get_row_at_index(GTK_LIST_BOX(st->list), i);

        if (row == NULL) break;   /* the list lags the store only mid-rebuild */
        rb_totp_refresh_row(row, rb_totp_at(st->store, i), now);
    }
    return G_SOURCE_CONTINUE;
}

static void rb_totp_msg(RbTotpWin *st, GtkMessageType type,
                        const char *title, const char *body)
{
    GtkWidget *dlg = gtk_message_dialog_new(GTK_WINDOW(st->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        type, GTK_BUTTONS_CLOSE, "%s", title);

    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dlg), "%s",
                                             body);
    g_signal_connect_swapped(dlg, "response", G_CALLBACK(gtk_widget_destroy),
                             dlg);
    gtk_widget_show(dlg);
}

static int rb_totp_confirm(RbTotpWin *st, const char *title,
                           const char *body)
{
    GtkWidget *dlg = gtk_message_dialog_new(GTK_WINDOW(st->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        GTK_MESSAGE_WARNING, GTK_BUTTONS_YES_NO, "%s", title);
    int rc;

    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dlg), "%s",
                                             body);
    rc = gtk_dialog_run(GTK_DIALOG(dlg));
    gtk_widget_destroy(dlg);
    return rc == GTK_RESPONSE_YES;
}

/* The selected account, or NULL.  Read through the id on the row rather
 * than a pointer into the store, which a refill could have moved. */
static const rb_totp_account *rb_totp_selected(RbTotpWin *st)
{
    GtkListBoxRow *row =
        gtk_list_box_get_selected_row(GTK_LIST_BOX(st->list));

    if (row == NULL) return NULL;
    return rb_totp_get(st->store,
                       (long)GPOINTER_TO_SIZE(
                           g_object_get_data(G_OBJECT(row), "rb-id")));
}

static void on_totp_copy_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    const rb_totp_account *a = rb_totp_selected(st);
    GtkClipboard *cb;
    char grouped[16];

    (void)button;
    if (a == NULL) return;
    rb_totp_code_text(a, rb_totp_now_s(), grouped, sizeof grouped);
    cb = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
    gtk_clipboard_set_text(cb, grouped, -1);
    /* Asks the clipboard manager to keep it past this process, which is
     * what the downloads window's "Copy link" does too. */
    gtk_clipboard_store(cb);
}

static void on_totp_delete_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    const rb_totp_account *a = rb_totp_selected(st);
    char *name, *body;

    (void)button;
    if (a == NULL) return;
    name = rb_totp_row_name(a);
    body = g_strdup_printf("\"%s\" will stop generating codes here.", name);
    g_free(name);
    if (!rb_totp_confirm(st, "Delete this account?", body)) {
        g_free(body);
        return;
    }
    g_free(body);
    rb_totp_remove(st->store, a->id);
    if (st->path != NULL) rb_totp_save(st->store, st->path);
    rb_totp_refill(st);
}

/* The file the user picked (g_free it), or NULL when the chooser was
 * dismissed. */
static char *rb_totp_choose_file(RbTotpWin *st, int saving)
{
    GtkWidget *dlg;
    char *path = NULL;

    dlg = gtk_file_chooser_dialog_new(
        (saving != 0) ? "Export accounts" : "Import accounts",
        GTK_WINDOW(st->win),
        (saving != 0) ? GTK_FILE_CHOOSER_ACTION_SAVE
                      : GTK_FILE_CHOOSER_ACTION_OPEN,
        "_Cancel", GTK_RESPONSE_CANCEL,
        (saving != 0) ? "_Save" : "_Open", GTK_RESPONSE_ACCEPT, NULL);
    gtk_file_chooser_set_do_overwrite_confirmation(GTK_FILE_CHOOSER(dlg),
                                                   TRUE);
    if (gtk_dialog_run(GTK_DIALOG(dlg)) == GTK_RESPONSE_ACCEPT) {
        path = gtk_file_chooser_get_filename(GTK_FILE_CHOOSER(dlg));
    }
    gtk_widget_destroy(dlg);
    return path;
}

static void on_totp_pass_toggle(GtkToggleButton *btn, gpointer user_data)
{
    gboolean on = gtk_toggle_button_get_active(btn);

    (void)user_data;
    gtk_entry_set_visibility(
        GTK_ENTRY(g_object_get_data(G_OBJECT(btn), "rb-a")), on);
    gtk_entry_set_visibility(
        GTK_ENTRY(g_object_get_data(G_OBJECT(btn), "rb-b")), on);
}

/* Asks for a passphrase twice and only comes back when the two agree (or
 * the user cancels).  A mismatch is an inline label, not a restart. */
static char *rb_totp_ask_passphrase(RbTotpWin *st, const char *title)
{
    GtkWidget *dlg, *grid, *lbl, *e1, *e2, *toggle, *err;
    char *out = NULL;

    dlg = gtk_dialog_new_with_buttons(title, GTK_WINDOW(st->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        "_Cancel", GTK_RESPONSE_CANCEL, "_OK", GTK_RESPONSE_OK, NULL);
    gtk_dialog_set_default_response(GTK_DIALOG(dlg), GTK_RESPONSE_OK);

    grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 6);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 8);
    gtk_container_set_border_width(GTK_CONTAINER(grid), 8);
    gtk_box_pack_start(
        GTK_BOX(gtk_dialog_get_content_area(GTK_DIALOG(dlg))), grid,
        TRUE, TRUE, 0);

    lbl = gtk_label_new("Passphrase");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 0, 1, 1);
    e1 = gtk_entry_new();
    gtk_entry_set_visibility(GTK_ENTRY(e1), FALSE);
    gtk_widget_set_hexpand(e1, TRUE);
    gtk_grid_attach(GTK_GRID(grid), e1, 1, 0, 1, 1);

    lbl = gtk_label_new("Repeat it");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 1, 1, 1);
    e2 = gtk_entry_new();
    gtk_entry_set_visibility(GTK_ENTRY(e2), FALSE);
    gtk_widget_set_hexpand(e2, TRUE);
    gtk_grid_attach(GTK_GRID(grid), e2, 1, 1, 1, 1);

    toggle = gtk_check_button_new_with_label("Show passwords");
    g_object_set_data(G_OBJECT(toggle), "rb-a", e1);
    g_object_set_data(G_OBJECT(toggle), "rb-b", e2);
    g_signal_connect(toggle, "toggled", G_CALLBACK(on_totp_pass_toggle), NULL);
    gtk_grid_attach(GTK_GRID(grid), toggle, 1, 2, 1, 1);

    err = gtk_label_new(NULL);
    gtk_widget_set_halign(err, GTK_ALIGN_START);
    gtk_grid_attach(GTK_GRID(grid), err, 1, 3, 1, 1);

    for (;;) {
        const char *a, *b;
        int rc = gtk_dialog_run(GTK_DIALOG(dlg));

        if (rc != GTK_RESPONSE_OK) break;
        a = gtk_entry_get_text(GTK_ENTRY(e1));
        b = gtk_entry_get_text(GTK_ENTRY(e2));
        if (a[0] == '\0') {
            gtk_label_set_text(GTK_LABEL(err), "Enter a passphrase.");
            continue;
        }
        if (strcmp(a, b) != 0) {
            gtk_label_set_text(GTK_LABEL(err),
                               "The passphrases do not match.");
            continue;
        }
        out = g_strdup(a);
        break;
    }
    gtk_widget_destroy(dlg);
    return out;
}

/* One widget pointer per field of the add dialog; the entries outlive
 * the struct (it lives on the stack of the open dialog). */
typedef struct {
    GtkWidget *issuer, *label, *secret, *uri, *algo, *digits, *period, *err;
} RbTotpAdd;

static void on_totp_fill_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpAdd *w = (RbTotpAdd *)user_data;
    const char *uri = gtk_entry_get_text(GTK_ENTRY(w->uri));
    rb_totp_pending p;
    char *b32;
    size_t cap;

    (void)button;
    memset(&p, 0, sizeof p);
    if (rb_totp_parse_uri(uri, &p) != 1) {
        rb_totp_pending_free(&p);   /* safe on the zeroed struct */
        gtk_label_set_text(GTK_LABEL(w->err),
                           "That is not a valid otpauth:// URI.");
        return;
    }
    gtk_entry_set_text(GTK_ENTRY(w->issuer),
                       (p.issuer != NULL) ? p.issuer : "");
    gtk_entry_set_text(GTK_ENTRY(w->label),
                       (p.label != NULL) ? p.label : "");
    /* The URI carries the secret Base32 and so does the entry: encode it
     * back, so the Add button sees one secret format no matter where the
     * row came from. */
    cap = (p.secret_len * 8 + 4) / 5 + 1;
    b32 = (char *)g_malloc(cap);
    if (rb_base32_encode(p.secret, p.secret_len, b32, cap) == 1) {
        gtk_entry_set_text(GTK_ENTRY(w->secret), b32);
        gtk_combo_box_set_active(GTK_COMBO_BOX(w->algo),
                                 (p.algo == RB_TOTP_SHA256) ? 1 : 0);
        gtk_combo_box_set_active(GTK_COMBO_BOX(w->digits),
                                 (p.digits == 8) ? 1 : 0);
        gtk_spin_button_set_value(GTK_SPIN_BUTTON(w->period),
                                  (double)p.period);
        gtk_label_set_text(GTK_LABEL(w->err), "Filled from the URI.");
    } else {
        gtk_label_set_text(GTK_LABEL(w->err),
                           "The URI's secret could not be shown.");
    }
    g_free(b32);
    rb_totp_pending_free(&p);
}

static void on_totp_add_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    GtkWidget *dlg, *grid, *lbl, *urirow, *fill;
    RbTotpAdd w;

    (void)button;
    dlg = gtk_dialog_new_with_buttons("Add account", GTK_WINDOW(st->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        "_Cancel", GTK_RESPONSE_CANCEL, "_Add", GTK_RESPONSE_OK, NULL);
    gtk_dialog_set_default_response(GTK_DIALOG(dlg), GTK_RESPONSE_OK);

    grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 6);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 8);
    gtk_container_set_border_width(GTK_CONTAINER(grid), 8);
    gtk_box_pack_start(
        GTK_BOX(gtk_dialog_get_content_area(GTK_DIALOG(dlg))), grid,
        TRUE, TRUE, 0);

    lbl = gtk_label_new("Issuer");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 0, 1, 1);
    w.issuer = gtk_entry_new();
    gtk_widget_set_hexpand(w.issuer, TRUE);
    gtk_grid_attach(GTK_GRID(grid), w.issuer, 1, 0, 1, 1);

    lbl = gtk_label_new("Label");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 1, 1, 1);
    w.label = gtk_entry_new();
    gtk_widget_set_hexpand(w.label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), w.label, 1, 1, 1, 1);

    lbl = gtk_label_new("Secret (Base32)");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 2, 1, 1);
    w.secret = gtk_entry_new();
    gtk_widget_set_hexpand(w.secret, TRUE);
    gtk_grid_attach(GTK_GRID(grid), w.secret, 1, 2, 1, 1);

    lbl = gtk_label_new("otpauth URI");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 3, 1, 1);
    urirow = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    w.uri = gtk_entry_new();
    gtk_widget_set_hexpand(w.uri, TRUE);
    gtk_box_pack_start(GTK_BOX(urirow), w.uri, TRUE, TRUE, 0);
    fill = gtk_button_new_with_label("Fill");
    gtk_widget_set_tooltip_text(fill,
                                "Fill the fields from a pasted otpauth:// URI");
    gtk_box_pack_start(GTK_BOX(urirow), fill, FALSE, FALSE, 0);
    gtk_grid_attach(GTK_GRID(grid), urirow, 1, 3, 1, 1);

    lbl = gtk_label_new("Algorithm");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 4, 1, 1);
    w.algo = gtk_combo_box_text_new();
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(w.algo), "SHA-1");
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(w.algo), "SHA-256");
    gtk_combo_box_set_active(GTK_COMBO_BOX(w.algo), 0);
    gtk_grid_attach(GTK_GRID(grid), w.algo, 1, 4, 1, 1);

    lbl = gtk_label_new("Digits");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 5, 1, 1);
    w.digits = gtk_combo_box_text_new();
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(w.digits), "6");
    gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(w.digits), "8");
    gtk_combo_box_set_active(GTK_COMBO_BOX(w.digits), 0);
    gtk_grid_attach(GTK_GRID(grid), w.digits, 1, 5, 1, 1);

    lbl = gtk_label_new("Period (seconds)");
    gtk_widget_set_halign(lbl, GTK_ALIGN_END);
    gtk_grid_attach(GTK_GRID(grid), lbl, 0, 6, 1, 1);
    w.period = gtk_spin_button_new_with_range(1.0, 86400.0, 1.0);
    gtk_spin_button_set_value(GTK_SPIN_BUTTON(w.period),
                              (double)RB_TOTP_DEFAULT_PERIOD);
    gtk_grid_attach(GTK_GRID(grid), w.period, 1, 6, 1, 1);

    w.err = gtk_label_new(NULL);
    gtk_widget_set_halign(w.err, GTK_ALIGN_START);
    gtk_grid_attach(GTK_GRID(grid), w.err, 1, 7, 1, 1);

    g_signal_connect(fill, "clicked", G_CALLBACK(on_totp_fill_clicked), &w);

    for (;;) {
        const char *b32;
        int need;
        unsigned char *secret;
        long id;
        int rc = gtk_dialog_run(GTK_DIALOG(dlg));

        if (rc != GTK_RESPONSE_OK) break;
        b32 = gtk_entry_get_text(GTK_ENTRY(w.secret));
        need = rb_base32_decode(b32, NULL, 0);   /* measure first */
        if (need <= 0) {
            gtk_label_set_text(GTK_LABEL(w.err),
                               "The secret is not valid Base32.");
            continue;
        }
        secret = (unsigned char *)g_malloc((size_t)need);
        if (rb_base32_decode(b32, secret, (size_t)need) != need) {
            g_free(secret);
            gtk_label_set_text(GTK_LABEL(w.err),
                               "The secret is not valid Base32.");
            continue;
        }
        id = rb_totp_add(st->store,
                         gtk_entry_get_text(GTK_ENTRY(w.issuer)),
                         gtk_entry_get_text(GTK_ENTRY(w.label)),
                         secret, (size_t)need,
                         (gtk_combo_box_get_active(GTK_COMBO_BOX(w.algo)) == 1)
                             ? RB_TOTP_SHA256 : RB_TOTP_SHA1,
                         (gtk_combo_box_get_active(GTK_COMBO_BOX(w.digits)) == 1)
                             ? 8 : 6,
                         gtk_spin_button_get_value_as_int(
                             GTK_SPIN_BUTTON(w.period)),
                         rb_totp_now_ms());
        g_free(secret);
        if (id <= 0) {
            gtk_label_set_text(GTK_LABEL(w.err),
                               "Could not add the account.");
            continue;
        }
        if (st->path != NULL) rb_totp_save(st->store, st->path);
        gtk_widget_destroy(dlg);
        rb_totp_refill(st);
        rb_totp_select_id(st, id);
        return;
    }
    gtk_widget_destroy(dlg);
}

static void on_totp_export_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    char *path, *pass, *blob = NULL, *done = NULL;
    FILE *f;

    (void)button;
    if (rb_totp_count(st->store) == 0) {
        rb_totp_msg(st, GTK_MESSAGE_INFO, "Nothing to export",
                    "There are no accounts to write.");
        return;
    }
    path = rb_totp_choose_file(st, 1);
    if (path == NULL) return;
    pass = rb_totp_ask_passphrase(st, "Choose an export passphrase");
    if (pass == NULL) {
        g_free(path);
        return;
    }

    /* The blob is one line by construction; the newline is the file's
     * own, and the import strips it again. */
    if (rb_totp_export_encrypted(st->store, pass, &blob) == 1 &&
        blob != NULL) {
        f = fopen(path, "w");
        if (f == NULL) {
            rb_totp_msg(st, GTK_MESSAGE_WARNING, "Could not write the file",
                        strerror(errno));
        } else {
            fputs(blob, f);
            fputc('\n', f);
            if (fclose(f) != 0) {
                rb_totp_msg(st, GTK_MESSAGE_WARNING,
                            "Could not write the file", strerror(errno));
            } else {
                done = g_strdup_printf("%d account%s written to\n%s",
                                       rb_totp_count(st->store),
                                       (rb_totp_count(st->store) == 1) ? ""
                                                                       : "s",
                                       path);
                rb_totp_msg(st, GTK_MESSAGE_INFO, "Accounts exported", done);
                g_free(done);
            }
        }
    } else {
        rb_totp_msg(st, GTK_MESSAGE_WARNING, "Export failed",
                    "The accounts could not be encrypted.");
    }
    g_free(blob);
    g_free(pass);
    g_free(path);
}

static void on_totp_import_clicked(GtkButton *button, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;
    char *path, *pass, *data = NULL;
    int merged;

    (void)button;
    path = rb_totp_choose_file(st, 0);
    if (path == NULL) return;
    if (!g_file_get_contents(path, &data, NULL, NULL)) {
        rb_totp_msg(st, GTK_MESSAGE_WARNING, "Could not read the file", path);
        g_free(path);
        return;
    }
    pass = rb_totp_ask_passphrase(st, "Import passphrase");
    if (pass == NULL) {
        g_free(data);
        g_free(path);
        return;
    }

    /* The file carries one trailing newline (it is how the export ends);
     * g_strchomp takes it off before the tag check sees the blob. */
    g_strchomp(data);
    merged = rb_totp_import_encrypted(st->store, data, pass);
    if (merged < 0) {
        rb_totp_msg(st, GTK_MESSAGE_ERROR, "Import failed",
                    "Wrong passphrase or damaged file");
    } else {
        if (st->path != NULL) rb_totp_save(st->store, st->path);
        rb_totp_refill(st);
        if (merged == 0) {
            rb_totp_msg(st, GTK_MESSAGE_INFO, "Nothing to import",
                        "Every account in the file is already here.");
        } else {
            char *done =
                g_strdup_printf("%d account%s merged.", merged,
                                (merged == 1) ? "" : "s");
            rb_totp_msg(st, GTK_MESSAGE_INFO, "Accounts imported", done);
            g_free(done);
        }
    }
    g_free(pass);
    g_free(data);
    g_free(path);
}

static void on_totp_response(GtkWidget *win, int response, gpointer user_data)
{
    (void)user_data;
    (void)response;
    gtk_widget_destroy(win);
}

static void on_totp_destroy(GtkWidget *widget, gpointer user_data)
{
    RbTotpWin *st = (RbTotpWin *)user_data;

    (void)widget;
    /* The timer holds a pointer to this state, so it has to be gone before
     * the state is: otherwise the next tick rewrites labels belonging to a
     * list that no longer exists.  Same order as the downloads teardown. */
    if (st->timer != 0) {
        g_source_remove(st->timer);
        st->timer = 0;
    }
    rb_totp_free(st->store);
    g_free(st->path);
    g_free(st);
    g_totp = NULL;
}

int rb_open_totp_window(App *app)
{
    RbTotpWin *st;
    GtkWidget *content, *box, *scroll, *empty, *actions;
    GtkWidget *add_btn, *export_btn, *import_btn;

    if (g_totp != NULL) {
        gtk_window_present(GTK_WINDOW(g_totp->win));
        return 0;
    }
    if (app == NULL) return -1;

    st = g_new0(RbTotpWin, 1);
    st->app = app;
    st->path = rb_totp_path();
    st->store = rb_totp_new();
    if (st->store == NULL) {
        g_free(st->path);
        g_free(st);
        return -1;
    }
    if (st->path != NULL) rb_totp_load(st->store, st->path);

    st->win = gtk_dialog_new_with_buttons("2FA Management",
        GTK_WINDOW(app->win), GTK_DIALOG_DESTROY_WITH_PARENT,
        "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(st->win), 560, 420);

    box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_container_set_border_width(GTK_CONTAINER(box), 8);
    content = gtk_dialog_get_content_area(GTK_DIALOG(st->win));
    gtk_box_pack_start(GTK_BOX(content), box, TRUE, TRUE, 0);

    st->list = gtk_list_box_new();
    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(scroll), st->list);

    empty = gtk_label_new("(no accounts yet)");
    gtk_widget_set_halign(empty, GTK_ALIGN_CENTER);
    gtk_widget_set_valign(empty, GTK_ALIGN_CENTER);
    st->stack = gtk_stack_new();
    gtk_stack_set_transition_type(GTK_STACK(st->stack),
                                  GTK_STACK_TRANSITION_TYPE_NONE);
    gtk_stack_add_named(GTK_STACK(st->stack), scroll, "list");
    gtk_stack_add_named(GTK_STACK(st->stack), empty, "empty");
    gtk_box_pack_start(GTK_BOX(box), st->stack, TRUE, TRUE, 0);

    actions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    add_btn = gtk_button_new_with_label("Add account…");
    st->btn_copy = gtk_button_new_with_label("Copy code");
    st->btn_del = gtk_button_new_with_label("Delete");
    export_btn = gtk_button_new_with_label("Export…");
    import_btn = gtk_button_new_with_label("Import…");
    gtk_widget_set_tooltip_text(st->btn_copy,
                                "Copy the selected account's current code");
    gtk_widget_set_tooltip_text(st->btn_del,
                                "Remove the selected account (asks first)");
    gtk_widget_set_tooltip_text(export_btn,
                                "Write an encrypted backup of the accounts");
    gtk_widget_set_tooltip_text(import_btn,
                                "Merge an encrypted backup into the list");
    gtk_box_pack_start(GTK_BOX(actions), add_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), st->btn_copy, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), st->btn_del, FALSE, FALSE, 0);
    gtk_box_pack_end(GTK_BOX(actions), import_btn, FALSE, FALSE, 0);
    gtk_box_pack_end(GTK_BOX(actions), export_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), actions, FALSE, FALSE, 0);

    g_signal_connect(st->list, "selected-rows-changed",
                     G_CALLBACK(on_totp_selection_changed), st);
    g_signal_connect(add_btn, "clicked", G_CALLBACK(on_totp_add_clicked), st);
    g_signal_connect(st->btn_copy, "clicked",
                     G_CALLBACK(on_totp_copy_clicked), st);
    g_signal_connect(st->btn_del, "clicked",
                     G_CALLBACK(on_totp_delete_clicked), st);
    g_signal_connect(export_btn, "clicked",
                     G_CALLBACK(on_totp_export_clicked), st);
    g_signal_connect(import_btn, "clicked",
                     G_CALLBACK(on_totp_import_clicked), st);
    g_signal_connect(st->win, "response", G_CALLBACK(on_totp_response), st);
    g_signal_connect(st->win, "destroy", G_CALLBACK(on_totp_destroy), st);

    g_totp = st;
    rb_totp_refill(st);
    st->timer = g_timeout_add_seconds(1, on_totp_tick, st);
    gtk_widget_show_all(st->win);
    return 0;
}

/* The profile-switch protocol, matching the Windows edition one for one.
 * The store is already saved on every mutation, so this is insurance;
 * the reload below is the part that carries state across. */
void rb_totp_flush(App *app)
{
    RbTotpWin *st = g_totp;

    (void)app;
    if (st == NULL || st->path == NULL) return;
    rb_totp_save(st->store, st->path);
}

void rb_totp_reload(App *app)
{
    RbTotpWin *st = g_totp;

    (void)app;
    if (st == NULL) return;
    g_free(st->path);
    st->path = rb_totp_path();
    rb_totp_free(st->store);
    st->store = rb_totp_new();
    if (st->store != NULL && st->path != NULL) {
        rb_totp_load(st->store, st->path);
    }
    rb_totp_refill(st);
}
