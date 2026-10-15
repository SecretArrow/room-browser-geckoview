/*
 * Room Browser (desktop) - GTK notes window.
 *
 * The notes manager over the ACTIVE profile's notes.jsonl, in the same
 * shape as the Downloads window: one instance, and opening it again
 * presents the one that is open.  The window is deliberately NOT modal —
 * a modal dialog would make "present it again" unreachable, since the
 * menu that opens it lives behind the grab.  The profile-switch
 * lifecycle mirrors the downloads one: flush while the old profile is
 * still the destination, reload next to the step that re-reads
 * everything else the new profile owns (see rb_do_switch_profile in
 * chrome.c).
 *
 * The file path is built the same way the chrome builds history.jsonl's
 * (rb_paths_data_dir plus a join), but re-resolved on every load and
 * save rather than remembered from start-up, so a switch needs no
 * bookkeeping here.
 *
 * The editor commits on selection switches, on Save and on close — that
 * is the whole contract that keeps the list and the editor honest with
 * each other about what is on disk.
 */
#include <string.h>
#include <time.h>

#include "chrome.h"
#include "notes.h"

/* One window, so one instance of its state.  g_notes == NULL whenever
 * the window is closed; every hook below checks it first. */
typedef struct {
    App *app;
    GtkWidget *win;
    GtkWidget *search;      /* GtkSearchEntry: the live filter */
    GtkWidget *stack;       /* list / empty / no-match placeholder */
    GtkWidget *list;        /* GtkListBox of "title — updated date" rows */
    GtkWidget *title;       /* GtkEntry */
    GtkWidget *body;        /* GtkTextView */
    GtkWidget *btn_dup;
    GtkWidget *btn_del;
    GtkWidget *btn_save;
    rb_notes *notes;
    char *path;             /* the active profile's notes.jsonl */
    long current_id;        /* the note loaded in the editor, 0 = none */
    int silent;             /* suppress selection handling while refilling */
} RbNotesWin;

static RbNotesWin *g_notes = NULL;

/* ms since the epoch, local time — the same figures rb_dl_stamp prints. */
static void rb_notes_stamp(long long ms, char *out, size_t cap)
{
    time_t t;
    struct tm *parts;

    if (ms <= 0) {
        snprintf(out, cap, "not yet");
        return;
    }
    t = (time_t)(ms / 1000);
    parts = localtime(&t);
    if (parts == NULL || strftime(out, cap, "%Y-%m-%d %H:%M:%S", parts) == 0) {
        snprintf(out, cap, "unknown");
    }
}

static long long rb_notes_now_ms(void)
{
    return (long long)(g_get_real_time() / G_USEC_PER_SEC);
}

/* The active profile's notes.jsonl, built the same way the chrome builds
 * history.jsonl's path: rb_paths_data_dir() plus a join.  Resolved fresh
 * on every load and save, so the window never carries profile state of
 * its own. */
static char *rb_notes_path(void)
{
    char *dir = rb_paths_data_dir();
    char *p;

    if (dir == NULL) return NULL;
    p = rb_paths_join(dir, "notes.jsonl");
    rb_paths_free(dir);
    return p;
}

/* Every mutation saves at once, like the other stores the chrome keeps. */
static void rb_notes_store_save(RbNotesWin *st)
{
    if (st->path != NULL) rb_notes_save(st->notes, st->path);
}

/* What a row (and the delete dialog) calls a note with no title. */
static const char *rb_notes_display_title(const rb_note *n)
{
    return (n->title != NULL && n->title[0] != '\0') ? n->title
                                                     : "(untitled)";
}

/* Puts the selected note into the editor, or empties the editor when
 * there is none.  Also keeps the per-note buttons honest. */
static void rb_notes_load_editor(RbNotesWin *st)
{
    const rb_note *n = (st->current_id != 0)
        ? rb_notes_get(st->notes, st->current_id) : NULL;
    GtkTextBuffer *buf = gtk_text_view_get_buffer(GTK_TEXT_VIEW(st->body));

    if (n != NULL) {
        gtk_entry_set_text(GTK_ENTRY(st->title), n->title);
        gtk_text_buffer_set_text(buf, n->body, -1);
    } else {
        st->current_id = 0;
        gtk_entry_set_text(GTK_ENTRY(st->title), "");
        gtk_text_buffer_set_text(buf, "", -1);
    }
    gtk_widget_set_sensitive(st->btn_dup, n != NULL);
    gtk_widget_set_sensitive(st->btn_del, n != NULL);
    gtk_widget_set_sensitive(st->btn_save, n != NULL);
}

/* Editor -> store -> disk, but only when something actually changed: an
 * edit bumps updated_at and the store sorts newest-first, so a no-op
 * commit would shuffle the rows the user is merely walking through.
 * Returns 1 when the store moved. */
static int rb_notes_commit_pending(RbNotesWin *st)
{
    const rb_note *n;
    GtkTextBuffer *buf;
    GtkTextIter a, b;
    const char *title;
    char *body;
    int changed = 0;

    if (st->current_id == 0) return 0;
    n = rb_notes_get(st->notes, st->current_id);
    if (n == NULL) return 0;

    title = gtk_entry_get_text(GTK_ENTRY(st->title));
    buf = gtk_text_view_get_buffer(GTK_TEXT_VIEW(st->body));
    gtk_text_buffer_get_bounds(buf, &a, &b);
    body = gtk_text_buffer_get_text(buf, &a, &b, FALSE);
    if (strcmp(title, n->title) != 0 || strcmp(body, n->body) != 0) {
        rb_notes_edit(st->notes, st->current_id, title, body,
                      rb_notes_now_ms());
        rb_notes_store_save(st);
        changed = 1;
    }
    g_free(body);
    return changed;
}

/* Parented on the notes window, not the main one: a message behind the
 * open window would be unreachable.  Same shape as the downloads one. */
static void rb_notes_msg(RbNotesWin *st, GtkMessageType type,
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

static int rb_notes_confirm(RbNotesWin *st, const char *title,
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

/* Rebuilds the rows from the store through the current filter.  Runs
 * with the selection handler suppressed: the rows are destroyed and
 * rebuilt, and the editor must not be told about it.  The note loaded
 * in the editor is reselected when its row survives the filter and
 * simply stays loaded when it does not — hiding a row is not the same
 * as closing it. */
static void rb_notes_refill(RbNotesWin *st)
{
    const rb_note **hits;
    const char *needle;
    int total, k, i;

    st->silent = 1;
    gtk_container_foreach(GTK_CONTAINER(st->list),
                          (GtkCallback)gtk_widget_destroy, NULL);

    /* An empty needle matches everything (the core documents it), so the
     * search call serves both the filtered and the unfiltered list. */
    total = rb_notes_count(st->notes);
    needle = gtk_entry_get_text(GTK_ENTRY(st->search));
    hits = (const rb_note **)g_malloc(sizeof(const rb_note *) *
                                      (size_t)(total > 0 ? total : 1));
    k = rb_notes_search(st->notes, needle, hits, total);

    for (i = 0; i < k; i++) {
        const rb_note *n = hits[i];
        GtkWidget *row, *lbl;
        char date[64];
        char *text;

        rb_notes_stamp(n->updated_at, date, sizeof date);
        text = g_strdup_printf("%s — %s", rb_notes_display_title(n), date);
        lbl = gtk_label_new(text);
        g_free(text);
        gtk_widget_set_halign(lbl, GTK_ALIGN_START);
        gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
        gtk_widget_set_margin_start(lbl, 6);
        gtk_widget_set_margin_end(lbl, 6);
        gtk_widget_set_margin_top(lbl, 3);
        gtk_widget_set_margin_bottom(lbl, 3);

        row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row), lbl);
        g_object_set_data(G_OBJECT(row), "rb-id",
                          GSIZE_TO_POINTER((gsize)n->id));
        gtk_container_add(GTK_CONTAINER(st->list), row);
        gtk_widget_show_all(row);
    }
    g_free(hits);

    /* Three states, never a dead pane: the store empty, the filter
     * matching nothing, or rows to show. */
    gtk_stack_set_visible_child_name(GTK_STACK(st->stack),
        (total == 0) ? "empty" : (k == 0) ? "nomatch" : "list");

    if (st->current_id != 0) {
        for (i = 0; ; i++) {
            GtkListBoxRow *row =
                gtk_list_box_get_row_at_index(GTK_LIST_BOX(st->list), i);
            long id;

            if (row == NULL) break;
            id = (long)GPOINTER_TO_SIZE(
                g_object_get_data(G_OBJECT(row), "rb-id"));
            if (id == st->current_id) {
                gtk_list_box_select_row(GTK_LIST_BOX(st->list), row);
                break;
            }
        }
    }
    st->silent = 0;
}

static void on_notes_search_changed(GtkSearchEntry *entry,
                                    gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;

    (void)entry;
    rb_notes_refill(st);
}

static void on_notes_selection_changed(GtkListBox *box, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;
    GtkListBoxRow *row;
    long id;

    (void)box;
    if (st->silent) return;

    row = gtk_list_box_get_selected_row(GTK_LIST_BOX(st->list));
    id = (row != NULL)
        ? (long)GPOINTER_TO_SIZE(g_object_get_data(G_OBJECT(row), "rb-id"))
        : 0;

    /* Switching rows saves the previous edit first — that is what keeps
     * the list and the editor from ever disagreeing about what is on
     * disk. */
    rb_notes_commit_pending(st);
    st->current_id = id;
    rb_notes_load_editor(st);
    rb_notes_refill(st);
}

static void on_notes_new_clicked(GtkButton *button, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;
    long id;

    (void)button;
    rb_notes_commit_pending(st);
    id = rb_notes_add(st->notes, "", "", rb_notes_now_ms());
    if (id <= 0) {
        rb_notes_msg(st, GTK_MESSAGE_WARNING, "Cannot add a note",
                     "The note store refused the new row.");
        return;
    }
    st->current_id = id;
    rb_notes_load_editor(st);
    rb_notes_store_save(st);
    rb_notes_refill(st);
    gtk_widget_grab_focus(st->title);
}

static void on_notes_dup_clicked(GtkButton *button, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;
    const rb_note *n;
    char *copy = NULL;
    long id;

    (void)button;
    n = (st->current_id != 0) ? rb_notes_get(st->notes, st->current_id)
                              : NULL;
    if (n == NULL) return;
    /* An empty title stays empty: the row already says "(untitled)", and
     * a copy named " (copy)" would look worse than no name at all. */
    if (n->title[0] != '\0') copy = g_strdup_printf("%s (copy)", n->title);
    id = rb_notes_add(st->notes, (copy != NULL) ? copy : "", n->body,
                      rb_notes_now_ms());
    g_free(copy);
    if (id <= 0) {
        rb_notes_msg(st, GTK_MESSAGE_WARNING, "Cannot duplicate the note",
                     "The note store refused the copy.");
        return;
    }
    st->current_id = id;
    rb_notes_load_editor(st);
    rb_notes_store_save(st);
    rb_notes_refill(st);
}

static void on_notes_delete_clicked(GtkButton *button, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;
    const rb_note *n;
    char *body;

    (void)button;
    n = (st->current_id != 0) ? rb_notes_get(st->notes, st->current_id)
                              : NULL;
    if (n == NULL) return;
    body = g_strdup_printf("\"%s\" will be removed for this profile.",
                           rb_notes_display_title(n));
    if (!rb_notes_confirm(st, "Delete this note?", body)) {
        g_free(body);
        return;
    }
    g_free(body);
    rb_notes_remove(st->notes, st->current_id);
    rb_notes_store_save(st);
    st->current_id = 0;
    rb_notes_load_editor(st);
    rb_notes_refill(st);
}

static void on_notes_save_clicked(GtkButton *button, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;

    (void)button;
    if (rb_notes_commit_pending(st)) rb_notes_refill(st);
}

static void on_notes_response(GtkWidget *win, int response,
                              gpointer user_data)
{
    (void)user_data;
    (void)response;
    gtk_widget_destroy(win);
}

/* Closing flushes: the editor may hold text the user never committed.
 * Runs before the children are torn down, so the widgets still read. */
static void on_notes_destroy(GtkWidget *widget, gpointer user_data)
{
    RbNotesWin *st = (RbNotesWin *)user_data;

    (void)widget;
    rb_notes_commit_pending(st);
    rb_notes_store_save(st);
    rb_notes_free(st->notes);
    g_free(st->path);
    g_free(st);
    g_notes = NULL;
}

int rb_open_notes_window(App *app)
{
    RbNotesWin *st;
    GtkWidget *content, *hbox, *left, *right, *scroll, *empty, *nomatch;
    GtkWidget *btns, *new_btn;

    if (g_notes != NULL) {
        gtk_window_present(GTK_WINDOW(g_notes->win));
        return 0;
    }
    if (app == NULL) return -1;

    st = g_new0(RbNotesWin, 1);
    st->app = app;
    st->path = rb_notes_path();
    st->notes = rb_notes_new();
    if (st->notes == NULL) {
        g_free(st->path);
        g_free(st);
        return -1;
    }
    if (st->path != NULL) rb_notes_load(st->notes, st->path);

    st->win = gtk_dialog_new_with_buttons("Notes", GTK_WINDOW(app->win),
        GTK_DIALOG_DESTROY_WITH_PARENT, "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(st->win), 720, 520);

    hbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8);
    gtk_container_set_border_width(GTK_CONTAINER(hbox), 8);
    content = gtk_dialog_get_content_area(GTK_DIALOG(st->win));
    gtk_box_pack_start(GTK_BOX(content), hbox, TRUE, TRUE, 0);

    /* Left: the filter and the list it feeds. */
    left = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_widget_set_size_request(left, 260, -1);
    gtk_box_pack_start(GTK_BOX(hbox), left, FALSE, FALSE, 0);

    st->search = gtk_search_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(st->search), "Search notes");
    gtk_box_pack_start(GTK_BOX(left), st->search, FALSE, FALSE, 0);

    st->list = gtk_list_box_new();
    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(scroll), st->list);

    empty = gtk_label_new("No notes yet");
    gtk_widget_set_halign(empty, GTK_ALIGN_CENTER);
    gtk_widget_set_valign(empty, GTK_ALIGN_CENTER);
    nomatch = gtk_label_new("No matching notes");
    gtk_widget_set_halign(nomatch, GTK_ALIGN_CENTER);
    gtk_widget_set_valign(nomatch, GTK_ALIGN_CENTER);
    st->stack = gtk_stack_new();
    gtk_stack_set_transition_type(GTK_STACK(st->stack),
                                  GTK_STACK_TRANSITION_TYPE_NONE);
    gtk_stack_add_named(GTK_STACK(st->stack), scroll, "list");
    gtk_stack_add_named(GTK_STACK(st->stack), empty, "empty");
    gtk_stack_add_named(GTK_STACK(st->stack), nomatch, "nomatch");
    gtk_box_pack_start(GTK_BOX(left), st->stack, TRUE, TRUE, 0);

    /* Right: the editor. */
    right = gtk_box_new(GTK_ORIENTATION_VERTICAL, 6);
    gtk_box_pack_start(GTK_BOX(hbox), right, TRUE, TRUE, 0);

    st->title = gtk_entry_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(st->title), "Title");
    gtk_box_pack_start(GTK_BOX(right), st->title, FALSE, FALSE, 0);

    st->body = gtk_text_view_new();
    gtk_text_view_set_wrap_mode(GTK_TEXT_VIEW(st->body), GTK_WRAP_WORD_CHAR);
    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(scroll), st->body);
    gtk_box_pack_start(GTK_BOX(right), scroll, TRUE, TRUE, 0);

    btns = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    new_btn = gtk_button_new_with_label("New note");
    st->btn_dup = gtk_button_new_with_label("Duplicate");
    st->btn_del = gtk_button_new_with_label("Delete");
    st->btn_save = gtk_button_new_with_label("Save");
    gtk_widget_set_tooltip_text(st->btn_dup, "Copy this note as a new one");
    gtk_widget_set_tooltip_text(st->btn_del, "Remove this note (asks first)");
    gtk_box_pack_start(GTK_BOX(btns), new_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(btns), st->btn_dup, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(btns), st->btn_del, FALSE, FALSE, 0);
    gtk_box_pack_end(GTK_BOX(btns), st->btn_save, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(right), btns, FALSE, FALSE, 0);

    g_signal_connect(st->search, "search-changed",
                     G_CALLBACK(on_notes_search_changed), st);
    g_signal_connect(st->list, "selected-rows-changed",
                     G_CALLBACK(on_notes_selection_changed), st);
    g_signal_connect(new_btn, "clicked",
                     G_CALLBACK(on_notes_new_clicked), st);
    g_signal_connect(st->btn_dup, "clicked",
                     G_CALLBACK(on_notes_dup_clicked), st);
    g_signal_connect(st->btn_del, "clicked",
                     G_CALLBACK(on_notes_delete_clicked), st);
    g_signal_connect(st->btn_save, "clicked",
                     G_CALLBACK(on_notes_save_clicked), st);
    g_signal_connect(st->win, "response", G_CALLBACK(on_notes_response), st);
    g_signal_connect(st->win, "destroy", G_CALLBACK(on_notes_destroy), st);

    g_notes = st;
    rb_notes_load_editor(st);
    rb_notes_refill(st);
    gtk_widget_show_all(st->win);
    return 0;
}

/* The profile-switch protocol, matching the Windows edition one for one:
 * flush while the OLD profile is still the destination (the editor may
 * hold an uncommitted edit), reload once the active profile has
 * changed. */
void rb_notes_flush(App *app)
{
    RbNotesWin *st = g_notes;

    (void)app;
    if (st == NULL) return;
    rb_notes_commit_pending(st);
    rb_notes_store_save(st);
}

void rb_notes_reload(App *app)
{
    RbNotesWin *st = g_notes;

    (void)app;
    if (st == NULL) return;
    g_free(st->path);
    st->path = rb_notes_path();
    rb_notes_free(st->notes);
    st->notes = rb_notes_new();
    st->current_id = 0;
    if (st->notes != NULL && st->path != NULL) {
        rb_notes_load(st->notes, st->path);
    }
    rb_notes_load_editor(st);
    rb_notes_refill(st);
}
