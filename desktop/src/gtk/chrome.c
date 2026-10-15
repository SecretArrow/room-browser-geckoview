/*
 * Room Browser (desktop) - GTK chrome implementation.
 *
 * GTK3 chrome in pure C11: GtkNotebook tab strip (one WebKitWebView per
 * tab), toolbar with omnibox, bookmark star and hamburger menu, keyboard
 * accelerators via GSimpleAction, plus the shared persistence glue.
 * Brave-inspired dark palette with purple accent (#A78BFA) loaded through
 * a GtkCssProvider (RB_CSS below).
 *
 * Design notes:
 *  - Array index of app->tabs == notebook page index == rb_tabs index;
 *    the switch-page handler keeps app->active_id in sync (suppressed via
 *    app->silent while the chrome itself mutates the notebook).
 *  - The per-tab close button resolves its tab by widget pointer
 *    (rb_tab_by_widget), so it keeps working after any reordering.
 *  - The hamburger menu is a classic GtkMenu attached to a GtkMenuButton
 *    (gtk_menu_button_set_popup is deprecated in favour of popovers but
 *    still fully functional on GTK 3.x; the two calls are wrapped in
 *    G_GNUC_BEGIN/END_IGNORE_DEPRECATIONS to stay warning-clean).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <gdk/gdkkeysyms.h>

#include "chrome.h"
#include "webview.h"
#include "rb_version.h"
#include "notes.h"
#include "totp.h"

/* Single global application state (declared extern in chrome.h). */
App g_app;

/* The JavaScript check menu item (single instance, module-static). */
static GtkWidget *g_js_item = NULL;

/* The "Show bookmarks bar" check menu item, rebuilt with the menu. */
static GtkWidget *g_bm_item = NULL;

/* Forward declarations (defined below, referenced by earlier functions). */
static void on_tab_close_clicked(GtkButton *button, gpointer user_data);
static void on_newtab_clicked(GtkButton *button, gpointer user_data);
static void rb_downloads_dir_init(App *app);
static void rb_bookmarks_bar_refresh(App *app);
/* The User-Agent page's rows are built by a helper that sits above the row
 * builders themselves, because the device row hands them their state. */
static GtkWidget *rb_pref_combo_row(GtkWidget *grid, int row, App *app,
                                    const char *key, const char *const *ids,
                                    const char *const *labels,
                                    const char *current, const char *title,
                                    int is_theme);
/* Defined after the device row, which is what calls it: a machine chosen in
 * the picker is what greys these rows out. */
static void rb_ua_followers_update(GtkWidget *device_widget, gpointer user_data);
static GtkWidget *rb_pref_entry_row(GtkWidget *grid, int row, App *app,
                                    const char *key, const char *current,
                                    const char *title);

/* ------------------------------------------------------------------ */
/* Chrome CSS, generated from the active profile's theme.
 *
 * The layout rules are fixed; every colour comes from rb_theme.h, so the
 * desktop wears the same 18 palettes the Android edition does and a profile
 * change repaints the chrome.  RGBA forms are emitted with GTK3's
 * rgba(r,g,b,a) syntax rather than #RRGGBBaa, which GTK 3.20 does not parse.
 *
 * The provider is kept so the stylesheet can be re-loaded in place when the
 * theme changes; the fallback (no theme yet) is the obsidian default. */

static GtkCssProvider *g_css = NULL;

/* The font the chrome would use unscaled: whatever GTK reports the first time
 * a scale is applied, captured then and kept.  rb_apply_font_scale() always
 * scales THIS rather than the name currently installed, so going 150% -> 100%
 * returns to the system size instead of compounding to 150% of 150%.
 *
 * Captured lazily rather than at start-up because GTK has to be initialised
 * first, and the first apply is the earliest point that is guaranteed. */
static char *g_base_font = NULL;

static void rb_capture_base_font(void)
{
    GtkSettings *s = gtk_settings_get_default();
    char *name = NULL;

    if (s == NULL || g_base_font != NULL) return;
    g_object_get(s, "gtk-font-name", &name, NULL);
    if (name != NULL && name[0] != '\0') {
        g_base_font = name;   /* g_object_get hands over the reference */
    } else {
        g_free(name);
    }
}

/* Applies the active profile's font scale to the UI font.
 *
 * A profile at RB_FONT_SCALE_DEFAULT gets the captured system font back
 * verbatim, so the common case leaves no trace on a setting that is
 * process-wide: the chrome renders exactly as it would with no scaling at
 * all.  Any other value scales the size the system font already had, which is
 * what makes this an accessibility control rather than a font override — a
 * user who already runs a large system font keeps it, scaled.
 *
 * gtk-font-name rather than a font-size rule in the stylesheet: it is the one
 * lever that reaches every widget the chrome builds, including the ones whose
 * text is set by the theme's CSS and the dialogs opened later. */
void rb_apply_font_scale(App *app)
{
    GtkSettings *s = gtk_settings_get_default();
    const rb_profile *p = rb_active_profile(app);
    const char *space;
    double base;
    int pct;
    int tenths;
    char *scaled;

    if (s == NULL) return;
    if (g_base_font == NULL) rb_capture_base_font();
    if (g_base_font == NULL) return;   /* nothing to scale from */

    pct = rb_font_scale_percent(p ? p->settings : NULL);
    if (pct == RB_FONT_SCALE_DEFAULT) {
        gtk_settings_set_string_property(s, "gtk-font-name", g_base_font, "rb");
        return;
    }

    /* "<family> <size>", as Pango writes it: the size follows the last space.
     * A name carrying no size at all is left alone rather than guessed at. */
    space = strrchr(g_base_font, ' ');
    if (space == NULL) return;
    base = g_ascii_strtod(space + 1, NULL);
    if (base <= 0.0) return;

    /* Point sizes are fractional (10.5, 11.5), so one decimal is kept.
     *
     * Both halves of this are deliberately locale-blind.  atof() and %f
     * follow LC_NUMERIC, and under a comma-decimal locale they would turn a
     * perfectly good "Cantarell 11" into "Cantarell 11,0" — which Pango
     * cannot parse, leaving the chrome with no font at all.  g_ascii_strtod()
     * always reads '.', and the result is built from integers so nothing
     * locale-dependent formats it. */
    tenths = (int)(base * (double)pct / 10.0 + 0.5);
    if (tenths < 1) tenths = 1;
    scaled = g_strdup_printf("%.*s %d.%d", (int)(space - g_base_font),
                             g_base_font, tenths / 10, tenths % 10);
    gtk_settings_set_string_property(s, "gtk-font-name", scaled, "rb");
    g_free(scaled);
}

/* Applies the profile's "Reduce motion" to the toolkit itself.
 *
 * GTK animates its own widgets — a switch sliding, a tab cross-fading, a menu
 * unfurling — and no stylesheet this chrome owns can reach any of that;
 * gtk-enable-animations is the one lever.  The progress strip is deliberately
 * NOT handled here: it is drawn rather than styled, and it lives in
 * rb_progress_sync(), which is the only place that knows whether a load is in
 * flight. */
void rb_apply_reduced_motion(App *app)
{
    GtkSettings *s = gtk_settings_get_default();

    if (s == NULL || app == NULL) return;
    g_object_set(s, "gtk-enable-animations",
                 rb_pref_int(app, RB_PREF_REDUCED_MOTION, 0) ? FALSE : TRUE,
                 NULL);
}

static void rb_css_rgba(char out[48], unsigned int argb, double alpha_mult)
{
    int r = 0, g = 0, b = 0;
    double a = 1.0;
    rb_theme_rgb(argb, &r, &g, &b);
    a = ((double)((argb >> 24) & 0xffu) / 255.0) * alpha_mult;
    if (a > 1.0) a = 1.0;
    if (a < 0.0) a = 0.0;
    snprintf(out, 48, "rgba(%d,%d,%d,%.3f)", r, g, b, a);
}

static void rb_css_hex(char out[8], unsigned int argb)
{
    rb_theme_hex(argb, out);   /* "#RRGGBB", alpha dropped */
}

/* Builds the stylesheet for a resolved palette.  Caller frees. */
static char *rb_css_build(const rb_theme_colors *c, int radius)
{
    char bg[8], surf[8], alt[8], prim[8];
    char txt[8], dim[8], addr[8], tabbar[8], border[8], sel[8];
    char accent_faint[48], accent_soft[48], sel_soft[48];
    size_t cap = 6144;
    char *css = (char *)malloc(cap);

    if (!css) return NULL;

    rb_css_hex(bg, c->background);
    rb_css_hex(surf, c->surface);
    rb_css_hex(alt, c->surface_alt);
    rb_css_hex(prim, c->primary);
    rb_css_hex(txt, c->text_primary);
    rb_css_hex(dim, c->text_secondary);
    rb_css_hex(addr, c->address_bar);
    rb_css_hex(tabbar, c->tab_bar);
    rb_css_hex(border, c->border);
    rb_css_hex(sel, c->selection);
    rb_css_rgba(accent_faint, c->primary, 0.22);
    rb_css_rgba(accent_soft, c->primary, 0.25);
    rb_css_rgba(sel_soft, c->selection, 0.45);

    snprintf(css, cap,
        "window { background-color: %s; }\n"
        "notebook header { background-color: %s; border: none; }\n"
        "notebook header tabs tab { background-color: %s; color: %s;"
        " padding: 3px 10px 3px 12px; }\n"
        "notebook header tabs tab:checked { background-color: %s;"
        " color: %s; border-bottom: 2px solid %s; }\n"
        "notebook header tabs tab:hover { color: %s; }\n"
        ".rb-toolbar { background-color: %s; padding: 5px 7px;"
        " border-bottom: 1px solid %s; }\n"
        ".rb-btn { background-color: transparent; color: %s; border: none;"
        " padding: 2px 9px; border-radius: %dpx; }\n"
        ".rb-btn:hover { background-color: %s; color: %s; }\n"
        ".rb-btn:disabled { color: %s; }\n"
        ".rb-omni { background-color: %s; color: %s; border: 1px solid %s;"
        " border-radius: %dpx; padding: 3px 10px 5px 10px;"
        " caret-color: %s; }\n"
        ".rb-omni:focus { border-color: %s; }\n"
        ".rb-omni selection { background-color: %s; color: %s; }\n"
        ".rb-tab-close { background-color: transparent; color: %s;"
        " border: none; padding: 0 3px; border-radius: %dpx; }\n"
        ".rb-tab-close:hover { color: %s; background-color: %s; }\n"
        ".rb-dim { color: %s; }\n"
        "menu { background-color: %s; color: %s; border: 1px solid %s; }\n"
        "menu menuitem:hover { background-color: %s; color: %s; }\n",
        bg, tabbar, alt, dim, surf, txt, prim, txt,
        surf, border, txt, radius, accent_faint, txt, dim,
        addr, txt, border, radius, prim, prim, sel_soft, txt,
        dim, radius, txt, accent_soft, dim,
        surf, txt, border, accent_faint, txt);

    /* Shape and the link-target bubble, in a SECOND snprintf rather than more
     * arguments on the one above: that call's format and argument lists are
     * long and positional, and one miscounted %s there reads garbage
     * silently.
     *
     * The shape rules are overrides, so they must come after the base rules
     * — same specificity, later wins.  What they buy is Brave's silhouette:
     * a tab with rounded shoulders whose active state is flush with the
     * toolbar below it (its background already equals the toolbar's, so
     * removing the accent underline is what makes the two read as ONE
     * surface), and a pill-shaped omnibox. */
    {
        size_t used = strlen(css);
        if (used + 1400 < cap) {
            snprintf(css + used, cap - used,
                     "notebook header tabs tab { min-height: 28px;"
                     " margin: 2px 2px 0 0; border-radius: 9px 9px 0 0; }\n"
                     "notebook header tabs tab:checked { border-bottom: none; }\n"
                     ".rb-omni { border-radius: 999px; }\n"
                     ".rb-status { background-color: %s; color: %s;"
                     " border: 1px solid %s; border-radius: %dpx;"
                     " padding: 2px 8px; }\n"
                     ".rb-bmbar { background-color: %s; padding: 3px 6px;"
                     " border-bottom: 1px solid %s; }\n"
                     ".rb-bmbtn { background-color: transparent; color: %s;"
                     " border: none; padding: 2px 8px; border-radius: %dpx; }\n"
                     ".rb-bmbtn:hover { background-color: %s; color: %s; }\n"
                     ".rb-findbar { background-color: %s; padding: 4px 6px;"
                     " border-bottom: 1px solid %s; }\n",
                     surf, txt, border, radius,
                     surf, border, txt, radius, accent_faint, txt,
                     surf, border);
        }
    }
    return css;
}

/* The chrome's own dark/light preference.  GTK3 has no "is the desktop dark"
 * query, so AUTO reads gtk-application-prefer-dark-theme and falls back to
 * the theme name — the same two signals every GTK3 app uses, and an
 * approximation either way. */
static int rb_system_is_dark(void)
{
    GtkSettings *s = gtk_settings_get_default();
    gboolean prefer_dark = FALSE;
    gchar *name = NULL;
    int dark = 0;

    if (s != NULL) {
        g_object_get(s, "gtk-application-prefer-dark-theme", &prefer_dark, NULL);
        dark = prefer_dark ? 1 : 0;
        g_object_get(s, "gtk-theme-name", &name, NULL);
        if (!dark && name != NULL) {
            gchar *lower = g_ascii_strdown(name, -1);
            if (strstr(lower, "dark") != NULL || strstr(lower, "-black") != NULL) {
                dark = 1;
            }
            g_free(lower);
        }
        if (name != NULL) g_free(name);
    }
    return dark;
}

/* The theme the ACTIVE PROFILE selected.  The id lives in the profile's
 * theme_json as {"id":"<theme>"} — the same place, and the same shape, as the
 * Android edition's themeJson snapshot.  "" or a malformed snapshot means the
 * default theme, which is what rb_theme_resolve() already answers. */
const rb_theme *rb_theme_current(App *app)
{
    const rb_profile *p = rb_active_profile(app);
    const char *json = (p && p->theme_json) ? p->theme_json : "";
    size_t pos = 0;
    char *id = NULL;
    const rb_theme *t;

    if (json[0] != '\0' && rb_json_find_key(json, "id", &pos) &&
        rb_json_parse_string(json, &pos, &id) && id != NULL) {
        t = rb_theme_by_id(id);
        free(id);
        return t ? t : rb_theme_default();
    }
    return rb_theme_default();
}

/* The MODE comes from the profile's "theme" setting ("system" is the stored
 * spelling of rb_theme.h's AUTO). */
static rb_theme_mode rb_theme_mode_current(App *app)
{
    const char *m = rb_pref(app, RB_PREF_THEME, "system");
    if (m == NULL) return RB_THEME_AUTO;
    if (strcmp(m, "light") == 0)  return RB_THEME_LIGHT;
    if (strcmp(m, "dark") == 0)   return RB_THEME_DARK;
    if (strcmp(m, "amoled") == 0) return RB_THEME_AMOLED;
    return RB_THEME_AUTO;
}

/* (Re)loads the chrome stylesheet from the active profile's theme. */
void rb_css_load(App *app)
{
    const rb_theme *t = app ? rb_theme_current(app) : rb_theme_default();
    rb_theme_mode mode = app ? rb_theme_mode_current(app) : RB_THEME_DARK;
    rb_theme_colors c = rb_theme_palette(t, mode, rb_system_is_dark());

    /* "High contrast" is a property of the chrome, like the theme itself, so
     * it is applied where the palette is resolved rather than at every use. */
    if (app && rb_pref_int(app, RB_PREF_HIGH_CONTRAST, 0)) {
        c = rb_theme_high_contrast(c);
    }
    char *css = rb_css_build(&c, t->corner_radius);

    if (css == NULL) return;
    if (g_css == NULL) {
        g_css = gtk_css_provider_new();
        {
            GdkScreen *screen = gdk_screen_get_default();
            if (screen) {
                gtk_style_context_add_provider_for_screen(screen,
                    GTK_STYLE_PROVIDER(g_css),
                    GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
            }
        }
    }
    /* GTK3 signature: (provider, data, length, GError**) — the 4-arg form;
     * the 3-arg variant is GTK4-only and fails to compile against gtk+-3.0.
     * Re-loading the same provider restyles every widget that uses it. */
    gtk_css_provider_load_from_data(g_css, css, -1, NULL);
    free(css);
}

/* ------------------------------------------------------------------ */
/* Small helpers */

char *rb_strdup(const char *s)
{
    size_t n;
    char *p;
    if (!s) return NULL;
    n = strlen(s) + 1;
    p = (char *)malloc(n);
    if (!p) return NULL;
    memcpy(p, s, n);
    return p;
}

void rb_set_str(char **dst, char *owned)
{
    if (!dst) { free(owned); return; }
    free(*dst);
    *dst = owned;
}

static char *rb_path_join(const char *dir, const char *name)
{
    size_t n = strlen(dir) + strlen(name) + 2;
    char *p = (char *)malloc(n);
    if (!p) return NULL;
    snprintf(p, n, "%s/%s", dir, name);
    return p;
}

static void rb_add_class(GtkWidget *w, const char *cls)
{
    gtk_style_context_add_class(gtk_widget_get_style_context(w), cls);
}

/* ------------------------------------------------------------------ */
/* Persistence: load at startup, save on change (and a final save at quit). */

/* The name given to the profile created for a data directory that has none.
 * Android calls its first profile "Personal" too (ProfileManager.create()). */
#define RB_PROFILE_FIRST_NAME "Personal"

int rb_data_init(App *app)
{
    char *dir = rb_paths_data_dir();
    int fresh_profiles = 0;

    app->store = rb_tabs_new();
    app->history = rb_history_new();
    app->bookmarks = rb_bookmarks_new();
    app->settings = rb_settings_new();
    app->profiles = rb_profile_registry_new();
    app->switcher = rb_switch_new();
    app->https = rb_https_pending_new();
    app->downloads = rb_downloads_new();
    app->filters = rb_filters_new();
    if (!app->store || !app->history || !app->bookmarks || !app->settings ||
        !app->profiles || !app->switcher || !app->https || !app->downloads ||
        !app->filters) {
        return -1;
    }
    /* The bundled list, compiled in: no network, no update check, same hosts
     * the Android edition ships.  Loading it is what makes the filter engine
     * able to answer at all — the per-profile switches decide which of its
     * categories are consulted. */
    rb_filters_load_builtin(app->filters);
    if (!dir) return -1;

    app->path_history = rb_path_join(dir, "history.jsonl");
    app->path_bookmarks = rb_path_join(dir, "bookmarks.jsonl");
    app->path_settings = rb_path_join(dir, "settings.txt");
    app->path_profiles = rb_path_join(dir, "profiles.jsonl");
    app->path_downloads = rb_path_join(dir, "downloads.jsonl");
    if (!app->path_history || !app->path_bookmarks || !app->path_settings ||
        !app->path_profiles || !app->path_downloads) {
        rb_paths_free(dir);
        return -1;
    }

    /* The GLOBAL store is BrowserGlobalSettings: the handful of settings that
     * belong to the installation rather than to a profile (DNS overrides,
     * network-change retention, telemetry).  Everything a user toggles in the
     * menu now lives in the active profile — see rb_pref(). */
    rb_prefs_global_defaults(app->settings);
    rb_settings_load(app->settings, app->path_settings);

    rb_history_load(app->history, app->path_history);
    rb_bookmarks_load(app->bookmarks, app->path_bookmarks);
    rb_downloads_load(app->downloads, app->path_downloads);
    /* Every row the last run left QUEUED or RUNNING describes a transfer this
     * process never had: the platform engine that owned it (WebKitDownload on
     * GTK, WebView2's DownloadOperation on Windows) died with the last
     * process.  Android re-queues these because its engine issues the HTTP
     * request itself and can resume with a Range header; here there is no
     * transfer left to resume, so the honest outcome is FAILED with a reason
     * the user can read — not a row that claims to be running forever. */
    if (rb_downloads_reconcile(app->downloads, NULL, "Interrupted") > 0) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }

    rb_profile_registry_load(app->profiles, app->path_profiles);
    if (rb_profile_count(app->profiles) == 0) {
        /* A data directory with no profiles.jsonl (a fresh install, or one
         * written by an older desktop build) gets the first profile here,
         * exactly as the Android edition does. */
        fresh_profiles = 1;
        if (rb_profile_create(app->profiles, RB_PROFILE_FIRST_NAME, NULL, 0, 1) < 0) {
            rb_paths_free(dir);
            return -1;
        }
    }

    {
        const rb_profile *first = rb_profile_default(app->profiles);
        if (first == NULL) { first = rb_profile_at(app->profiles, 0); }
        if (first == NULL) { rb_paths_free(dir); return -1; }
        app->active_profile_id = rb_strdup(first->id);
        if (!app->active_profile_id) { rb_paths_free(dir); return -1; }

        /* Settings written by a desktop build older than the profile registry
         * lived in settings.txt under bare keys ("home", "javascript").  Carry
         * them over rather than dropping the user's homepage and JavaScript
         * choice on the floor; the file itself is left where it is. */
        if (fresh_profiles) {
            rb_settings *ps = first->settings;
            if (ps) {
                if (rb_settings_get(app->settings, RB_PREF_HOME_LOCAL, NULL)) {
                    rb_settings_set(ps, RB_PREF_HOME_LOCAL,
                        rb_settings_get(app->settings, RB_PREF_HOME_LOCAL, ""));
                }
                if (rb_settings_get(app->settings, RB_PREF_JAVASCRIPT, NULL)) {
                    rb_settings_set_int(ps, RB_PREF_JAVASCRIPT,
                        rb_settings_get_int(app->settings, RB_PREF_JAVASCRIPT, 1));
                }
            }
        }
    }

    /* Project policy: JavaScript is NEVER disabled by default. */
    app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
    app->home_url = rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                      "https://duckduckgo.com"));
    rb_downloads_dir_init(app);

    /* A profile file from the build whose theme combo wrote the theme ID into
     * the MODE setting is repaired once, here, so a choice made with that
     * picker shows up instead of sitting inert in the wrong key. */
    rb_profile_repair_theme_setting(app->profiles);

    rb_profiles_save(app);
    rb_paths_free(dir);
    return 0;
}

/* Where downloads land: the user's Downloads folder, under the subfolder the
 * profile names in RB_PREF_DOWNLOAD_SUBFOLDER ("RoomBrowser" by default) —
 * the same place, and the same setting, as the Android edition.  Falls back
 * to <data dir>/downloads when the desktop has no Downloads special dir. */
static void rb_downloads_dir_init(App *app)
{
    const char *base = g_get_user_special_dir(G_USER_DIRECTORY_DOWNLOAD);
    const char *sub = rb_pref(app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser");

    if (sub == NULL) sub = "RoomBrowser";
    if (base != NULL) {
        app->download_dir = (sub[0] != '\0')
            ? rb_paths_join(base, sub)
            : rb_strdup(base);
    } else {
        char *data = rb_paths_data_dir();
        app->download_dir = data ? rb_path_join(data, "downloads") : NULL;
        if (data) rb_paths_free(data);
    }
    if (app->download_dir != NULL) {
        rb_paths_mkdirs(app->download_dir);
    }
}

void rb_data_shutdown(App *app)
{
    if (app->history && app->path_history) {
        rb_history_save(app->history, app->path_history);
    }
    if (app->bookmarks && app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    if (app->settings && app->path_settings) {
        rb_settings_save(app->settings, app->path_settings);
    }
    if (app->downloads && app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
    rb_profiles_save(app);
}

void rb_data_free(App *app)
{
    free(app->path_history);   app->path_history = NULL;
    free(app->path_bookmarks); app->path_bookmarks = NULL;
    free(app->path_settings);  app->path_settings = NULL;
    free(app->path_profiles);  app->path_profiles = NULL;
    free(app->path_downloads); app->path_downloads = NULL;
    free(app->download_dir);   app->download_dir = NULL;
    free(app->home_url);       app->home_url = NULL;
    free(app->active_profile_id); app->active_profile_id = NULL;
    if (app->store)     { rb_tabs_free(app->store);         app->store = NULL; }
    if (app->history)   { rb_history_free(app->history);    app->history = NULL; }
    if (app->bookmarks) { rb_bookmarks_free(app->bookmarks); app->bookmarks = NULL; }
    if (app->downloads) { rb_downloads_free(app->downloads); app->downloads = NULL; }
    if (app->settings)  { rb_settings_free(app->settings);  app->settings = NULL; }
    if (app->profiles)  { rb_profile_registry_free(app->profiles); app->profiles = NULL; }
    if (app->switcher)  { rb_switch_free(app->switcher);    app->switcher = NULL; }
    if (app->https)     { rb_https_pending_free(app->https); app->https = NULL; }
    if (app->filters)   { rb_filters_free(app->filters); app->filters = NULL; }
    rb_gw_content_blocking_clear(app);
    if (app->cb_store)  { g_object_unref(app->cb_store); app->cb_store = NULL; }
    free(app->tabs);
    app->tabs = NULL;
    app->tabs_n = 0;
    app->tabs_cap = 0;
}

/* ------------------------------------------------------------------ */
/* Profile-scoped settings.
 *
 * Android reads every feature switch off the active ProfileSettings.  These
 * are the desktop's spelling of that read, so a feature never has to know
 * whether a profile exists, and a missing key falls back to the same default
 * the Android edition uses. */

const rb_profile *rb_active_profile(App *app)
{
    if (!app || !app->profiles || !app->active_profile_id) { return NULL; }
    return rb_profile_by_id(app->profiles, app->active_profile_id);
}

const char *rb_pref(App *app, const char *key, const char *fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) { return fallback; }
    return rb_settings_get(p->settings, key, fallback);
}

int rb_pref_int(App *app, const char *key, int fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) { return fallback; }
    return rb_settings_get_int(p->settings, key, fallback);
}

const rb_settings *rb_pref_store(App *app)
{
    const rb_profile *p = rb_active_profile(app);
    return (p != NULL) ? p->settings : NULL;
}

void rb_pref_set(App *app, const char *key, const char *value)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p) { return; }
    /* rb_profile_by_id hands out a const row, but the settings store it points
     * at is not itself const: the pointer member is what carries the
     * qualification, so reading it out yields a mutable rb_settings *.  The
     * registry owns the store, so it is saved with the profile. */
    rb_settings_set(p->settings, key, value);
    rb_profiles_save(app);
}

void rb_pref_set_int(App *app, const char *key, int value)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p) { return; }
    rb_settings_set_int(p->settings, key, value);
    rb_profiles_save(app);
}

void rb_profiles_save(App *app)
{
    if (app && app->profiles && app->path_profiles) {
        rb_profile_registry_save(app->profiles, app->path_profiles);
    }
}

/* The active profile's effective User-Agent, malloc'd — or NULL when the
 * engine default should be sent untouched.  Mirrors UserAgents.
 * effectiveUserAgent(): a device, when the profile presents one, decides the
 * UA on its own; otherwise the profile's ua_mode picks between the engine
 * default, one of the presets, and a free-form string.  The device wins
 * because it also decides the shim, and a UA that disagreed with the machine
 * behind it would be the contradiction the whole feature exists to avoid. */
char *rb_ua_current(App *app)
{
    const char *mode;
    rb_ua_mode m = RB_UA_MODE_DEFAULT;
    char *device_ua = rb_device_ua_for(rb_pref(app, RB_PREF_DEVICE_ID, NULL));

    if (device_ua != NULL) {
        return device_ua;
    }
    mode = rb_pref(app, RB_PREF_UA_MODE, "default");
    if (mode != NULL) {
        if (strcmp(mode, "preset") == 0) {
            m = RB_UA_MODE_PRESET;
        } else if (strcmp(mode, "custom") == 0) {
            m = RB_UA_MODE_CUSTOM;
        }
    }
    return rb_ua_effective(m, rb_pref(app, RB_PREF_UA_PRESET_ID, NULL),
                           rb_pref(app, RB_PREF_CUSTOM_USER_AGENT, NULL));
}

rb_filter_options rb_filter_opts(App *app)
{
    /* The compatibility-first defaults the engine ships, then the profile's
     * own overrides.  Reading through rb_pref_int means a profile that has
     * never touched these keys gets exactly the engine default. */
    rb_filter_options o = rb_filter_options_default();

    o.block_ads        = rb_pref_int(app, RB_PREF_BLOCK_ADS, o.block_ads);
    o.block_trackers   = rb_pref_int(app, RB_PREF_BLOCK_TRACKERS, o.block_trackers);
    o.block_cross_site = rb_pref_int(app, RB_PREF_BLOCK_CROSS_SITE, o.block_cross_site);
    o.block_malicious  = rb_pref_int(app, RB_PREF_BLOCK_MALICIOUS, o.block_malicious);
    o.block_popups     = rb_pref_int(app, RB_PREF_BLOCK_POPUPS, o.block_popups);
    return o;
}

/* ------------------------------------------------------------------ */
/* Tab resolution */

GtkTab *rb_tab_by_widget(App *app, GtkWidget *w)
{
    int i;
    if (!app || !w) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if ((GtkWidget *)app->tabs[i].wv == w ||
            app->tabs[i].label == w ||
            app->tabs[i].close_btn == w ||
            app->tabs[i].icon == w) {
            return &app->tabs[i];
        }
    }
    return NULL;
}

GtkTab *rb_active_tab(App *app)
{
    int i;
    if (!app) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == app->active_id) return &app->tabs[i];
    }
    return NULL;
}

/* Edge of the square a tab favicon is scaled into.  16px matches what Brave
 * and Chrome use, and leaves the tab's title the rest of the width. */
#define RB_TAB_ICON 16

void rb_tab_favicon_set(GtkTab *tab, WebKitWebView *wv)
{
    cairo_surface_t *surface;
    GdkPixbuf *pix = NULL;
    int w = 0, h = 0;

    if (!tab || !tab->icon || !wv) return;

    surface = webkit_web_view_get_favicon(wv);
    if (surface) {
        w = cairo_image_surface_get_width(surface);
        h = cairo_image_surface_get_height(surface);
        if (w > 0 && h > 0) {
            pix = gdk_pixbuf_get_from_surface(surface, 0, 0, w, h);
        }
    }

    if (pix) {
        /* Fit the icon into RB_TAB_ICON square, preserving the aspect ratio:
         * a non-square favicon scaled to 16x16 outright would be stretched. */
        int side = (w > h) ? w : h;
        int iw = w * RB_TAB_ICON / side;
        int ih = h * RB_TAB_ICON / side;
        GdkPixbuf *scaled;
        if (iw < 1) iw = 1;
        if (ih < 1) ih = 1;
        scaled = gdk_pixbuf_scale_simple(pix, iw, ih, GDK_INTERP_BILINEAR);
        g_object_unref(pix);
        if (scaled) {
            /* set_from_pixbuf takes its own reference, so ours goes here. */
            gtk_image_set_from_pixbuf(GTK_IMAGE(tab->icon), scaled);
            g_object_unref(scaled);
            gtk_widget_set_visible(tab->icon, TRUE);
            return;
        }
    }

    /* No favicon (or one we could not decode): hide the image rather than
     * leave a blank square, so the title keeps the full width. */
    gtk_image_clear(GTK_IMAGE(tab->icon));
    gtk_widget_set_visible(tab->icon, FALSE);
}

void rb_status_show(App *app, const char *text)
{
    if (!app || !app->status) return;
    if (text && text[0]) {
        gtk_label_set_text(GTK_LABEL(app->status), text);
        gtk_widget_show(app->status);
    } else {
        gtk_label_set_text(GTK_LABEL(app->status), "");
        gtk_widget_hide(app->status);
    }
}

static rb_tab *rb_store_tab(App *app)
{
    return app->active_id ? rb_tabs_get(app->store, app->active_id) : NULL;
}

/* ------------------------------------------------------------------ */
/* UI refresh helpers */

/* The connection indicator Brave puts at the leading edge of the omnibox.
 * HTTPS gets the padlock and plain HTTP the warning; anything else — an
 * internal page, or the blank new tab — gets NO icon rather than a
 * misleading one.  The names come from the icon theme, so a theme without
 * them shows nothing instead of failing. */
static void rb_update_omni_security(App *app, const char *url)
{
    const char *icon = NULL;
    const char *tip = NULL;

    if (url && g_str_has_prefix(url, "https://")) {
        icon = "channel-secure-symbolic";
        tip = "Connection is encrypted";
    } else if (url && g_str_has_prefix(url, "http://")) {
        icon = "channel-insecure-symbolic";
        tip = "Not secure \xE2\x80\x94 this connection is not encrypted";
    }

    gtk_entry_set_icon_from_icon_name(GTK_ENTRY(app->omnibox),
                                      GTK_ENTRY_ICON_PRIMARY, icon);
    gtk_entry_set_icon_tooltip_text(GTK_ENTRY(app->omnibox),
                                    GTK_ENTRY_ICON_PRIMARY,
                                    icon ? tip : NULL);
}

void rb_update_omni(App *app, const char *url)
{
    if (!app || !app->omnibox) return;
    gtk_entry_set_text(GTK_ENTRY(app->omnibox), url ? url : "");
    rb_update_omni_security(app, url);
}

void rb_update_titlebar(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (t && t->title && t->title[0]) {
        char buf[600];
        snprintf(buf, sizeof buf, "%s \xE2\x80\x94 Room Browser", t->title);
        gtk_window_set_title(GTK_WINDOW(app->win), buf);
    } else {
        gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");
    }
}

void rb_update_nav(App *app)
{
    gboolean back = FALSE, fwd = FALSE;
    rb_gw_can_nav(app, &back, &fwd);
    gtk_widget_set_sensitive(app->back, back);
    gtk_widget_set_sensitive(app->fwd, fwd);
}

void rb_update_star(App *app)
{
    rb_tab *t = rb_store_tab(app);
    int on = (t && t->url && rb_bookmarks_contains(app->bookmarks, t->url));
    gtk_button_set_label(GTK_BUTTON(app->star), on ? "\xE2\x98\x85" : "\xE2\x98\x86");
}

void rb_update_reloadbtn(App *app)
{
    gtk_button_set_label(GTK_BUTTON(app->reload), app->loading ? "\xC3\x97" : "\xE2\x9F\xB3");
}

/* ------------------------------------------------------------------ */
/* The page-load indicator.
 *
 * A 2px accent segment sliding along the very bottom of the toolbar while a
 * page is loading, and nothing at all when none is.  It mirrors the Win32
 * edition's strip: deliberately minimal — no track, no text, no percentage —
 * and drawn in its own 2px-tall strip between the toolbar and the page, so it
 * never covers anything being read and a running animation redraws two rows
 * rather than the window.  The reload button flipping to a stop glyph stays
 * the authoritative signal; this only makes a slow load visible at a glance.
 *
 * The colour is resolved per frame from the active profile's theme rather
 * than cached, so a theme change during a load is picked up for free. */

#define RB_PROGRESS_H      2
#define RB_PROGRESS_MS     16      /* ~60 Hz */
#define RB_PROGRESS_STEPS  140     /* ticks for one left-to-right sweep */
#define RB_PROGRESS_DIV    4       /* segment width = strip width / this */

static int   g_prog_on;            /* 1 while the animation timeout is armed */
static int   g_prog_phase;         /* 0..RB_PROGRESS_STEPS */
static guint g_prog_timer;         /* the timeout source, 0 when not armed */

static gboolean rb_progress_draw(GtkWidget *w, cairo_t *cr, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_theme_colors c;
    double wd, seg_w, x;

    if (app == NULL || !app->loading) return FALSE;
    wd = (double)gtk_widget_get_allocated_width(w);
    if (wd <= 0.0) return FALSE;

    seg_w = wd / RB_PROGRESS_DIV;
    if (seg_w < 32.0) seg_w = 32.0;
    /* Enters from the left edge, leaves past the right edge, repeats — the
     * standard indeterminate sweep, with no notion of "how far along". */
    x = ((double)g_prog_phase * (wd + seg_w)) / (double)RB_PROGRESS_STEPS - seg_w;

    c = rb_theme_palette(rb_theme_current(app), rb_theme_mode_current(app),
                         rb_system_is_dark());
    cairo_set_source_rgba(cr,
        (double)((c.primary >> 16) & 0xffu) / 255.0,
        (double)((c.primary >>  8) & 0xffu) / 255.0,
        (double)( c.primary        & 0xffu) / 255.0,
        1.0);
    cairo_rectangle(cr, x, 0.0, seg_w, (double)RB_PROGRESS_H);
    cairo_fill(cr);
    return FALSE;
}

static gboolean rb_progress_tick(gpointer user_data)
{
    App *app = (App *)user_data;

    g_prog_phase++;
    if (g_prog_phase > RB_PROGRESS_STEPS) g_prog_phase = 0;
    if (app != NULL && app->prog_area != NULL) {
        gtk_widget_queue_draw(app->prog_area);
    }
    return G_SOURCE_CONTINUE;
}

/* The strip is destroyed with the window, but a load can still be in flight
 * at that point (teardown calls rb_set_loading(app, 0) itself).  Dropping the
 * pointer and the timeout here is what keeps a later queue_draw — or the next
 * tick — from touching freed memory. */
static void rb_progress_area_gone(GtkWidget *w, gpointer user_data)
{
    App *app = (App *)user_data;

    if (app != NULL && app->prog_area == w) app->prog_area = NULL;
    if (g_prog_timer != 0) {
        g_source_remove(g_prog_timer);
        g_prog_timer = 0;
    }
    g_prog_on = 0;
}

/* Starts the sweep while a load is in flight, and on the way out clears the
 * strip by queuing one last draw with g_prog_on already 0.
 *
 * "Reduce motion" is honoured here, in the two places this edition animates:
 * the sweep, which simply is not started, and GTK's own widget animations,
 * which gtk-enable-animations turns off wholesale — a switch sliding, a tab
 * cross-fading.  The strip still appears during a load, with its segment
 * parked mid-way rather than travelling, because a load has to stay visible
 * and a full-width bar would read as "finished" — the one thing it must not
 * say.  Parked and moving are the only two states, so the setting can be
 * changed mid-load and the next call here settles it. */
static void rb_progress_sync(App *app)
{
    int still = rb_pref_int(app, RB_PREF_REDUCED_MOTION, 0) ? 1 : 0;
    int want  = (app->loading && !still) ? 1 : 0;

    if (still) {
        g_prog_phase = RB_PROGRESS_STEPS / 2;
    } else if (want && !g_prog_on) {
        g_prog_phase = 0;
    }

    if (want != g_prog_on) {
        g_prog_on = want;
        if (want) {
            g_prog_timer = g_timeout_add(RB_PROGRESS_MS, rb_progress_tick, app);
        } else if (g_prog_timer != 0) {
            g_source_remove(g_prog_timer);
            g_prog_timer = 0;
        }
    }
    /* Always redrawn, where this once returned early when nothing changed: the
     * phase may have been parked above, and the only caller is a load starting
     * or ending. */
    if (app->prog_area != NULL) gtk_widget_queue_draw(app->prog_area);
}

/* The one place app->loading changes.  Everything that shows loading state —
 * the reload/stop glyph and the progress strip — is driven from here, so the
 * call sites (navigation starting, navigation done, tabs torn down) cannot
 * disagree about whether a page is loading. */
void rb_set_loading(App *app, int on)
{
    app->loading = on ? 1 : 0;
    rb_update_reloadbtn(app);
    rb_progress_sync(app);
}

void rb_update_all(App *app)
{
    rb_tab *t = rb_store_tab(app);
    rb_update_omni(app, t && t->url ? t->url : "");
    rb_update_titlebar(app);
    rb_update_nav(app);
    rb_update_star(app);
    rb_update_reloadbtn(app);
}

/* ------------------------------------------------------------------ */
/* Actions */

static void rb_do_reload_or_stop(App *app)
{
    if (app->loading) rb_gw_stop(app);
    else              rb_gw_reload(app);
}

void rb_do_new_tab(App *app)
{
    rb_do_add_tab(app, NULL);
}

/* Opens a tab at `url`, or at the profile's homepage when `url` is NULL or
 * blank.  Everything that adds a tab goes through here so the notebook, the
 * app->tabs array and the rb_tabs store can never disagree on the index. */
void rb_do_add_tab(App *app, const char *url)
{
    WebKitWebView *wv;
    GtkWidget *hbox, *lbl, *close, *icon;
    const char *home = app->home_url ? app->home_url : "https://duckduckgo.com";
    /* A tab opened without an address lands on the homepage, unless the
     * profile turned that off — in which case it opens empty.  Reading the
     * switch here is what makes it real: it used to be stored and ignored. */
    const char *target = (url && url[0]) ? url
                       : (rb_pref_int(app, RB_PREF_HOMEPAGE_ENABLED, 1)
                              ? home : "about:blank");
    long id;
    int idx;

    wv = rb_gw_new_view(app);
    if (!wv) return;

    hbox = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    lbl = gtk_label_new("New Tab");
    gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
    gtk_label_set_width_chars(GTK_LABEL(lbl), 10);
    /* The favicon slot.  It starts empty and hidden and is filled in by the
     * view's "notify::favicon" handler, so a page that never publishes one
     * costs the tab no width at all. */
    icon = gtk_image_new();
    gtk_widget_set_size_request(icon, RB_TAB_ICON, RB_TAB_ICON);
    gtk_widget_set_valign(icon, GTK_ALIGN_CENTER);
    close = gtk_button_new_with_label("\xC3\x97");
    rb_add_class(close, "rb-tab-close");
    g_signal_connect(close, "clicked", G_CALLBACK(on_tab_close_clicked), app);
    gtk_box_pack_start(GTK_BOX(hbox), icon, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(hbox), lbl, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(hbox), close, FALSE, FALSE, 0);

    if (app->tabs_n == app->tabs_cap) {
        int cap = app->tabs_cap ? app->tabs_cap * 2 : 8;
        GtkTab *tabs = (GtkTab *)realloc(app->tabs, (size_t)cap * sizeof(GtkTab));
        if (!tabs) {
            gtk_widget_destroy(hbox);
            gtk_widget_destroy(GTK_WIDGET(wv));
            return;
        }
        app->tabs = tabs;
        app->tabs_cap = cap;
    }

    id = rb_tabs_add(app->store, "New Tab", target, rb_profile_now_ms());
    app->tabs[app->tabs_n].id = id;
    app->tabs[app->tabs_n].wv = wv;
    app->tabs[app->tabs_n].label = lbl;
    app->tabs[app->tabs_n].close_btn = close;
    app->tabs[app->tabs_n].icon = icon;
    app->tabs_n++;

    app->silent = 1;
    idx = gtk_notebook_append_page(GTK_NOTEBOOK(app->notebook), GTK_WIDGET(wv), hbox);
    app->silent = 0;
    gtk_widget_show_all(hbox);
    gtk_widget_show(GTK_WIDGET(wv));

    app->active_id = id;
    if (idx >= 0) {
        gtk_notebook_set_current_page(GTK_NOTEBOOK(app->notebook), idx);
    }
    rb_update_all(app);
    webkit_web_view_load_uri(wv, target);
}

void rb_do_close_tab_id(App *app, long id)
{
    int i;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == id) break;
    }
    if (i >= app->tabs_n) return;

    app->silent = 1;
    gtk_notebook_remove_page(GTK_NOTEBOOK(app->notebook), i);
    app->silent = 0;
    memmove(&app->tabs[i], &app->tabs[i + 1],
            (size_t)(app->tabs_n - i - 1) * sizeof(app->tabs[0]));
    app->tabs_n--;
    rb_tabs_close(app->store, id, rb_profile_now_ms());
    if (app->active_id == id) app->active_id = 0;

    if (app->tabs_n == 0) {
        rb_do_new_tab(app);
        return;
    }
    if (app->active_id == 0) {
        gint cur = gtk_notebook_get_current_page(GTK_NOTEBOOK(app->notebook));
        if (cur >= 0 && cur < app->tabs_n) {
            app->active_id = app->tabs[cur].id;
        }
    }
    rb_update_all(app);
}

/* Android's TranslateDialog, minus the dialog: the target language already
 * has a row in Preferences, so the only thing a menu item has to decide is
 * whether to translate this page.  The wrapper URL comes from core, so both
 * editions send the identical request. */
static void rb_do_translate(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->store, app->active_id) : NULL;
    const char *target = rb_pref(app, RB_PREF_TRANSLATE_TARGET, "id");
    char *wrap;

    if (t == NULL || t->url == NULL || t->url[0] == '\0') {
        rb_warn(app, "Cannot translate", "Open a page first.");
        return;
    }
    wrap = rb_url_translate_wrapper(t->url, target);
    if (wrap == NULL) {
        /* Only the browser's own pages get here, which is the same guard
         * Android's dialog makes before it builds the URL. */
        rb_warn(app, "Cannot translate",
                "This is one of the browser's own pages, not a web page.");
        return;
    }
    rb_do_add_tab(app, wrap);
    free(wrap);
}

void rb_do_navigate(App *app, const char *url)
{
    rb_tab *t;
    char *upgraded = NULL;
    int did_upgrade = 0;
    const char *target = url;

    if (!app || !url || !url[0]) return;
    t = rb_store_tab(app);
    if (!t) return;

    /* HTTPS-First.  The omnibox already sends a bare domain straight to
     * https, so what this catches is everything else that is still plain
     * http at load time: a typed http:// URL, a bookmark or homepage entry,
     * a session-restored tab.  The upgrade is recorded so that the failure
     * handler can retry the original once — see rb_gw_new_view's load-failed.
     * With the setting off, http URLs load as typed, exactly as on Android. */
    if (app->https && rb_pref_int(app, RB_PREF_HTTPS_UPGRADE, 1)) {
        upgraded = rb_url_upgrade_to_https(url, &did_upgrade);
        if (did_upgrade && upgraded) {
            rb_https_register(app->https, upgraded, url);
            target = upgraded;
        }
    }

    rb_set_str(&t->url, rb_strdup(target));
    rb_set_loading(app, 1);
    rb_update_omni(app, target);
    rb_gw_navigate(app, target);
    free(upgraded);
}

/* ------------------------------------------------------------------ */
/* The bookmarks bar
 *
 * Brave's bookmarks bar, built from the two shapes the core's ordering
 * already gives us: a bookmark that NAMES a folder becomes a folder menu
 * button, and one without a folder becomes a plain button.  rb_bookmarks'
 * display order groups by folder first (BookmarkDao.observeAll), so folder
 * rows arrive contiguously and one forward walk builds every group — no
 * grouping pass here that could disagree with the order the manager screens
 * show.
 *
 * The bar hides itself when it has nothing to draw and when the profile turns
 * it off, so an empty or disabled bar costs no vertical space.  Buttons mirror
 * Brave's behaviour: left-click navigates the current tab, middle-click opens
 * a background tab, right-click offers the two things a bookmark bar is for.
 */

/* Where a button or menu item keeps its URL.  Object data rather than a
 * captured closure: GTK3 has no closure for a plain callback, and the URL is
 * owned by the store, which any bookmark edit can rewrite under us — so each
 * widget holds its own copy. */
#define RB_BM_URL_KEY "rb-bm-url"

static void rb_bm_children_free(GtkWidget *box)
{
    GList *kids = gtk_container_get_children(GTK_CONTAINER(box));
    GList *it;
    for (it = kids; it != NULL; it = it->next) {
        gtk_widget_destroy(GTK_WIDGET(it->data));
    }
    g_list_free(kids);
}

static const char *rb_bm_url_of(GtkWidget *w)
{
    return (const char *)g_object_get_data(G_OBJECT(w), RB_BM_URL_KEY);
}

static void rb_bm_take_url(GtkWidget *w, const char *url)
{
    g_object_set_data_full(G_OBJECT(w), RB_BM_URL_KEY, g_strdup(url), g_free);
}

static void rb_bm_open(GtkWidget *widget, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(widget);
    if (url && url[0]) rb_do_navigate(app, url);
}

/* Middle-click, the way a Brave bookmark button opens a background tab. */
static gboolean rb_bm_pressed(GtkWidget *widget, GdkEventButton *event,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(widget);
    if (event->button == 2 && url && url[0]) {
        rb_do_add_tab(app, url);
        return TRUE;
    }
    return FALSE;
}

static void rb_bm_open_new(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(GTK_WIDGET(item));
    if (url && url[0]) rb_do_add_tab(app, url);
}

static void rb_bm_remove(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = rb_bm_url_of(GTK_WIDGET(item));
    if (!url || !url[0]) return;
    rb_bookmarks_delete_url(app->bookmarks, url);
    if (app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    rb_update_star(app);
    rb_bookmarks_bar_refresh(app);
}

static gboolean rb_bm_context(GtkWidget *widget, GdkEvent *event,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    GdkEventButton *be = (GdkEventButton *)event;
    const char *url = rb_bm_url_of(widget);
    GtkWidget *menu, *item;

    if (event->type != GDK_BUTTON_PRESS || be->button != 3) return FALSE;
    if (!url || !url[0]) return FALSE;

    menu = gtk_menu_new();

    item = gtk_menu_item_new_with_label("Open in new tab");
    rb_bm_take_url(item, url);
    g_signal_connect(item, "activate", G_CALLBACK(rb_bm_open_new), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Remove bookmark");
    rb_bm_take_url(item, url);
    g_signal_connect(item, "activate", G_CALLBACK(rb_bm_remove), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_widget_show_all(menu);
    /* A popup menu is not owned by anything, so it has to destroy itself once
     * a choice has been made or dismissed. */
    g_signal_connect_swapped(menu, "selection-done",
                             G_CALLBACK(gtk_widget_destroy), menu);
    gtk_menu_popup_at_pointer(GTK_MENU(menu), event);
    return TRUE;
}

static GtkWidget *rb_bm_button(App *app, const rb_bookmark *bm)
{
    const char *text = (bm->title && bm->title[0]) ? bm->title : bm->url;
    GtkWidget *btn = gtk_button_new_with_label(text);
    GtkWidget *lbl = gtk_bin_get_child(GTK_BIN(btn));

    gtk_button_set_relief(GTK_BUTTON(btn), GTK_RELIEF_NONE);
    rb_add_class(btn, "rb-bmbtn");
    if (lbl) {
        gtk_label_set_ellipsize(GTK_LABEL(lbl), PANGO_ELLIPSIZE_END);
        gtk_label_set_max_width_chars(GTK_LABEL(lbl), 24);
    }
    gtk_widget_set_tooltip_text(btn, bm->url);
    rb_bm_take_url(btn, bm->url);
    g_signal_connect(btn, "clicked", G_CALLBACK(rb_bm_open), app);
    g_signal_connect(btn, "button-press-event", G_CALLBACK(rb_bm_pressed), app);
    g_signal_connect(btn, "button-press-event", G_CALLBACK(rb_bm_context), app);
    return btn;
}

static void rb_bookmarks_bar_refresh(App *app)
{
    int i, n, shown = 0;

    if (!app || !app->bmbar) return;

    rb_bm_children_free(app->bmbar);

    if (!rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1)) {
        gtk_widget_hide(app->bmbar);
        return;
    }

    n = rb_bookmarks_count(app->bookmarks);
    for (i = 0; i < n; ) {
        const rb_bookmark *bm = rb_bookmarks_at(app->bookmarks, i);
        if (!bm || !bm->url || !bm->url[0]) { i++; continue; }

        if (bm->folder && bm->folder[0]) {
            /* Folder rows are contiguous, so this group runs until the folder
             * name changes — or until a folder-less row, which sorts last. */
            const char *folder = bm->folder;
            GtkWidget *menu = gtk_menu_new();
            GtkWidget *btn = gtk_menu_button_new();

            while (i < n) {
                const rb_bookmark *f = rb_bookmarks_at(app->bookmarks, i);
                GtkWidget *item;
                if (!f || !f->url || !f->url[0]) { i++; continue; }
                if (!f->folder || strcmp(f->folder, folder) != 0) break;
                item = gtk_menu_item_new_with_label(
                    (f->title && f->title[0]) ? f->title : f->url);
                rb_bm_take_url(item, f->url);
                g_signal_connect(item, "activate",
                                 G_CALLBACK(rb_bm_open), app);
                gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
                i++;
            }

            gtk_widget_show_all(menu);
            gtk_button_set_label(GTK_BUTTON(btn), folder);
            gtk_button_set_relief(GTK_BUTTON(btn), GTK_RELIEF_NONE);
            rb_add_class(btn, "rb-bmbtn");
            gtk_widget_set_tooltip_text(btn, folder);
            /* Same deprecation as rb_build_menu: the classic GtkMenu is kept
             * deliberately, and is fully functional on GTK 3.x. */
            G_GNUC_BEGIN_IGNORE_DEPRECATIONS
            gtk_menu_button_set_popup(GTK_MENU_BUTTON(btn), menu);
            G_GNUC_END_IGNORE_DEPRECATIONS
            /* The button does not own what set_popup() attached, and this bar
             * throws its buttons away on every refresh — so the menu is tied
             * to the button's lifetime explicitly rather than left to leak a
             * widget tree per starred page. */
            g_signal_connect_swapped(btn, "destroy",
                                     G_CALLBACK(gtk_widget_destroy), menu);
            gtk_box_pack_start(GTK_BOX(app->bmbar), btn, FALSE, FALSE, 0);
        } else {
            gtk_box_pack_start(GTK_BOX(app->bmbar), rb_bm_button(app, bm),
                               FALSE, FALSE, 0);
            i++;
        }
        shown++;
    }

    /* A child packed into an already-shown container starts hidden, so the
     * refresh shows its own work; and a bar with nothing in it goes away
     * rather than leaving a bare strip of background. */
    if (shown) gtk_widget_show_all(app->bmbar);
    else gtk_widget_hide(app->bmbar);
}

void rb_do_toggle_bookmark(App *app)
{
    rb_tab *t = rb_store_tab(app);
    if (!t || !t->url || !t->url[0]) return;
    /* BrowserViewModel.toggleBookmark, policy included: the same call stars
     * and unstars, and a blank title falls back to the URL. */
    (void)rb_bookmarks_toggle(app->bookmarks, t->url,
                              t->title && t->title[0] ? t->title : t->url,
                              rb_profile_now_ms());
    if (app->path_bookmarks) {
        rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    }
    rb_update_star(app);
    /* Starring is how a page reaches the bar, so the bar follows it live. */
    rb_bookmarks_bar_refresh(app);
}

/* ------------------------------------------------------------------ */
/* Find in page
 *
 * Android's FindInPageBar: a field, previous, next and close, with the search
 * re-running on every keystroke.  The engine half is WebKit's find controller,
 * the GTK twin of the findAllAsync / window.find pair Android drives, and it
 * both highlights the matches and reports how many there are.
 *
 * The options are Android's, read off its calls: window.find() is passed
 * caseSensitive=false and wrapAround=true there, so the search here is
 * case-insensitive and wraps.
 *
 * Two things differ from Android's bar, both visible and both on purpose:
 *
 *  - The count is displayed.  Android discards findAllAsync's result and its
 *    bar says nothing; WebKit hands the number over for free, and the number
 *    is most of why somebody opens the bar.  It is the TOTAL only — WebKit2GTK
 *    does not report which match is current, so "3 of 12" is not available
 *    here and is not faked.
 *  - The bar belongs to the window while the search belongs to a tab.
 *    Switching tabs with the bar open finishes the search on the tab being
 *    left, so its highlights go with it, and re-runs the same text in the one
 *    arriving — which is what switching tabs with the bar open asks for.
 *
 * A tab id is remembered rather than a pointer: tabs are closed under us, and
 * a stale id simply finds no tab.
 */

static WebKitWebView *rb_find_view(App *app)
{
    GtkTab *t = rb_active_tab(app);
    return (t != NULL) ? t->wv : NULL;
}

static WebKitWebView *rb_find_view_for(App *app, long id)
{
    int i;

    if (id == 0) return NULL;
    for (i = 0; i < app->tabs_n; i++) {
        if (app->tabs[i].id == id) return app->tabs[i].wv;
    }
    return NULL;
}

static WebKitFindController *rb_find_ctl(WebKitWebView *wv)
{
    return (wv != NULL) ? webkit_web_view_get_find_controller(wv) : NULL;
}

static void rb_find_set_count(App *app, guint matches)
{
    char buf[48];

    if (app->find_label == NULL) return;
    /* WebKit normally reports an empty result through failed-to-find-text, but
     * a zero count is spelled the same way rather than as "0 matches". */
    if (matches == 0) {
        gtk_label_set_text(GTK_LABEL(app->find_label), "No matches");
        return;
    }
    /* One match is not "1 matches"; the count is the one place a user reads
     * this bar closely enough to notice. */
    snprintf(buf, sizeof buf, (matches == 1) ? "%u match" : "%u matches",
             (unsigned)matches);
    gtk_label_set_text(GTK_LABEL(app->find_label), buf);
}

static void rb_find_set_none(App *app)
{
    if (app->find_label != NULL) {
        gtk_label_set_text(GTK_LABEL(app->find_label), "No matches");
    }
}

static void rb_find_clear_label(App *app)
{
    /* Empty rather than "0 matches": a bar whose field was just cleared has
     * not searched for anything, and saying it found none would be a lie. */
    if (app->find_label != NULL) gtk_label_set_text(GTK_LABEL(app->find_label), "");
}

/* Finishes the search on every view that could still be carrying one.  Both
 * calls are safe when there is nothing to finish, and doing both means the
 * bar never leaves a highlight behind on the tab it was pointed at before the
 * last switch. */
static void rb_find_finish(App *app)
{
    WebKitFindController *fc;

    fc = rb_find_ctl(rb_find_view_for(app, app->find_id));
    if (fc != NULL) webkit_find_controller_search_finish(fc);
    fc = rb_find_ctl(rb_find_view(app));
    if (fc != NULL) webkit_find_controller_search_finish(fc);
    app->find_id = 0;
}

/* The search the entry's text asks for, from the top. */
static void rb_find_run(App *app)
{
    WebKitWebView *wv = rb_find_view(app);
    GtkTab *t = rb_active_tab(app);
    WebKitFindController *fc;
    const char *text;

    if (wv == NULL || t == NULL) return;
    fc = rb_find_ctl(wv);
    if (fc == NULL) return;

    /* Leaving a tab behind takes its highlights with it: they belong to the
     * bar, and the bar is now showing the tab that arrived. */
    if (app->find_id != 0 && app->find_id != t->id) {
        WebKitFindController *ofc = rb_find_ctl(rb_find_view_for(app, app->find_id));
        if (ofc != NULL) webkit_find_controller_search_finish(ofc);
        app->find_id = 0;
    }

    text = (app->find_entry != NULL)
               ? gtk_entry_get_text(GTK_ENTRY(app->find_entry)) : NULL;
    if (text == NULL || text[0] == '\0') {
        webkit_find_controller_search_finish(fc);
        app->find_id = 0;
        rb_find_clear_label(app);
        return;
    }

    app->find_id = t->id;
    webkit_find_controller_search(fc, text,
                                  WEBKIT_FIND_OPTIONS_CASE_INSENSITIVE |
                                  WEBKIT_FIND_OPTIONS_WRAP_AROUND,
                                  G_MAXUINT);
}

/* One more match in `forward`'s direction, or a fresh search when the text in
 * the field is not the text the controller is already holding — which is what
 * makes Enter after typing do the right thing rather than step a search for
 * the previous word.  The controller remembers its own query, so nothing here
 * has to. */
static void rb_find_step(App *app, gboolean forward)
{
    WebKitWebView *wv = rb_find_view(app);
    WebKitFindController *fc = rb_find_ctl(wv);
    const char *text, *current;

    if (fc == NULL || app->find_entry == NULL) return;
    text = gtk_entry_get_text(GTK_ENTRY(app->find_entry));
    if (text == NULL || text[0] == '\0') return;
    current = webkit_find_controller_get_search_text(fc);
    if (current == NULL || strcmp(current, text) != 0) {
        rb_find_run(app);
        return;
    }
    if (forward) webkit_find_controller_search_next(fc);
    else         webkit_find_controller_search_previous(fc);
}

static void rb_find_hide(App *app)
{
    WebKitWebView *wv = rb_find_view(app);

    rb_find_finish(app);
    rb_find_clear_label(app);
    if (app->findbar != NULL) gtk_widget_hide(app->findbar);
    /* The keyboard goes back where it was before Ctrl+F took it. */
    if (wv != NULL) gtk_widget_grab_focus(GTK_WIDGET(wv));
}

static void rb_find_show(App *app)
{
    if (app->findbar == NULL) return;
    gtk_widget_show_all(app->findbar);
    gtk_widget_grab_focus(app->find_entry);
    gtk_editable_select_region(GTK_EDITABLE(app->find_entry), 0, -1);
    /* Text left over from last time is re-run rather than shown as a stale
     * count: the page may have changed while the bar was closed. */
    rb_find_run(app);
}

static gboolean rb_find_is_open(App *app)
{
    if (app->findbar == NULL) return FALSE;
    return gtk_widget_get_visible(app->findbar);
}

/* ------------------------------------------------------------------ */
/* Find in page: the bar itself, and its callbacks */

static void on_find_changed(GtkEditable *editable, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)editable;
    rb_find_run(app);
}

static void on_find_prev(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_find_step(app, FALSE);
}

static void on_find_next(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_find_step(app, TRUE);
}

static void on_find_close(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_find_hide(app);
}

/* Enter steps forward, Shift+Enter back, Escape closes — what every other
 * find bar answers to.  Returning FALSE for everything else leaves the entry
 * its ordinary editing keys. */
static gboolean on_find_key(GtkWidget *widget, GdkEventKey *event, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)widget;

    if (event->keyval == GDK_KEY_Escape) {
        rb_find_hide(app);
        return TRUE;
    }
    if (event->keyval == GDK_KEY_Return || event->keyval == GDK_KEY_KP_Enter) {
        rb_find_step(app, (event->state & GDK_SHIFT_MASK) ? FALSE : TRUE);
        return TRUE;
    }
    return FALSE;
}

/* Built directly under the page — packed after the notebook, so it opens
 * between the page and the toolbar rather than over either of them. */
static void rb_find_build(App *app, GtkWidget *root)
{
    GtkWidget *prev, *next, *close;

    app->findbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    rb_add_class(app->findbar, "rb-findbar");

    app->find_entry = gtk_entry_new();
    rb_add_class(app->find_entry, "rb-omni");
    gtk_entry_set_placeholder_text(GTK_ENTRY(app->find_entry), "Find in page");
    gtk_widget_set_hexpand(app->find_entry, TRUE);
    g_signal_connect(app->find_entry, "changed",
                     G_CALLBACK(on_find_changed), app);
    g_signal_connect(app->find_entry, "key-press-event",
                     G_CALLBACK(on_find_key), app);

    app->find_label = gtk_label_new("");
    rb_add_class(app->find_label, "rb-dim");
    /* The count is what the label is for, so it must not stretch the bar. */
    gtk_label_set_ellipsize(GTK_LABEL(app->find_label), PANGO_ELLIPSIZE_END);
    gtk_label_set_max_width_chars(GTK_LABEL(app->find_label), 14);

    prev = gtk_button_new_with_label("\xE2\x86\x91");
    next = gtk_button_new_with_label("\xE2\x86\x93");
    close = gtk_button_new_with_label("\xC3\x97");
    rb_add_class(prev, "rb-btn");
    rb_add_class(next, "rb-btn");
    rb_add_class(close, "rb-btn");
    gtk_widget_set_tooltip_text(prev, "Previous match (Shift+Enter)");
    gtk_widget_set_tooltip_text(next, "Next match (Enter)");
    gtk_widget_set_tooltip_text(close, "Close find bar (Escape)");
    g_signal_connect(prev, "clicked", G_CALLBACK(on_find_prev), app);
    g_signal_connect(next, "clicked", G_CALLBACK(on_find_next), app);
    g_signal_connect(close, "clicked", G_CALLBACK(on_find_close), app);

    gtk_box_pack_start(GTK_BOX(app->findbar), app->find_entry, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(app->findbar), app->find_label, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app->findbar), prev, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app->findbar), next, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(app->findbar), close, FALSE, FALSE, 0);

    gtk_box_pack_start(GTK_BOX(root), app->findbar, FALSE, FALSE, 0);
}

/* Both callbacks below are per view and every view has its own controller, so
 * a background tab finishing a search would otherwise relabel a bar that is
 * showing a different page.  The controller knows which view it belongs to,
 * which is what the check is against — no per-view state to keep. */
static void on_find_counted(WebKitFindController *fc, guint matches,
                            gpointer user_data)
{
    App *app = (App *)user_data;
    if (webkit_find_controller_get_web_view(fc) != rb_find_view(app)) return;
    rb_find_set_count(app, matches);
}

static void on_find_failed(WebKitFindController *fc, gpointer user_data)
{
    App *app = (App *)user_data;
    if (webkit_find_controller_get_web_view(fc) != rb_find_view(app)) return;
    rb_find_set_none(app);
}

void rb_find_watch(App *app, WebKitWebView *wv)
{
    WebKitFindController *fc = rb_find_ctl(wv);

    if (fc == NULL) return;
    g_signal_connect(fc, "counted-matches", G_CALLBACK(on_find_counted), app);
    g_signal_connect(fc, "failed-to-find-text", G_CALLBACK(on_find_failed), app);
}

/* ------------------------------------------------------------------ */
/* Profile switching
 *
 * The seven-step protocol lives in rb_switch (a port of
 * ProfileSwitchStateMachine); this is the platform half.  Android restarts
 * the browser process at the end because WebView's data-directory suffix is
 * process-wide — on GTK each profile owns a WebKitWebContext built on its own
 * WebsiteDataManager, so the same protocol runs in-process and the destroy
 * before load rule still holds: a context is never reused for another
 * profile. */

/* Closed tabs and the tab list of a profile are desktop-only keys: Android
 * keeps tabs in a per-profile Room database, the desktop keeps them in the
 * profile's own settings. */
#define RB_PREF_OPEN_TABS_LOCAL  "open_tabs"    /* '\n'-separated URLs */
#define RB_PREF_ACTIVE_TAB_LOCAL "active_tab"   /* index into open_tabs  */

static void rb_tabs_save_state(App *app)
{
    size_t cap = 1;
    char *joined;
    char *w;
    int i;
    int active = 0;
    int written = 0;

    /* Sizing pass, then one write: the URLs can be long and there are as
     * many of them as the user has tabs. */
    for (i = 0; i < app->tabs_n; i++) {
        rb_tab *t = rb_tabs_get(app->store, app->tabs[i].id);
        if (t && t->url && t->url[0]) cap += strlen(t->url) + 1;
    }
    joined = (char *)malloc(cap);
    if (joined == NULL) return;
    w = joined;
    for (i = 0; i < app->tabs_n; i++) {
        rb_tab *t = rb_tabs_get(app->store, app->tabs[i].id);
        size_t n;
        if (!t || !t->url || !t->url[0]) continue;
        /* The index has to be into what was WRITTEN, not into app->tabs:
         * the restore reads it back positionally. */
        if (app->tabs[i].id == app->active_id) active = written;
        n = strlen(t->url);
        memcpy(w, t->url, n);
        w += n;
        *w++ = '\n';
        written++;
    }
    *w = '\0';

    rb_pref_set(app, RB_PREF_OPEN_TABS_LOCAL, joined);
    rb_pref_set_int(app, RB_PREF_ACTIVE_TAB_LOCAL, active);
    free(joined);
}

static void rb_tabs_restore_state(App *app)
{
    const char *joined = rb_pref(app, RB_PREF_OPEN_TABS_LOCAL, NULL);
    int want = rb_pref_int(app, RB_PREF_ACTIVE_TAB_LOCAL, 0);
    int opened = 0;

    if (joined != NULL && joined[0] != '\0') {
        const char *a = joined;
        while (*a != '\0') {
            const char *b = strchr(a, '\n');
            size_t n = (b != NULL) ? (size_t)(b - a) : strlen(a);
            if (n > 0) {
                char *url = (char *)malloc(n + 1);
                if (url == NULL) break;
                memcpy(url, a, n);
                url[n] = '\0';
                rb_do_add_tab(app, url);
                free(url);
                opened++;
            }
            if (b == NULL) break;
            a = b + 1;
        }
    }
    if (opened == 0) {
        rb_do_add_tab(app, NULL);   /* a fresh profile opens on the homepage */
        return;
    }
    if (want >= 0 && want < app->tabs_n) {
        app->active_id = app->tabs[want].id;
        app->silent = 1;
        gtk_notebook_set_current_page(GTK_NOTEBOOK(app->notebook), want);
        app->silent = 0;
        rb_update_all(app);
    }
}

/* Removes every tab and its view.  The views must be gone before the context
 * they were built on is released. */
static void rb_tabs_destroy_all(App *app)
{
    app->silent = 1;
    while (app->tabs_n > 0) {
        gtk_notebook_remove_page(GTK_NOTEBOOK(app->notebook), app->tabs_n - 1);
        app->tabs_n--;
    }
    app->silent = 0;
    app->active_id = 0;
    rb_set_loading(app, 0);
    while (rb_tabs_count(app->store) > 0) {
        const rb_tab *t = rb_tabs_at(app->store, 0);
        long id;
        if (t == NULL) break;
        id = t->id;   /* close() frees the row this pointer names */
        rb_tabs_close(app->store, id, rb_profile_now_ms());
    }
    rb_tabs_forget_closed(app->store);
    rb_https_clear(app->https);
    rb_update_all(app);
}

static void rb_msg(App *app, GtkMessageType type, const char *title,
                   const char *body)
{
    GtkWidget *dlg;

    /* A policy decision can fire before the window exists; a message that
     * cannot be shown must still not be lost. */
    if (app == NULL || app->win == NULL) {
        fprintf(stderr, "roombrowser: %s%s%s\n", title ? title : "",
                (body && body[0]) ? ": " : "", (body && body[0]) ? body : "");
        return;
    }
    dlg = gtk_message_dialog_new(GTK_WINDOW(app->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        type, GTK_BUTTONS_CLOSE, "%s", title ? title : "");
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dlg), "%s",
                                             body ? body : "");
    g_signal_connect_swapped(dlg, "response", G_CALLBACK(gtk_widget_destroy), dlg);
    gtk_widget_show(dlg);
}

void rb_warn(App *app, const char *title, const char *body)
{
    rb_msg(app, GTK_MESSAGE_WARNING, title, body);
}

static void rb_switch_error_dialog(App *app, const char *title,
                                   const char *body)
{
    rb_msg(app, GTK_MESSAGE_ERROR, title, body);
}

void rb_do_switch_profile(App *app, const char *to_id)
{
    char from_id[RB_PROFILE_ID_LEN + 1];
    int rc;
    int step;

    if (app == NULL || app->switcher == NULL || to_id == NULL) return;
    if (app->active_profile_id == NULL) return;
    if (strcmp(app->active_profile_id, to_id) == 0) return;

    /* Copy the id out: the switch replaces app->active_profile_id, and the
     * registry row it points into can be relocated by a mutation. */
    snprintf(from_id, sizeof from_id, "%s", app->active_profile_id);

    /* Begin requires IDLE, and a finished switch stays COMPLETE until it is
     * reset — so the log is cleared here rather than before each step. */
    rb_switch_reset(app->switcher);
    rc = rb_switch_begin(app->switcher, from_id, to_id);
    if (rc != 0) {
        const char *why = (rc == RB_SWITCH_ERR_BUSY) ? "a switch is already running"
                        : (rc == RB_SWITCH_ERR_SAME) ? "that profile is already active"
                                                     : "no profile was given";
        rb_switch_error_dialog(app, "Cannot switch profile", why);
        return;
    }

    while ((step = rb_switch_step_of(app->switcher)) >= 0) {
        switch (step) {
        case RB_SWITCH_STOP_NAVIGATION:
            {
                int i;
                for (i = 0; i < app->tabs_n; i++) {
                    if (app->tabs[i].wv) {
                        webkit_web_view_stop_loading(app->tabs[i].wv);
                    }
                }
            }
            break;
        case RB_SWITCH_SAVE_TAB_STATE:
            /* The OLD profile is still active, so the tabs land in its own
             * settings — which is the whole point of the step. */
            rb_tabs_save_state(app);
            break;
        case RB_SWITCH_DESTROY_BROWSER_CONTEXT:
            rb_tabs_destroy_all(app);
            rb_gw_context_free(app);
            /* The context that owned this profile's transfers is gone with it,
             * so any row of this profile still marked QUEUED or RUNNING is
             * describing a download nothing is carrying any more.  Done here
             * rather than later because rb_data_shutdown, a few steps down,
             * is what writes the store back to disk. */
            rb_downloads_reconcile(app->downloads, from_id,
                                   "Profile switched");
            break;
        case RB_SWITCH_FLUSH_PROFILE_STATE:
            rb_data_shutdown(app);
            /* The notes and 2FA windows keep stores of their own: flush
             * them here too, while the OLD profile is still the
             * destination. */
            rb_notes_flush(app);
            rb_totp_flush(app);
            break;
        case RB_SWITCH_RELEASE_PROFILE_RESOURCES:
            /* The in-memory stores that were the old profile's view of the
             * world.  History and bookmarks are installation-wide on the
             * desktop, so they stay; the session state does not. */
            rb_https_clear(app->https);
            break;
        case RB_SWITCH_LOAD_NEW_PROFILE_CONTEXT:
            rb_set_str(&app->active_profile_id, rb_strdup(to_id));
            rb_profile_touch(app->profiles, to_id);
            rb_gw_context_new(app);
            /* Re-read everything the new profile owns. */
            app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
            rb_set_str(&app->home_url,
                       rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                         "https://duckduckgo.com")));
            rb_set_str(&app->download_dir, NULL);
            rb_downloads_dir_init(app);
            rb_notes_reload(app);      /* the new profile's notes */
            rb_totp_reload(app);       /* ...and its 2FA accounts */
            rb_css_load(app);          /* the new profile's theme */
            rb_apply_font_scale(app);  /* ...and its font scale */
            rb_apply_reduced_motion(app);
            rb_progress_sync(app);     /* ...and whether a load may animate */
            if (g_js_item != NULL) {
                gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(g_js_item),
                                               app->js_enabled ? TRUE : FALSE);
            }
            break;
        case RB_SWITCH_RESTORE_NEW_PROFILE_TABS:
            rb_tabs_restore_state(app);
            break;
        default:
            break;
        }
        if (rb_switch_step_done(app->switcher) != 0) break;
    }

    if (rb_switch_state_of(app->switcher) == RB_SWITCH_FAILED) {
        const char *err = rb_switch_error(app->switcher);
        rb_switch_error_dialog(app, "Profile switch failed",
                               err ? err : "unknown error");
    }
    /* Leave the machine ready for the next switch.  reset() clears the
     * snapshot, so it is read above, before this. */
    rb_switch_reset(app->switcher);
    rb_update_all(app);
}

/* ------------------------------------------------------------------ */
/* Signal callbacks */

static void on_switch_page(GtkNotebook *notebook, GtkWidget *page,
                            guint page_num, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)notebook;
    (void)page;
    if (app->silent) return;
    if ((int)page_num < app->tabs_n) {
        app->active_id = app->tabs[page_num].id;
        rb_update_all(app);
        /* A find bar left open follows the user to the tab that just came
         * forward: the old tab's highlights are finished off and the same
         * text is searched for here. */
        if (rb_find_is_open(app)) rb_find_run(app);
    }
}

static void on_tab_close_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkTab *gt = rb_tab_by_widget(app, GTK_WIDGET(button));
    if (gt) rb_do_close_tab_id(app, gt->id);
}

static void on_newtab_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)button;
    rb_do_new_tab(app);
}

static void on_nav_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *w = GTK_WIDGET(button);
    if (w == app->back) {
        rb_gw_back(app);
    } else if (w == app->fwd) {
        rb_gw_forward(app);
    } else if (w == app->reload) {
        rb_do_reload_or_stop(app);
    } else if (w == app->home) {
        rb_do_navigate(app, app->home_url ? app->home_url
                                          : "https://duckduckgo.com");
    } else if (w == app->star) {
        rb_do_toggle_bookmark(app);
    }
}

static void on_omni_activate(GtkEntry *entry, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *text = gtk_entry_get_text(entry);
    const char *engine;
    char *url;
    int kind = RB_URL_INPUT_WEB;

    if (!text || !text[0]) return;

    /* The profile's engine.  A NULL or unknown id resolves to the default
     * inside rb_search_resolve, so no fallback is spelled here. */
    engine = rb_pref(app, RB_PREF_SEARCH_ENGINE, NULL);
    url = rb_url_classify(text, engine, &kind, NULL);
    if (url && url[0]) {
        rb_do_navigate(app, url);
    }
    free(url);
}

static gboolean on_omni_key(GtkWidget *widget, GdkEventKey *event,
                            gpointer user_data)
{
    App *app = (App *)user_data;
    (void)widget;
    if (event->keyval == GDK_KEY_Escape) {
        rb_tab *t = rb_store_tab(app);
        rb_update_omni(app, t && t->url ? t->url : "");
        return TRUE;
    }
    return FALSE;
}

/* ------------------------------------------------------------------ */
/* Hamburger menu */

static void on_menu_new_tab(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_new_tab(app);
}

static void on_menu_bookmark(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_toggle_bookmark(app);
}

static void on_js_toggled(GtkCheckMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    app->js_enabled = gtk_check_menu_item_get_active(item) ? 1 : 0;
    /* The switch belongs to the profile, not to the installation: Android
     * keeps it in ProfileSettings, so two profiles can disagree. */
    rb_pref_set_int(app, RB_PREF_JAVASCRIPT, app->js_enabled);
    rb_gw_apply_js(app);   /* live toggle: applies to the open webviews */
}

static void on_bm_toggled(GtkCheckMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    /* A per-profile preference like the JavaScript switch above, so the bar
     * is shown or hidden for the profile it was chosen in. */
    rb_pref_set_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL,
                    gtk_check_menu_item_get_active(item) ? 1 : 0);
    rb_bookmarks_bar_refresh(app);
}

static void on_dialog_response(GtkWidget *widget, gint response_id,
                               gpointer user_data)
{
    (void)response_id;
    (void)user_data;
    gtk_widget_destroy(widget);
}

/* ------------------------------------------------------------------ */
/* Preferences
 *
 * The desktop counterpart of Android's ProfileSettingsScreen.  Every control
 * writes through rb_pref_set() or rb_pref_set_int(), so a change lands in the
 * PROFILE it was made in — which is the whole point of the per-profile
 * settings the rest of this file reads.
 *
 * After a write the affected subsystem is refreshed immediately rather than
 * at the next navigation, so the switch does what it says while the user is
 * looking at it. */

/* Applies whatever a preference key controls to the running browser.  A key
 * with no live effect (a stored-only setting) falls through to the sweep at
 * the end, which is idempotent and cheap.
 *
 * The comparisons are exact strings, not a switch on the first letter: the
 * keys share initials ("theme" and "translate_target_language", "download_
 * subfolder" and "dns_mode"), and a prefix test would apply the wrong one. */
static void rb_prefs_apply_key(App *app, const char *key)
{
    if (app == NULL || key == NULL) return;

    if (strcmp(key, RB_PREF_JAVASCRIPT) == 0) {
        app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
        rb_gw_apply_js(app);
    } else if (strcmp(key, RB_PREF_HOME_LOCAL) == 0) {
        rb_set_str(&app->home_url,
                   rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                     "https://duckduckgo.com")));
    } else if (strcmp(key, RB_PREF_BOOKMARKS_BAR_LOCAL) == 0) {
        /* The bar is per profile, so a profile switch lands here too. */
        if (g_bm_item != NULL) {
            gtk_check_menu_item_set_active(
                GTK_CHECK_MENU_ITEM(g_bm_item),
                rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1) ? TRUE : FALSE);
        }
        rb_bookmarks_bar_refresh(app);
    } else if (strcmp(key, RB_PREF_UA_MODE) == 0 ||
               strcmp(key, RB_PREF_UA_PRESET_ID) == 0 ||
               strcmp(key, RB_PREF_DEVICE_ID) == 0 ||
               strcmp(key, RB_PREF_SCREEN_SIZE) == 0 ||
               strcmp(key, RB_PREF_SCREEN_WIDTH) == 0 ||
               strcmp(key, RB_PREF_SCREEN_HEIGHT) == 0 ||
               strcmp(key, RB_PREF_CUSTOM_USER_AGENT) == 0) {
        /* One path for all of them: the shim carries the machine half and the
         * screen half, and both are installed by the same walk over the tabs.
         * The screen keys land here rather than getting their own branch so
         * there is exactly one place that decides what a page is told. */
        rb_gw_apply_ua(app);
    } else if (strcmp(key, RB_PREF_THEME) == 0 ||
               strcmp(key, RB_PREF_ACCENT_ARGB) == 0 ||
               strcmp(key, RB_PREF_HIGH_CONTRAST) == 0) {
        /* High contrast is a second way of asking for a different palette, so
         * it repaints through the same path as the theme itself. */
        rb_css_load(app);
    } else if (strcmp(key, RB_PREF_FONT_SCALE) == 0) {
        /* Scales the UI font.  No stylesheet reload: the scale rides on
         * gtk-font-name, which restyles every widget by itself. */
        rb_apply_font_scale(app);
    } else if (strcmp(key, RB_PREF_REDUCED_MOTION) == 0) {
        /* Both halves of the switch: the toolkit's own animations, and the
         * loading strip, which is drawn and so has to be told as well. */
        rb_apply_reduced_motion(app);
        rb_progress_sync(app);
    } else if (strcmp(key, RB_PREF_DOWNLOAD_SUBFOLDER) == 0) {
        /* rb_downloads_dir_init assigns rather than appends, so the old path
         * has to go first. */
        rb_set_str(&app->download_dir, NULL);
        rb_downloads_dir_init(app);
    }

    /* Everything the content blocker's rules or the WebKit view settings
     * depend on is re-applied wholesale: it is idempotent, so there is no
     * need to work out here which switch moved. */
    rb_gw_apply_web_settings(app);
    rb_gw_content_blocking_apply(app);
    rb_profiles_save(app);
}

/* Rows carry their preference key on the widget, so one callback serves every
 * switch in the dialog. */
static void on_pref_switch(GObject *obj, GParamSpec *pspec, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *key = (const char *)g_object_get_data(obj, "rb-pref-key");
    (void)pspec;
    if (key == NULL) return;
    rb_pref_set_int(app, key, gtk_switch_get_active(GTK_SWITCH(obj)) ? 1 : 0);
    rb_prefs_apply_key(app, key);
}

/* A labelled switch row.  `fallback` is what the core's default is, so a
 * profile that never changed the key still shows the right state. */
static void rb_pref_row(GtkWidget *grid, int row, App *app, const char *key,
                        int fallback, const char *title, const char *subtitle)
{
    GtkWidget *label;
    GtkWidget *sw;
    char *markup;

    markup = g_markup_printf_escaped("<b>%s</b>%s%s",
        title,
        (subtitle != NULL) ? "\n" : "",
        (subtitle != NULL) ? subtitle : "");
    label = gtk_label_new(NULL);
    gtk_label_set_markup(GTK_LABEL(label), markup);
    g_free(markup);
    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_label_set_line_wrap(GTK_LABEL(label), TRUE);
    gtk_widget_set_hexpand(label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 1, 1);

    sw = gtk_switch_new();
    gtk_widget_set_valign(sw, GTK_ALIGN_CENTER);
    gtk_switch_set_active(GTK_SWITCH(sw), rb_pref_int(app, key, fallback) ? TRUE : FALSE);
    g_object_set_data_full(G_OBJECT(sw), "rb-pref-key", rb_strdup(key), free);
    g_signal_connect(sw, "notify::active", G_CALLBACK(on_pref_switch), app);
    gtk_grid_attach(GTK_GRID(grid), sw, 1, row, 1, 1);
}

/* A combo row whose options are (id, label) pairs terminated by a NULL id.
 * One callback serves all of them; the key and the value list ride on the
 * widget.
 *
 * `is_theme` marks the one row that is NOT a setting.  The theme ID lives in
 * the profile's theme_json snapshot (see rb_profile_set_theme), so it cannot
 * go through rb_pref_set() with the rest — a distinction that has to be made
 * explicitly, because the two were once the same combo and the theme ID
 * overwrote the MODE setting every time it was changed. */
typedef struct {
    const char *key;
    const char *const *ids;    /* ids[i] pairs with labels[i] */
    const char *const *labels;
    int is_theme;
} rb_pref_choices;

static void on_pref_combo(GtkComboBox *combo, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_pref_choices *ch = (rb_pref_choices *)g_object_get_data(G_OBJECT(combo),
                                                              "rb-pref-choices");
    char *active;
    if (ch == NULL) return;
    active = gtk_combo_box_text_get_active_text(GTK_COMBO_BOX_TEXT(combo));
    if (active == NULL) return;
    /* The visible text is the label; the id is the label's twin by index. */
    {
        int i;
        for (i = 0; ch->labels[i] != NULL; i++) {
            if (strcmp(ch->labels[i], active) == 0) {
                if (ch->is_theme) {
                    /* Into the profile snapshot, then a repaint: the palette
                     * itself changed, which no setting can express. */
                    rb_profile_set_theme(app->profiles, app->active_profile_id,
                                         ch->ids[i]);
                    rb_profiles_save(app);
                    rb_css_load(app);
                } else {
                    rb_pref_set(app, ch->key, ch->ids[i]);
                    rb_prefs_apply_key(app, ch->key);
                }
                break;
            }
        }
    }
    g_free(active);
}

static GtkWidget *rb_pref_combo_row(GtkWidget *grid, int row, App *app,
                                    const char *key, const char *const *ids,
                                    const char *const *labels, const char *current,
                                    const char *title, int is_theme)
{
    GtkWidget *label = gtk_label_new(title);
    GtkWidget *combo = gtk_combo_box_text_new();
    rb_pref_choices *ch = g_new0(rb_pref_choices, 1);
    int i;
    int sel = 0;

    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_widget_set_hexpand(label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 1, 1);

    ch->key = key;
    ch->ids = ids;
    ch->labels = labels;
    ch->is_theme = is_theme;
    for (i = 0; labels[i] != NULL; i++) {
        gtk_combo_box_text_append_text(GTK_COMBO_BOX_TEXT(combo), labels[i]);
        if (current != NULL && ids[i] != NULL && strcmp(ids[i], current) == 0) {
            sel = i;
        }
    }
    gtk_combo_box_set_active(GTK_COMBO_BOX(combo), sel);
    g_object_set_data_full(G_OBJECT(combo), "rb-pref-choices", ch, g_free);
    g_signal_connect(combo, "changed", G_CALLBACK(on_pref_combo), app);
    gtk_grid_attach(GTK_GRID(grid), combo, 1, row, 1, 1);
    return combo;
}

/* A dim paragraph spanning both columns, for a row whose control cannot carry
 * a description of its own — a combo box has no subtitle slot, and a caveat
 * that only appears in a tooltip is a caveat nobody reads. */
static void rb_pref_note_row(GtkWidget *grid, int row, const char *text)
{
    GtkWidget *l = gtk_label_new(text);
    gtk_label_set_xalign(GTK_LABEL(l), 0.0f);
    gtk_label_set_line_wrap(GTK_LABEL(l), TRUE);
    gtk_widget_set_hexpand(l, TRUE);
    gtk_widget_set_opacity(l, 0.72);
    gtk_grid_attach(GTK_GRID(grid), l, 0, row, 2, 1);
}

/* The label a machine is shown under, in the row's button and in every row
 * of the picker: one function, so the two can never disagree about what a
 * device is called.  An empty id and an id the catalogue no longer carries
 * both read as "no device", which is exactly what rb_device_ua_for() does
 * with them. */
static char *rb_device_label(const char *id)
{
    const rb_device *d = rb_device_by_id(id);

    if (d == NULL) {
        return g_strdup("No device — use the User-Agent setting");
    }
    return g_strdup_printf("%s %s — %s (%d)", d->brand, d->model,
                           rb_device_os_name(d->os), d->year);
}

/* The name of a DIFFERENT profile already presenting this machine, or NULL.
 * The active profile is skipped — its own machine is what the row already
 * shows, and calling a profile's device a repeat of itself would be nonsense
 * — and so is "no device", which any number of profiles may share without
 * one of them being a repeat. */
static const char *rb_device_used_by(App *app, const char *id)
{
    int i, n;

    if (app == NULL || app->profiles == NULL || id == NULL || id[0] == '\0') {
        return NULL;
    }
    n = rb_profile_count(app->profiles);
    for (i = 0; i < n; i++) {
        const rb_profile *p = rb_profile_at(app->profiles, i);
        const char *own;
        if (p == NULL || p->settings == NULL) continue;
        if (app->active_profile_id != NULL &&
            strcmp(p->id, app->active_profile_id) == 0) continue;
        own = rb_settings_get(p->settings, RB_PREF_DEVICE_ID, "");
        if (own != NULL && strcmp(own, id) == 0) {
            return (p->name != NULL) ? p->name : "another profile";
        }
    }
    return NULL;
}

/* Case-insensitive "does haystack contain needle", with the needle already
 * lowered: g_ascii_strdown rather than strcasestr, which is a GNU extension
 * this build has no guarantee of declaring. */
static gboolean rb_contains_ci(const char *hay, const char *needle)
{
    char *low;
    gboolean hit;

    if (hay == NULL) return FALSE;
    low = g_ascii_strdown(hay, -1);
    hit = (strstr(low, needle) != NULL);
    g_free(low);
    return hit;
}

/* What the search field filters on.  The year goes in as text so "2023"
 * finds a year's machines without the user knowing a model name, and the OS
 * is its display name rather than the raw field, so both "Windows" and
 * "Windows 11" find the same rows. */
static gboolean rb_device_matches(const rb_device *d, const char *needle)
{
    char year[16];

    if (needle[0] == '\0') return TRUE;
    snprintf(year, sizeof year, "%d", d->year);
    return rb_contains_ci(d->brand, needle) ||
           rb_contains_ci(d->model, needle) ||
           rb_contains_ci(year, needle) ||
           rb_contains_ci(rb_device_os_name(d->os), needle);
}

/* The row's caption is the setting, always: the initial build and every
 * choice made in the picker come through here, so the button can never show
 * a machine the profile is not presenting.  The User-Agent rows hang off the
 * same call, because a device is what decides whether they apply. */
static void rb_device_caption_set(GtkWidget *button, App *app)
{
    GtkWidget *caption = gtk_bin_get_child(GTK_BIN(button));
    char *text = rb_device_label(rb_pref(app, RB_PREF_DEVICE_ID, ""));

    if (caption != NULL) {
        gtk_label_set_text(GTK_LABEL(caption), text);
    }
    g_free(text);
    rb_ua_followers_update(button, app);
}

/* A picker between the row's button and the catalogue.  The search field and
 * the list are rebuilt from this on every keystroke, and the button is what
 * a choice has to be visible in once the dialog is gone. */
typedef struct {
    GtkWidget *dialog;
    GtkWidget *search;
    GtkWidget *list;
    GtkWidget *button;
    App *app;
} rb_device_picker;

/* One choice.  The id rides on the row so the activation handler needs
 * nothing but the row it was handed. */
static void rb_device_picker_add_row(rb_device_picker *pk, const char *id,
                                     const char *text)
{
    GtkWidget *row = gtk_list_box_row_new();
    GtkWidget *label = gtk_label_new(text);

    gtk_label_set_ellipsize(GTK_LABEL(label), PANGO_ELLIPSIZE_END);
    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    g_object_set_data_full(G_OBJECT(row), "rb-device-id", g_strdup(id), g_free);
    gtk_container_add(GTK_CONTAINER(row), label);
    gtk_list_box_insert(GTK_LIST_BOX(pk->list), row, -1);
}

/* Rebuild the list for the current query, and retitle the dialog with what
 * the query left.  The title is the count the Android picker shows, in the
 * same words: how many machines matched, out of the whole catalogue. */
static void rb_device_picker_refill(rb_device_picker *pk)
{
    const char *text = gtk_entry_get_text(GTK_ENTRY(pk->search));
    char *needle = g_ascii_strdown((text != NULL) ? text : "", -1);
    int total = rb_device_count();
    int shown = 0;
    int i;
    char title[64];
    char *none;
    GList *kids, *it;

    /* Emptied in place rather than rebuilt: the list widget keeps its scroll
     * position between queries, and the search field keeps the focus and the
     * cursor while the user is still typing in it. */
    kids = gtk_container_get_children(GTK_CONTAINER(pk->list));
    for (it = kids; it != NULL; it = it->next) gtk_widget_destroy(GTK_WIDGET(it->data));
    g_list_free(kids);

    /* The first row is a real choice rather than a cancel: it clears the
     * setting, and the User-Agent rows below decide again.  It is in every
     * result, since "no device" is never filtered out — it is not one of the
     * machines the query searches. */
    none = rb_device_label("");
    rb_device_picker_add_row(pk, "", none);
    g_free(none);

    for (i = 0; i < total; i++) {
        const rb_device *d = rb_device_at(i);
        const char *taken;
        char *text_out;

        if (d == NULL || !rb_device_matches(d, needle)) continue;
        shown++;

        /* A machine another profile also presents is marked, not hidden: the
         * user may still choose it, they are just told it is a repeat. */
        taken = rb_device_used_by(pk->app, d->id);
        text_out = rb_device_label(d->id);
        if (taken != NULL) {
            char *marked = g_strdup_printf("%s — in use by %s", text_out, taken);
            g_free(text_out);
            text_out = marked;
        }
        rb_device_picker_add_row(pk, d->id, text_out);
        g_free(text_out);
    }

    snprintf(title, sizeof title, "Device (%d of %d)", shown, total);
    gtk_window_set_title(GTK_WINDOW(pk->dialog), title);
    gtk_widget_show_all(pk->list);
    g_free(needle);
}

static void on_device_search_changed(GtkEntry *entry, gpointer user_data)
{
    rb_device_picker *pk = (rb_device_picker *)user_data;
    (void)entry;
    rb_device_picker_refill(pk);
}

static void on_device_row_activated(GtkListBox *box, GtkListBoxRow *row,
                                    gpointer user_data)
{
    rb_device_picker *pk = (rb_device_picker *)user_data;
    const char *id = (const char *)g_object_get_data(G_OBJECT(row),
                                                     "rb-device-id");
    (void)box;
    if (id == NULL) return;   /* only the rows this picker made carry an id */

    /* The empty id is the "No device" row, and it is written as such: the
     * setting is cleared rather than left as it was. */
    rb_pref_set(pk->app, RB_PREF_DEVICE_ID, id);
    rb_prefs_apply_key(pk->app, RB_PREF_DEVICE_ID);
    rb_device_caption_set(pk->button, pk->app);
    gtk_widget_destroy(pk->dialog);
}

/* Search-and-pick over the whole catalogue.  A combo was the wrong control
 * for it once the catalogue passed a thousand machines: a list that long has
 * no usable ordering, and the type-ahead that stood in for a search only
 * jumps to a prefix.  This is the Android picker's shape — a search field
 * over a list — and it is sized from rb_device_count() like everything else
 * that walks the catalogue. */
static void rb_show_device_picker(GtkWidget *button, App *app)
{
    rb_device_picker *pk = g_new0(rb_device_picker, 1);
    GtkWidget *dlg, *area, *scroll;

    dlg = gtk_dialog_new_with_buttons("Device", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Cancel", GTK_RESPONSE_CANCEL, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 560, 440);
    /* Cancel, Escape and the window close button all land here and all do
     * nothing but close: a half-typed query is not a choice. */
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    pk->dialog = dlg;
    pk->button = button;
    pk->app = app;
    pk->search = gtk_entry_new();
    pk->list = gtk_list_box_new();
    gtk_entry_set_placeholder_text(GTK_ENTRY(pk->search),
                                   "Search brand, model, year or OS");
    gtk_widget_set_hexpand(pk->search, TRUE);
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(pk->list), GTK_SELECTION_NONE);
    g_object_set_data_full(G_OBJECT(dlg), "rb-device-picker", pk, g_free);

    g_signal_connect(pk->search, "changed",
                     G_CALLBACK(on_device_search_changed), pk);
    g_signal_connect(pk->list, "row-activated",
                     G_CALLBACK(on_device_row_activated), pk);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_widget_set_vexpand(scroll, TRUE);
    gtk_container_add(GTK_CONTAINER(scroll), pk->list);

    area = gtk_dialog_get_content_area(GTK_DIALOG(dlg));
    gtk_box_pack_start(GTK_BOX(area), pk->search, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(area), scroll, TRUE, TRUE, 0);

    rb_device_picker_refill(pk);
    gtk_widget_show_all(dlg);
    /* The dialog is opened to be typed in; a picker that opens with the
     * focus on the Cancel button makes the user reach for the mouse. */
    gtk_widget_grab_focus(pk->search);
}

static void on_device_button_clicked(GtkButton *btn, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_show_device_picker(GTK_WIDGET(btn), app);
}

/* The device row: a profile presents a real machine, or it presents nothing
 * and the User-Agent settings below decide.  Choosing a device IS choosing
 * the User-Agent, so the two are one control rather than two that can
 * disagree — but the control is a button showing the choice, which opens the
 * search picker above, because the catalogue is far too long to list. */
static GtkWidget *rb_pref_device_row(GtkWidget *grid, int row, App *app)
{
    GtkWidget *label = gtk_label_new("Device");
    GtkWidget *button = gtk_button_new();
    GtkWidget *caption = gtk_label_new(NULL);

    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_widget_set_hexpand(label, TRUE);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 1, 1);

    gtk_label_set_ellipsize(GTK_LABEL(caption), PANGO_ELLIPSIZE_END);
    gtk_label_set_xalign(GTK_LABEL(caption), 0.0f);
    gtk_container_add(GTK_CONTAINER(button), caption);
    gtk_widget_set_hexpand(button, TRUE);
    gtk_widget_set_tooltip_text(button, "Present this profile as a real machine");
    g_signal_connect(button, "clicked", G_CALLBACK(on_device_button_clicked),
                     app);
    gtk_grid_attach(GTK_GRID(grid), button, 1, row, 1, 1);

    rb_device_caption_set(button, app);
    return button;
}

/* ------------------------------------------------------------------ */
/* Screen size
 *
 * The Android edition's row, with the same two modes.  A profile that
 * presents a workstation while reporting a laptop panel is a contradiction
 * nobody asked for, and that is all this removes; what a claim can and cannot
 * do is the core's decision, documented in rb_devices.h, and the note under
 * the row says the important half of it where the choice is being made.
 *
 * "This display" is the default and the only mode in which nothing is
 * claimed, which is why the numbers are inert while it is selected rather
 * than cleared: switching the row off and on again must not resurrect a size
 * the user has already left behind, and a stored number that outlived its
 * mode is exactly how that would happen. */

/* This display's size in CSS pixels.  GDK reports device pixels and the scale
 * factor separately, and a page is told CSS pixels, so the two are divided
 * here.  The scale is floored at 1, because a monitor reporting 0 would
 * otherwise divide by zero.
 *
 * Both outputs are 0 when there is no monitor to ask — a session with no
 * display at all.  That is left as 0 rather than guessed at, and the row shows
 * empty fields for it: a number invented here would be a screen size the
 * browser is not using, which is the one thing this row must not show. */
static void rb_screen_real_size(int *w, int *h)
{
    GdkDisplay *dpy = gdk_display_get_default();
    GdkMonitor *mon = (dpy != NULL) ? gdk_display_get_primary_monitor(dpy) : NULL;
    GdkRectangle geo;
    int scale;

    *w = 0;
    *h = 0;
    if (mon == NULL && dpy != NULL) {
        mon = gdk_display_get_monitor(dpy, 0);
    }
    if (mon == NULL) {
        return;
    }
    gdk_monitor_get_geometry(mon, &geo);
    scale = gdk_monitor_get_scale_factor(mon);
    if (scale < 1) {
        scale = 1;
    }
    *w = geo.width / scale;
    *h = geo.height / scale;
    if (*w < 1) *w = 1;
    if (*h < 1) *h = 1;
}

typedef struct {
    App *app;
    GtkWidget *numbers;  /* the two fields, shown only in manual mode */
    GtkWidget *width;
    GtkWidget *height;
} rb_screen_row;

/* The size a field holds, or 0 when it does not hold one.  The leading number
 * is what counts: a pasted "1920 px" is a size the user meant, and reading the
 * number out of it is friendlier than refusing it.  Anything that is not a
 * number at all, or is absurdly large, reads as 0 and is refused by the
 * caller. */
static int rb_screen_field_value(GtkWidget *entry)
{
    const char *text = gtk_entry_get_text(GTK_ENTRY(entry));
    long v;

    if (text == NULL) return 0;
    v = strtol(text, NULL, 10);
    if (v < 0 || v > 1000000L) return 0;
    return (int)v;
}

/* Both fields at once.  A pair with nothing in it is left empty rather than
 * filled with a zero the user would have to notice and correct — the one case
 * that produces one is a session with no display to read. */
static void rb_screen_entries_set(rb_screen_row *sr, int w, int h)
{
    char buf[16];

    if (w < 1 || h < 1) {
        gtk_entry_set_text(GTK_ENTRY(sr->width), "");
        gtk_entry_set_text(GTK_ENTRY(sr->height), "");
        return;
    }
    snprintf(buf, sizeof buf, "%d", w);
    gtk_entry_set_text(GTK_ENTRY(sr->width), buf);
    snprintf(buf, sizeof buf, "%d", h);
    gtk_entry_set_text(GTK_ENTRY(sr->height), buf);
}

/* Both boxes are written from the setting, so the row is right whenever it is
 * built — after a profile switch as much as after a mode change.  A stored
 * pair with nothing usable in it opens on this display's own size instead: a
 * manual claim is nearly always a small correction to the real one, and an
 * empty field would make the user look the number up somewhere else. */
static void rb_screen_numbers_sync(rb_screen_row *sr)
{
    int w = 0, h = 0;

    if (sr == NULL || sr->numbers == NULL) return;
    if (!rb_screen_claim_of(rb_pref_store(sr->app), &w, &h)) {
        rb_screen_real_size(&w, &h);
    }
    rb_screen_entries_set(sr, w, h);
}

/* Manual mode is the only mode with numbers to type, so the fields appear and
 * disappear with it.  The setting is read rather than the combo, so the row
 * is right whenever it is rebuilt — including on a profile switch. */
static void rb_screen_manual_sync(rb_screen_row *sr)
{
    const char *mode;

    if (sr == NULL) return;
    mode = rb_pref(sr->app, RB_PREF_SCREEN_SIZE, "real");
    if (sr->numbers != NULL) {
        gtk_widget_set_visible(sr->numbers,
                               (mode != NULL && strcmp(mode, "manual") == 0));
    }
}

static void on_screen_mode_changed(GtkComboBox *combo, gpointer user_data)
{
    rb_screen_row *sr = (rb_screen_row *)user_data;

    (void)combo;
    if (sr == NULL) return;
    /* Connected after the row's own handler, so the setting already holds the
     * new mode by the time the numbers are read back through it. */
    rb_screen_numbers_sync(sr);
    rb_screen_manual_sync(sr);
}

static void on_screen_use_real_clicked(GtkButton *btn, gpointer user_data)
{
    rb_screen_row *sr = (rb_screen_row *)user_data;
    int w = 0, h = 0;

    (void)btn;
    if (sr == NULL) return;
    rb_screen_real_size(&w, &h);
    rb_screen_entries_set(sr, w, h);
}

static void on_screen_apply_clicked(GtkButton *btn, gpointer user_data)
{
    rb_screen_row *sr = (rb_screen_row *)user_data;
    int w, h;

    (void)btn;
    if (sr == NULL) return;
    w = rb_screen_field_value(sr->width);
    h = rb_screen_field_value(sr->height);

    /* Refused rather than clamped: a size the user did not type is a size
     * they will not recognise on the row afterwards, and the range is wide
     * enough that nothing real lands outside it. */
    if (w < RB_SCREEN_PX_MIN || w > RB_SCREEN_PX_MAX ||
        h < RB_SCREEN_PX_MIN || h > RB_SCREEN_PX_MAX) {
        char detail[192];
        snprintf(detail, sizeof detail,
                 "Both numbers have to be between %d and %d pixels.  Left "
                 "unchanged.", RB_SCREEN_PX_MIN, RB_SCREEN_PX_MAX);
        rb_warn(sr->app, "Not a usable screen size", detail);
        rb_screen_numbers_sync(sr);
        return;
    }

    rb_pref_set_int(sr->app, RB_PREF_SCREEN_WIDTH, w);
    rb_pref_set_int(sr->app, RB_PREF_SCREEN_HEIGHT, h);
    /* The row is already in manual mode — the fields are only reachable from
     * it — so there is no mode to write here.  Applying the width key is what
     * reinstalls the shim on every open tab. */
    rb_prefs_apply_key(sr->app, RB_PREF_SCREEN_WIDTH);
}

static GtkWidget *rb_pref_screen_row(GtkWidget *grid, int row, App *app)
{
    static const char *const ids[3] = { "real", "manual", NULL };
    static const char *const labels[3] = { "This display", "Custom", NULL };
    GtkWidget *combo;
    GtkWidget *box;
    GtkWidget *sep;
    GtkWidget *use_real;
    GtkWidget *apply;
    rb_screen_row *sr = g_new0(rb_screen_row, 1);

    sr->app = app;
    combo = rb_pref_combo_row(grid, row, app, RB_PREF_SCREEN_SIZE, ids, labels,
                              rb_pref(app, RB_PREF_SCREEN_SIZE, "real"),
                              "Reported screen size", 0);

    box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    sr->width = gtk_entry_new();
    sr->height = gtk_entry_new();
    sep = gtk_label_new("x");
    use_real = gtk_button_new_with_label("This display");
    apply = gtk_button_new_with_label("Apply");

    gtk_entry_set_width_chars(GTK_ENTRY(sr->width), 6);
    gtk_entry_set_width_chars(GTK_ENTRY(sr->height), 6);
    gtk_widget_set_tooltip_text(sr->width, "Width in CSS pixels");
    gtk_widget_set_tooltip_text(sr->height, "Height in CSS pixels");
    gtk_widget_set_tooltip_text(use_real,
                                "Fill the two numbers in from this display");
    gtk_box_pack_start(GTK_BOX(box), sr->width, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), sep, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), sr->height, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), use_real, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), apply, FALSE, FALSE, 0);
    gtk_widget_set_halign(box, GTK_ALIGN_START);
    gtk_grid_attach(GTK_GRID(grid), box, 0, row + 1, 2, 1);
    sr->numbers = box;

    g_signal_connect(use_real, "clicked",
                     G_CALLBACK(on_screen_use_real_clicked), sr);
    g_signal_connect(apply, "clicked",
                     G_CALLBACK(on_screen_apply_clicked), sr);
    /* Connected after the row's own handler, which is what writes the mode:
     * GLib runs them in connection order, so the setting already holds the new
     * value by the time this one reads it back to seed the fields. */
    g_signal_connect(combo, "changed",
                     G_CALLBACK(on_screen_mode_changed), sr);
    /* The state dies with the combo it belongs to, and so with the dialog. */
    g_object_set_data_full(G_OBJECT(combo), "rb-screen-row", sr, g_free);

    rb_screen_numbers_sync(sr);
    rb_screen_manual_sync(sr);

    rb_pref_note_row(grid, row + 2,
        "What a page is told the display is. A claimed size replaces the "
        "screen's own width, height and available area; the page is still "
        "laid out in this window, so a claim that differs from this display "
        "will disagree with it. \"This display\" reports the truth and claims "
        "nothing.");
    return combo;
}

/* The rows that only mean something when NO device is chosen.  A control that
 * silently does nothing is worse than one that is visibly off: with a machine
 * selected, these grey out and the note below says why, so the settings screen
 * never offers an answer the browser is going to ignore. */
typedef struct {
    GtkWidget *mode;
    GtkWidget *preset;
    GtkWidget *custom;
    GtkWidget *current;   /* the "Current: ..." note, refreshed with them */
} rb_ua_followers;

/* What the profile would actually send, and why.  Re-read rather than cached,
 * because both halves of it change when the device changes. */
static void ua_current_note_set(GtkWidget *note, App *app)
{
    char *ua = rb_ua_current(app);
    const char *device_id = rb_pref(app, RB_PREF_DEVICE_ID, "");
    gboolean has_device = (device_id != NULL && device_id[0] != '\0');
    char *markup = g_markup_printf_escaped(
        "<small>Current: %s\n%s</small>",
        (ua != NULL) ? ua : "the engine default",
        has_device
            ? "Set by the device above, along with the platform "
              "characteristics that go with it. Choose \"No device\" to "
              "set the string on its own."
            : "On its own, a User-Agent string changes no other platform "
              "or device characteristic.");
    gtk_label_set_markup(GTK_LABEL(note), markup);
    g_free(markup);
    free(ua);
}

static void rb_ua_followers_update(GtkWidget *device_widget, gpointer user_data)
{
    rb_ua_followers *f = (rb_ua_followers *)g_object_get_data(
        G_OBJECT(device_widget), "rb-ua-followers");
    App *app = (App *)user_data;
    const char *id;
    gboolean has_device;

    if (f == NULL) return;

    /* Read the chosen id from the setting rather than from the control, so
     * the greying stays right whatever the control on the row happens to be
     * — it is a button now, and a button has no row to read an index off. */
    id = (app != NULL) ? rb_pref(app, RB_PREF_DEVICE_ID, "") : "";
    has_device = (id != NULL && id[0] != '\0');

    if (f->mode != NULL) gtk_widget_set_sensitive(f->mode, !has_device);
    if (f->preset != NULL) gtk_widget_set_sensitive(f->preset, !has_device);
    if (f->custom != NULL) gtk_widget_set_sensitive(f->custom, !has_device);
    if (f->current != NULL && app != NULL) ua_current_note_set(f->current, app);
}

/* The User-Agent rows themselves.  They come after the device row because the
 * device decides them, and they hand their widgets back through `f` so that
 * row can grey them out. */
static void ua_rows(GtkWidget *grid, int *row, App *app, rb_ua_followers *f)
{
    int r = *row;

    {
        static const char *ids[4] = { "default", "preset", "custom", NULL };
        static const char *labels[4] = { "Default (WebKit)",
                                         "Preset", "Custom", NULL };
        f->mode = rb_pref_combo_row(grid, r++, app, RB_PREF_UA_MODE, ids, labels,
                                    rb_pref(app, RB_PREF_UA_MODE, "default"),
                                    "User-Agent mode", 0);
    }
    {
        static const char *ids[64];
        static const char *labels[64];
        static char buf[64][128];
        int n = rb_ua_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_ua_preset *p = rb_ua_at(i);
            ids[i] = (p != NULL) ? p->id : "";
            snprintf(buf[i], sizeof buf[i], "%s%s",
                     (p != NULL && p->label != NULL) ? p->label : "",
                     (p != NULL && p->is_desktop) ? "  (desktop)" : "");
            labels[i] = buf[i];
        }
        ids[n] = NULL;
        labels[n] = NULL;
        f->preset = rb_pref_combo_row(grid, r++, app, RB_PREF_UA_PRESET_ID,
                                      ids, labels,
                                      rb_pref(app, RB_PREF_UA_PRESET_ID, ""),
                                      "Preset", 0);
    }
    f->custom = rb_pref_entry_row(grid, r, app, RB_PREF_CUSTOM_USER_AGENT,
                                  rb_pref(app, RB_PREF_CUSTOM_USER_AGENT, ""),
                                  "Custom User-Agent");
    r += 2;

    f->current = gtk_label_new(NULL);
    gtk_label_set_xalign(GTK_LABEL(f->current), 0.0f);
    gtk_label_set_line_wrap(GTK_LABEL(f->current), TRUE);
    ua_current_note_set(f->current, app);
    gtk_grid_attach(GTK_GRID(grid), f->current, 0, r++, 2, 1);

    *row = r;
}

/* An entry row with an Apply button: the value is written when Apply is
 * pressed, not on every keystroke, so a half-typed URL is never saved. */
static void on_pref_entry_apply(GtkButton *btn, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *key = (const char *)g_object_get_data(G_OBJECT(btn), "rb-pref-key");
    GtkWidget *entry = (GtkWidget *)g_object_get_data(G_OBJECT(btn), "rb-pref-entry");
    const char *text;
    if (key == NULL || entry == NULL) return;
    text = gtk_entry_get_text(GTK_ENTRY(entry));
    if (text == NULL) text = "";

    /* The two DNS fields are checked before they are stored, with the same
     * validator the Android edition uses.  A value that could never be used
     * is refused here rather than saved, so the settings screen never shows a
     * "protected" DNS mode that silently is not one.  Empty is allowed: it
     * means "not configured". */
    if (strcmp(key, RB_PREF_DOH_URL) == 0 && text[0] != '\0' &&
        !rb_dns_valid_doh_url(text)) {
        rb_warn(app, "Not a valid DNS-over-HTTPS URL",
                "It has to be an https:// URL with a host, for example "
                "https://dns.example/dns-query.  Left unchanged.");
        return;
    }
    if (strcmp(key, RB_PREF_DOT_HOSTNAME) == 0 && text[0] != '\0' &&
        !rb_dns_valid_dot_hostname(text)) {
        rb_warn(app, "Not a valid DNS-over-TLS hostname",
                "Expected a hostname, optionally with a port, for example "
                "dns.example or dns.example:853.  Left unchanged.");
        return;
    }

    rb_pref_set(app, key, text);
    rb_prefs_apply_key(app, key);
}

static GtkWidget *rb_pref_entry_row(GtkWidget *grid, int row, App *app,
                                    const char *key, const char *current,
                                    const char *title)
{
    GtkWidget *label = gtk_label_new(title);
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    GtkWidget *entry = gtk_entry_new();
    GtkWidget *apply = gtk_button_new_with_label("Apply");

    gtk_label_set_xalign(GTK_LABEL(label), 0.0f);
    gtk_grid_attach(GTK_GRID(grid), label, 0, row, 2, 1);

    gtk_entry_set_text(GTK_ENTRY(entry), (current != NULL) ? current : "");
    gtk_widget_set_hexpand(entry, TRUE);
    gtk_box_pack_start(GTK_BOX(box), entry, TRUE, TRUE, 0);
    gtk_box_pack_start(GTK_BOX(box), apply, FALSE, FALSE, 0);
    g_object_set_data_full(G_OBJECT(apply), "rb-pref-key", rb_strdup(key), free);
    g_object_set_data(G_OBJECT(apply), "rb-pref-entry", entry);
    g_signal_connect(apply, "clicked", G_CALLBACK(on_pref_entry_apply), app);
    gtk_grid_attach(GTK_GRID(grid), box, 0, row + 1, 2, 1);
    return box;
}

static GtkWidget *rb_pref_page(void)
{
    GtkWidget *grid = gtk_grid_new();
    gtk_grid_set_row_spacing(GTK_GRID(grid), 8);
    gtk_grid_set_column_spacing(GTK_GRID(grid), 12);
    gtk_container_set_border_width(GTK_CONTAINER(grid), 12);
    gtk_widget_set_margin_end(grid, 12);
    return grid;
}

static GtkWidget *rb_pref_scrolled(GtkWidget *child)
{
    GtkWidget *sw = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(sw),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    gtk_container_add(GTK_CONTAINER(sw), child);
    return sw;
}

/* --- Danger zone: clear browsing data ---
 *
 * Android's ClearDataSection.  The WebKit half is real per-profile work
 * (cookies, cache and site storage live in the profile's data directories),
 * and the store half is the core's own files. */

typedef struct {
    App *app;
    GtkWidget *history, *cookies, *cache, *site_data, *downloads;
} rb_clear_widgets;

static void on_clear_data(GtkButton *btn, gpointer user_data)
{
    rb_clear_widgets *w = (rb_clear_widgets *)user_data;
    App *app = w->app;
    const rb_profile *p = rb_active_profile(app);
    GString *done = g_string_new("");
    WebKitWebsiteDataTypes types = 0;

    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->history)) &&
        app->history != NULL) {
        int n = rb_history_clear(app->history);
        if (app->path_history) rb_history_save(app->history, app->path_history);
        g_string_append_printf(done, "history (%d)\n", n);
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->cookies))) {
        types |= WEBKIT_WEBSITE_DATA_COOKIES;
        /* The core keeps no cookie jar — WebKit owns it — so there is nothing
         * to clear here beyond the data manager below. */
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->cache))) {
        types |= WEBKIT_WEBSITE_DATA_MEMORY_CACHE | WEBKIT_WEBSITE_DATA_DISK_CACHE;
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->site_data))) {
        types |= WEBKIT_WEBSITE_DATA_LOCAL_STORAGE |
                 WEBKIT_WEBSITE_DATA_INDEXEDDB_DATABASES |
                 WEBKIT_WEBSITE_DATA_WEBSQL_DATABASES |
                 WEBKIT_WEBSITE_DATA_SESSION_STORAGE |
                 WEBKIT_WEBSITE_DATA_SERVICE_WORKER_REGISTRATIONS |
                 WEBKIT_WEBSITE_DATA_DOM_CACHE;
    }
    if (gtk_toggle_button_get_active(GTK_TOGGLE_BUTTON(w->downloads)) &&
        app->downloads != NULL && p != NULL) {
        int n = rb_downloads_clear_profile(app->downloads, p->id);
        if (app->path_downloads) {
            rb_downloads_save(app->downloads, app->path_downloads);
        }
        /* Records only: the FILES the user downloaded are never deleted by a
         * "clear browsing data" — that is the user's data, not the browser's. */
        g_string_append_printf(done, "download records (%d)\n", n);
    }

    if (types != 0 && app->ctx != NULL) {
        WebKitWebsiteDataManager *dm =
            webkit_web_context_get_website_data_manager(app->ctx);
        if (dm != NULL) {
            /* Every profile's data lives under its own directories, so this
             * reaches this profile's cookies and cache and no other's. */
            webkit_website_data_manager_clear(dm, types, 0, NULL, NULL, NULL);
            g_string_append(done, "cookies / cache / site data\n");
        }
    }

    if (done->len > 0) {
        g_string_prepend(done, "Cleared:\n");
        rb_warn(app, "Browsing data", done->str);
    } else {
        rb_warn(app, "Browsing data", "Nothing was selected.");
    }
    g_string_free(done, TRUE);
    (void)btn;
}

/* --- the dialog --- */

static void rb_show_prefs_dialog_impl(App *app)
{
    GtkWidget *dlg;
    GtkWidget *notebook;
    GtkWidget *grid;
    GtkWidget *area;
    int r;

    const rb_theme *theme;

    if (app == NULL || app->win == NULL) return;

    dlg = gtk_dialog_new_with_buttons("Profile Settings", GTK_WINDOW(app->win),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT | GTK_DIALOG_USE_HEADER_BAR,
        "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 720, 560);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    notebook = gtk_notebook_new();
    area = gtk_dialog_get_content_area(GTK_DIALOG(dlg));
    gtk_box_pack_start(GTK_BOX(area), notebook, TRUE, TRUE, 0);

    /* ---- Appearance ---- */
    grid = rb_pref_page();
    r = 0;
    {
        /* The theme list is the core's registry, so the desktop offers the
         * same presets the Android theme studio is built from.  The ID goes
         * into the profile's theme_json snapshot (is_theme = 1); the MODE is
         * the separate row below, and the two used to share one control —
         * which is why choosing a theme also silently reset the mode, and why
         * no theme was ever applied at all. */
        static const char *ids[64];
        static const char *labels[64];
        int n = rb_theme_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_theme *t = rb_theme_at(i);
            ids[i] = (t != NULL) ? t->id : "";
            labels[i] = (t != NULL && t->name != NULL) ? t->name : "";
        }
        ids[n] = NULL;
        labels[n] = NULL;
        theme = rb_theme_current(app);
        rb_pref_combo_row(grid, r++, app, NULL, ids, labels,
                          (theme != NULL) ? theme->id : NULL, "Theme", 1);
    }
    {
        /* rb_prefs.h's spellings for the mode, with "system" standing for
         * rb_theme.h's AUTO. */
        static const char *const mode_ids[] = {
            "system", "light", "dark", "amoled", NULL
        };
        static const char *const mode_labels[] = {
            "Match system", "Light", "Dark", "AMOLED", NULL
        };
        rb_pref_combo_row(grid, r++, app, RB_PREF_THEME, mode_ids, mode_labels,
                          rb_pref(app, RB_PREF_THEME, "system"),
                          "Appearance", 0);
    }
    {
        /* The scale every UI font is multiplied by.  Stored as a percentage so
         * the same value means the same thing here, on Windows, and to the
         * Android app's text-size setting. */
        static const char *const scale_ids[] = {
            "80", "90", "100", "110", "125", "150", NULL
        };
        static const char *const scale_labels[] = {
            "80%", "90%", "100% (default)", "110%", "125%", "150%", NULL
        };
        rb_pref_combo_row(grid, r++, app, RB_PREF_FONT_SCALE, scale_ids,
                          scale_labels,
                          rb_pref(app, RB_PREF_FONT_SCALE, "100"),
                          "Text size", 0);
    }
    rb_pref_row(grid, r++, app, RB_PREF_REDUCED_MOTION, 0, "Reduce motion",
                "Stops the page-load strip sweeping and the toolkit's own "
                "transitions; a load is still shown");
    rb_pref_row(grid, r++, app, RB_PREF_HIGH_CONTRAST, 0, "High contrast",
                "Draws the chrome in true black or true white with text at "
                "the opposite end, keeping the theme's accent colours");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Appearance"));

    /* ---- Search ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *ids[64];
        static const char *labels[64];
        int n = rb_search_count();
        int i;
        if (n > 63) n = 63;
        for (i = 0; i < n; i++) {
            const rb_search_engine *e = rb_search_at(i);
            ids[i] = (e != NULL) ? e->id : "";
            labels[i] = (e != NULL && e->label != NULL) ? e->label : "";
        }
        ids[n] = NULL;
        labels[n] = NULL;
        rb_pref_combo_row(grid, r++, app, RB_PREF_SEARCH_ENGINE, ids, labels,
                          rb_pref(app, RB_PREF_SEARCH_ENGINE, "duckduckgo"),
                          "Search engine", 0);
    }
    rb_pref_row(grid, r++, app, RB_PREF_SEARCH_SUGGESTIONS, 0,
                "Search suggestions",
                "Sends what you type to the search engine as you type it");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Search"));

    /* ---- Privacy & Blocking ---- */
    grid = rb_pref_page();
    r = 0;
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_ADS, 0, "Block ads",
                "Blocks known ad hosts from the bundled offline list");
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_TRACKERS, 0, "Block trackers",
                NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_CROSS_SITE, 0,
                "Block cross-site trackers", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_POPUPS, 0, "Block popups", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_MALICIOUS, 1,
                "Block malicious websites",
                "A top-level navigation into a listed malicious host is refused");
    rb_pref_row(grid, r++, app, RB_PREF_HTTPS_UPGRADE, 1, "HTTPS upgrades",
                "Upgrades http to https and falls back to http when the secure "
                "version is unreachable");
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, 0,
                "Block third-party cookies",
                "Off by default for compatibility: many logins and embeds need them");
    /* Shown even though it does nothing here: hiding it would let a user
     * believe their profile has no such setting, and the note says plainly
     * that this edition cannot honour it. */
    rb_pref_row(grid, r++, app, RB_PREF_BLOCK_MIXED_CONTENT, 0,
                "Block mixed content",
                "Stored and honoured on Android; WebKitGTK exposes no switch for "
                "it, so it has no effect on this edition");
    rb_pref_row(grid, r++, app, RB_PREF_JAVASCRIPT, 1, "JavaScript enabled",
                "Never disabled by default");
    {
        /* The same three options the Android settings screen offers, with its
         * own wording.  This edition can only express two of them — see the
         * note under the row, and rb_gw_web_settings_to(). */
        static const char *const ids[] = {
            "default", "restrict_local_ip", "disabled", NULL
        };
        static const char *const labels[] = {
            "WebRTC: Default",
            "WebRTC: Restrict local IP exposure",
            "WebRTC: Disabled (may break calls)",
            NULL
        };
        const rb_profile *p = rb_active_profile(app);
        rb_pref_combo_row(grid, r++, app, RB_PREF_WEBRTC_POLICY, ids, labels,
                          rb_webrtc_policy_name(
                              rb_webrtc_policy_of(p ? p->settings : NULL)),
                          "WebRTC", 0);
        rb_pref_note_row(grid, r++,
            "WebKitGTK has one WebRTC switch and no IP-handling policy, so only "
            "\"Disabled\" changes anything here: it turns WebRTC off, while "
            "\"Default\" and \"Restrict local IP exposure\" both leave it on.");
    }
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Privacy"));

    /* ---- User-Agent ---- */
    grid = rb_pref_page();
    r = 0;
    {
        /* The device comes first because it decides the User-Agent: with a
         * machine chosen, the three rows below are not consulted at all. */
        GtkWidget *device = rb_pref_device_row(grid, r++, app);
        rb_ua_followers *f = g_new0(rb_ua_followers, 1);
        rb_pref_note_row(grid, r++,
                         "A device sets the User-Agent and everything a page can "
                         "ask about the machine — platform, client hints, memory, "
                         "cores, WebGL. It says nothing about the screen, which "
                         "is the row below.");
        ua_rows(grid, &r, app, f);
        /* Attached before the first update, and refreshed by the picker
         * through the same call, so the rows grey out when the machine
         * changes and are already right when the page is shown. */
        g_object_set_data_full(G_OBJECT(device), "rb-ua-followers", f, g_free);
        rb_ua_followers_update(device, app);

        /* Screen size is independent of the device — a profile on a UA preset
         * can claim one, and a profile presenting a machine need not — so it
         * is its own row rather than a follower of the picker above. */
        rb_pref_screen_row(grid, r, app);
        r += 3;
    }
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("User-Agent"));

    /* ---- DNS & Network ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *ids[5] = { "system", "auto", "doh", "dot", NULL };
        static const char *labels[5] = { "System",
                                         "Use the global browser setting",
                                         "DNS-over-HTTPS",
                                         "DNS-over-TLS", NULL };
        rb_pref_combo_row(grid, r++, app, RB_PREF_DNS_MODE, ids, labels,
                          rb_pref(app, RB_PREF_DNS_MODE, "system"), "DNS mode", 0);
    }
    rb_pref_entry_row(grid, r, app, RB_PREF_DOH_URL,
                      rb_pref(app, RB_PREF_DOH_URL, ""), "DNS-over-HTTPS URL");
    r += 2;
    rb_pref_entry_row(grid, r, app, RB_PREF_DOT_HOSTNAME,
                      rb_pref(app, RB_PREF_DOT_HOSTNAME, ""),
                      "DNS-over-TLS hostname");
    r += 2;
    {
        /* The effective mode, computed exactly as the Android settings screen
         * computes it (profile mode, then the global one, then validation).
         * Shown so the DNS fields above are not dead text: the user can see
         * what the profile would actually resolve with, including the
         * MISCONFIGURED case a half-typed URL produces. */
        const char *gm = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DNS_MODE, "system") : "system";
        const char *gd = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOH_URL, "") : "";
        const char *gh = (app->settings != NULL)
            ? rb_settings_get(app->settings, RB_GPREF_DOT_HOSTNAME, "") : "";
        rb_dns_effective eff = rb_dns_resolve(
            rb_pref(app, RB_PREF_DNS_MODE, "system"),
            rb_pref(app, RB_PREF_DOH_URL, ""),
            rb_pref(app, RB_PREF_DOT_HOSTNAME, ""),
            gm, gd, gh);
        GtkWidget *note = gtk_label_new(NULL);
        char *markup = g_markup_printf_escaped(
            "<small>Effective mode: %s\n%s</small>",
            rb_dns_status_name(eff.status),
            (eff.status == RB_DNS_STATUS_MISCONFIGURED)
                ? "The configured value is not usable, so name resolution falls "
                  "back to the system resolver."
                : "This build does not resolve names itself — page loads use the "
                  "system resolver either way. See README.md.");
        gtk_label_set_markup(GTK_LABEL(note), markup);
        gtk_label_set_xalign(GTK_LABEL(note), 0.0f);
        gtk_label_set_line_wrap(GTK_LABEL(note), TRUE);
        g_free(markup);
        rb_dns_effective_free(&eff);
        gtk_grid_attach(GTK_GRID(grid), note, 0, r++, 2, 1);
    }
    rb_pref_row(grid, r++, app, RB_PREF_NET_PROTECT_GLOBAL, 1,
                "Network protection: use the global setting", NULL);
    rb_pref_row(grid, r++, app, RB_PREF_NET_PROTECT_ENABLED, 1,
                "IP conflict warning enabled",
                "Warns when the address this profile resolved to is also in use "
                "on the local network");
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Network"));

    /* ---- Homepage, tabs & language ---- */
    grid = rb_pref_page();
    r = 0;
    rb_pref_entry_row(grid, r, app, RB_PREF_HOME_LOCAL,
                      rb_pref(app, RB_PREF_HOME_LOCAL, "https://duckduckgo.com"),
                      "Homepage");
    r += 2;
    rb_pref_row(grid, r++, app, RB_PREF_HOMEPAGE_ENABLED, 1, "Show homepage",
                "Off opens an empty tab; on opens the homepage above");
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_PRIVACY_STATS, 1,
                "Show privacy statistics",
                "Android's new-tab card; this edition has no new-tab page, so "
                "the switch is stored for parity and changes nothing here");
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_RECENT_SITES, 1,
                "Show recent sites",
                "Android's new-tab card; this edition has no new-tab page, so "
                "the switch is stored for parity and changes nothing here");
    rb_pref_row(grid, r++, app, RB_PREF_SHOW_CLOCK, 1, "Show clock",
                "Android's new-tab clock; this edition has no new-tab page, so "
                "the switch is stored for parity and changes nothing here");
    rb_pref_row(grid, r++, app, RB_PREF_DESKTOP_MODE_DEFAULT, 0,
                "Request desktop sites by default",
                "Already true here: this is a desktop browser, so there is no "
                "mobile mode for it to override");
    rb_pref_row(grid, r++, app, RB_PREF_AUTOFILL_ENABLED, 0,
                "Autofill integration",
                "Android delegates this to the system autofill framework, which "
                "a desktop browser has no equivalent of — this edition stores no "
                "credentials and does not fill forms");
    rb_pref_entry_row(grid, r, app, RB_PREF_DOWNLOAD_SUBFOLDER,
                      rb_pref(app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser"),
                      "Downloads subfolder");
    r += 2;
    rb_pref_entry_row(grid, r, app, RB_PREF_TRANSLATE_TARGET,
                      rb_pref(app, RB_PREF_TRANSLATE_TARGET, "id"),
                      "Translate target language (e.g. id)");
    r += 2;
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Homepage & Languages"));

    /* ---- Danger zone ---- */
    grid = rb_pref_page();
    r = 0;
    {
        static const char *names[] = { "History", "Cookies", "Cache",
                                       "Site data (local storage, IndexedDB)",
                                       "Download records" };
        GtkWidget *boxes[5];
        rb_clear_widgets *w = g_new0(rb_clear_widgets, 1);
        GtkWidget *btn;
        GtkWidget *note;
        int i;
        static const int defaults[5] = { 1, 1, 1, 0, 0 };

        w->app = app;
        for (i = 0; i < 5; i++) {
            boxes[i] = gtk_check_button_new_with_label(names[i]);
            gtk_toggle_button_set_active(GTK_TOGGLE_BUTTON(boxes[i]),
                                         defaults[i] ? TRUE : FALSE);
            gtk_grid_attach(GTK_GRID(grid), boxes[i], 0, r++, 2, 1);
        }
        w->history = boxes[0];
        w->cookies = boxes[1];
        w->cache = boxes[2];
        w->site_data = boxes[3];
        w->downloads = boxes[4];

        note = gtk_label_new(NULL);
        gtk_label_set_markup(GTK_LABEL(note),
            "<small>This clears the data of the ACTIVE PROFILE only; every other "
            "profile keeps its own cookies and cache. Downloaded files are never "
            "deleted — only the records of them.</small>");
        gtk_label_set_xalign(GTK_LABEL(note), 0.0f);
        gtk_label_set_line_wrap(GTK_LABEL(note), TRUE);
        gtk_grid_attach(GTK_GRID(grid), note, 0, r++, 2, 1);

        btn = gtk_button_new_with_label("Clear selected data");
        g_signal_connect(btn, "clicked", G_CALLBACK(on_clear_data), w);
        g_signal_connect_swapped(dlg, "destroy", G_CALLBACK(g_free), w);
        gtk_grid_attach(GTK_GRID(grid), btn, 0, r++, 2, 1);
    }
    gtk_notebook_append_page(GTK_NOTEBOOK(notebook), rb_pref_scrolled(grid),
                             gtk_label_new("Clear data"));

    gtk_widget_show_all(dlg);
}

void rb_show_prefs_dialog(App *app)
{
    rb_show_prefs_dialog_impl(app);
}

static void rb_show_about(App *app)
{
    GtkWidget *dlg = gtk_about_dialog_new();
    const gchar *authors[2];
    authors[0] = "Maragung";
    authors[1] = NULL;
    gtk_window_set_transient_for(GTK_WINDOW(dlg), GTK_WINDOW(app->win));
    gtk_window_set_modal(GTK_WINDOW(dlg), TRUE);
    gtk_about_dialog_set_program_name(GTK_ABOUT_DIALOG(dlg), "Room Browser");
    gtk_about_dialog_set_version(GTK_ABOUT_DIALOG(dlg), RB_VERSION);
    gtk_about_dialog_set_authors(GTK_ABOUT_DIALOG(dlg), authors);
    gtk_about_dialog_set_comments(GTK_ABOUT_DIALOG(dlg),
        "Privacy desktop browser in pure C (Brave-inspired dark chrome).");
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);
    gtk_widget_show_all(dlg);
}

static void on_hist_row_activated(GtkListBox *box, GtkListBoxRow *row,
                                  gpointer user_data)
{
    App *app = (App *)user_data;
    const char *url = (const char *)g_object_get_data(G_OBJECT(row), "url");
    (void)box;
    if (url && url[0]) {
        rb_do_navigate(app, url);
    }
    gtk_widget_destroy(gtk_widget_get_toplevel(GTK_WIDGET(row)));
}

static void rb_show_history_dialog(App *app)
{
    GtkWidget *dlg, *scroll, *list;
    const rb_hist_entry *rec;
    int n = 0, i;

    dlg = gtk_dialog_new_with_buttons("Recent history", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 560, 400);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    list = gtk_list_box_new();
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_NONE);

    rec = rb_history_recent(app->history, 10, &n);
    for (i = 0; i < n; i++) {
        const char *title = (rec[i].title && rec[i].title[0]) ? rec[i].title
                                                              : rec[i].url;
        GtkWidget *row = gtk_list_box_row_new();
        GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
        GtkWidget *l1 = gtk_label_new(title ? title : "");
        GtkWidget *l2 = gtk_label_new(rec[i].url ? rec[i].url : "");
        gtk_label_set_ellipsize(GTK_LABEL(l1), PANGO_ELLIPSIZE_END);
        gtk_label_set_xalign(GTK_LABEL(l1), 0.0f);
        gtk_label_set_ellipsize(GTK_LABEL(l2), PANGO_ELLIPSIZE_MIDDLE);
        gtk_label_set_xalign(GTK_LABEL(l2), 0.0f);
        rb_add_class(l2, "rb-dim");
        g_object_set_data_full(G_OBJECT(row), "url",
                               rb_strdup(rec[i].url ? rec[i].url : ""), g_free);
        gtk_box_pack_start(GTK_BOX(vbox), l1, TRUE, TRUE, 0);
        gtk_box_pack_start(GTK_BOX(vbox), l2, TRUE, TRUE, 0);
        gtk_container_add(GTK_CONTAINER(row), vbox);
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }
    if (n == 0) {
        GtkWidget *row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row),
                          gtk_label_new("(no history yet)"));
        gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
    }

    g_signal_connect(list, "row-activated",
                     G_CALLBACK(on_hist_row_activated), app);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);

    gtk_container_add(GTK_CONTAINER(scroll), list);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(dlg))),
                      scroll);
    gtk_widget_show_all(dlg);
}

/* The status line under a row's name: what state the download is in, and the
 * figures that go with it.
 *
 * Sizes go through the core formatter so a download reads the same here as it
 * does on Android ("1.4 MB", not "1468006 bytes"), and an unknown length is
 * reported as what has arrived so far rather than as a percentage invented from
 * a total the server never sent. */
static void rb_dl_set_meta(GtkWidget *label, const rb_download *dl)
{
    char meta[320];
    char have[32];
    char total[32];
    const char *status = rb_download_status_name(dl->status);

    rb_download_format_bytes(dl->downloaded_bytes, have, sizeof have);
    rb_download_format_bytes(dl->total_bytes, total, sizeof total);

    if (rb_download_is_active(dl)) {
        if (dl->total_bytes > 0) {
            snprintf(meta, sizeof meta, "%s — %d%%  (%s / %s)", status,
                     rb_download_progress_percent(dl->downloaded_bytes,
                                                  dl->total_bytes),
                     have, total);
        } else {
            snprintf(meta, sizeof meta, "%s — %s so far", status, have);
        }
    } else if (dl->status == RB_DL_COMPLETED) {
        snprintf(meta, sizeof meta, "%s — %s", status, have);
    } else if (dl->error != NULL && dl->error[0] != '\0') {
        snprintf(meta, sizeof meta, "%s — %s", status, dl->error);
    } else {
        snprintf(meta, sizeof meta, "%s", status);
    }
    gtk_label_set_text(GTK_LABEL(label), meta);
}

/* One row: name, then the status line above.
 *
 * The meta label is stashed on the row so the dialog's timer can rewrite it in
 * place — that is what keeps the window live without the list being torn down
 * and rebuilt under the user's scroll position on every tick.  The download id
 * rides along the same way: a row index does not survive a rebuild, and a
 * pointer into the store does not survive an enqueue. */
static void rb_downloads_add_row(GtkWidget *list, const rb_download *dl)
{
    GtkWidget *row = gtk_list_box_row_new();
    GtkWidget *vbox = gtk_box_new(GTK_ORIENTATION_VERTICAL, 2);
    GtkWidget *l1 = gtk_label_new(dl->file_name ? dl->file_name : "");
    GtkWidget *l2 = gtk_label_new("");
    long long *id = g_new(long long, 1);

    *id = dl->id;
    g_object_set_data_full(G_OBJECT(row), "rb-id", id, g_free);

    gtk_label_set_ellipsize(GTK_LABEL(l1), PANGO_ELLIPSIZE_END);
    gtk_label_set_xalign(GTK_LABEL(l1), 0.0f);
    g_object_set_data_full(G_OBJECT(row), "url",
                           rb_strdup(dl->url ? dl->url : ""), g_free);
    gtk_box_pack_start(GTK_BOX(vbox), l1, TRUE, TRUE, 0);

    gtk_label_set_ellipsize(GTK_LABEL(l2), PANGO_ELLIPSIZE_MIDDLE);
    gtk_label_set_xalign(GTK_LABEL(l2), 0.0f);
    rb_add_class(l2, "rb-dim");
    gtk_box_pack_start(GTK_BOX(vbox), l2, TRUE, TRUE, 0);

    rb_dl_set_meta(l2, dl);
    g_object_set_data(G_OBJECT(row), "rb-meta", l2);

    gtk_container_add(GTK_CONTAINER(row), vbox);
    gtk_list_box_insert(GTK_LIST_BOX(list), row, -1);
}

static void on_dl_clear_clicked(GtkButton *button, gpointer user_data)
{
    App *app = (App *)user_data;
    const rb_profile *p = rb_active_profile(app);
    (void)button;
    /* Clears the RECORDS of this profile only; the files on disk belong to
     * the user and are never touched (DownloadDao.deleteAllFor). */
    rb_downloads_clear_profile(app->downloads, (p != NULL) ? p->id : "");
    if (app->path_downloads) {
        rb_downloads_save(app->downloads, app->path_downloads);
    }
    gtk_widget_destroy(gtk_widget_get_toplevel(GTK_WIDGET(button)));
}

/* The open downloads window, and the timer that keeps it moving.
 *
 * A download manager whose window freezes at the figures it was opened with is
 * not a download manager: the transfer it is describing keeps arriving while
 * the user watches it.  The timer lives exactly as long as the dialog does. */
typedef struct {
    App *app;
    GtkWidget *dlg;
    GtkWidget *list;
    guint timer;
    int rows;   /* rows currently in the list, 0 when the placeholder is shown */

    /* The per-row actions, kept so their sensitivity can follow the selection:
     * "Open" on a download that has not finished has nothing to open. */
    GtkWidget *btn_open;
    GtkWidget *btn_folder;
    GtkWidget *btn_copy;
    GtkWidget *btn_details;
    GtkWidget *btn_cancel;
    GtkWidget *btn_remove;
} RbDlDialog;

/* The selected download, or NULL.  Read through the id on the row rather than
 * a pointer into the store, which an enqueue could have moved. */
static const rb_download *rb_dl_selected(RbDlDialog *st)
{
    GtkListBoxRow *row = gtk_list_box_get_selected_row(GTK_LIST_BOX(st->list));
    long long *id;

    if (row == NULL) return NULL;
    id = (long long *)g_object_get_data(G_OBJECT(row), "rb-id");
    return (id != NULL) ? rb_downloads_by_id(st->app->downloads, *id) : NULL;
}

/* A created_at / completed_at (ms since the epoch) as a local date and time.
 * Local, not UTC: a download happened when the user's own clock said so. */
static void rb_dl_stamp(long long ms, char *out, size_t cap)
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

/* Parented on the downloads window, not the main one: the downloads window is
 * modal, so a message behind it would be unreachable. */
static void rb_dl_msg(RbDlDialog *st, GtkMessageType type, const char *title,
                      const char *body)
{
    GtkWidget *dlg = gtk_message_dialog_new(GTK_WINDOW(st->dlg),
        GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
        type, GTK_BUTTONS_CLOSE, "%s", title);
    gtk_message_dialog_format_secondary_text(GTK_MESSAGE_DIALOG(dlg), "%s",
                                             body);
    g_signal_connect_swapped(dlg, "response", G_CALLBACK(gtk_widget_destroy),
                             dlg);
    gtk_widget_show(dlg);
}

/* What the selected download can actually be asked to do.  A button that would
 * do nothing is insensitive rather than hidden, so the row of actions stays put
 * and the user can see which ones apply to this row. */
static void rb_dl_sync_actions(RbDlDialog *st)
{
    const rb_download *dl = rb_dl_selected(st);
    int saved = (dl != NULL && dl->destination != NULL &&
                 dl->destination[0] != '\0');

    gtk_widget_set_sensitive(st->btn_open, saved);
    gtk_widget_set_sensitive(st->btn_folder, saved);
    gtk_widget_set_sensitive(st->btn_copy,
                             dl != NULL && dl->url != NULL && dl->url[0] != '\0');
    gtk_widget_set_sensitive(st->btn_details, dl != NULL);
    /* Cancel follows the ENGINE, not the record: a RUNNING row left over from
     * a previous run has nothing behind it to stop (rb_downloads_reconcile
     * demotes those at startup, but a stale row can exist until then). */
    gtk_widget_set_sensitive(st->btn_cancel,
                             dl != NULL && rb_gw_download_active(dl->id));
    gtk_widget_set_sensitive(st->btn_remove, dl != NULL);
}

static void rb_dl_fill_rows(RbDlDialog *st)
{
    const rb_profile *p = rb_active_profile(st->app);
    const char *pid = (p != NULL) ? p->id : "";
    GList *kids;
    GList *it;
    int n;
    int i;

    kids = gtk_container_get_children(GTK_CONTAINER(st->list));
    for (it = kids; it != NULL; it = it->next) {
        gtk_widget_destroy(GTK_WIDGET(it->data));
    }
    g_list_free(kids);

    /* The ACTIVE profile's rows, which is also what "Clear list" clears.  The
     * list used to walk the whole store, so it offered rows the button could
     * not touch and showed another profile's downloads. */
    n = rb_downloads_count_for(st->app->downloads, pid);
    st->rows = n;
    for (i = 0; i < n; i++) {
        const rb_download *dl = rb_downloads_at_for(st->app->downloads, pid, i);
        if (dl != NULL) {
            rb_downloads_add_row(st->list, dl);
        }
    }
    if (n == 0) {
        GtkWidget *row = gtk_list_box_row_new();
        gtk_container_add(GTK_CONTAINER(row),
                          gtk_label_new("(no downloads yet)"));
        gtk_list_box_insert(GTK_LIST_BOX(st->list), row, -1);
    }
    gtk_widget_show_all(st->list);
    rb_dl_sync_actions(st);
}

static void on_dl_selection_changed(GtkListBox *box, gpointer user_data)
{
    (void)box;
    rb_dl_sync_actions((RbDlDialog *)user_data);
}

/* Opens the file the platform engine saved.  Deliberately not offered for a
 * download that has not completed: there is no file yet, and opening the
 * partial one would hand the user a truncated file that looks whole. */
static void on_dl_open(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    GFile *file;
    char *uri;
    GError *err = NULL;
    (void)button;

    if (dl == NULL || dl->destination == NULL || dl->destination[0] == '\0')
        return;
    file = g_file_new_for_path(dl->destination);
    uri = g_file_get_uri(file);
    if (!gtk_show_uri_on_window(GTK_WINDOW(st->dlg), uri, GDK_CURRENT_TIME,
                                &err)) {
        rb_dl_msg(st, GTK_MESSAGE_WARNING, "Cannot open the file",
                  (err != NULL && err->message != NULL) ? err->message
                     : "No application is registered for this file type.");
    }
    if (err != NULL) g_error_free(err);
    g_free(uri);
    g_object_unref(file);
}

/* Reveals the file in the file manager — the folder, since GTK has no portable
 * "select this file" call. */
static void on_dl_show_folder(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    GFile *file;
    char *dir;
    char *uri;
    GError *err = NULL;
    (void)button;

    if (dl == NULL || dl->destination == NULL || dl->destination[0] == '\0')
        return;
    dir = g_path_get_dirname(dl->destination);
    file = g_file_new_for_path(dir);
    uri = g_file_get_uri(file);
    if (!gtk_show_uri_on_window(GTK_WINDOW(st->dlg), uri, GDK_CURRENT_TIME,
                                &err)) {
        rb_dl_msg(st, GTK_MESSAGE_WARNING, "Cannot open the folder",
                  (err != NULL && err->message != NULL) ? err->message
                     : "The file manager could not be started.");
    }
    if (err != NULL) g_error_free(err);
    g_free(uri);
    g_object_unref(file);
    g_free(dir);
}

static void on_dl_copy_link(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    GtkClipboard *cb;
    (void)button;

    if (dl == NULL || dl->url == NULL || dl->url[0] == '\0') return;
    cb = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
    gtk_clipboard_set_text(cb, dl->url, -1);
    /* Asks the clipboard manager to keep it past this process, which is what
     * makes "copy link" still paste after the browser is closed. */
    gtk_clipboard_store(cb);
}

static void on_dl_details(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    char body[1400];
    char size_line[96];
    char have[32];
    char total[32];
    char started[32];
    char ended[32];
    (void)button;

    if (dl == NULL) return;
    rb_download_format_bytes(dl->downloaded_bytes, have, sizeof have);
    rb_download_format_bytes(dl->total_bytes, total, sizeof total);
    rb_dl_stamp(dl->created_at, started, sizeof started);
    rb_dl_stamp(dl->completed_at, ended, sizeof ended);

    /* An unknown length is not 0%: it is a figure nobody has, so the line says
     * so instead of printing a percentage that would be a claim. */
    if (dl->total_bytes > 0) {
        snprintf(size_line, sizeof size_line, "%s of %s (%d%%)", have, total,
                 rb_download_progress_percent(dl->downloaded_bytes,
                                              dl->total_bytes));
    } else {
        snprintf(size_line, sizeof size_line, "%s (total unknown)", have);
    }

    snprintf(body, sizeof body,
             "Status: %s\n"
             "Source: %s\n"
             "Type: %s\n"
             "Saved to: %s\n"
             "Size: %s\n"
             "Started: %s\n"
             "Completed: %s%s%s",
             rb_download_status_name(dl->status),
             (dl->url != NULL && dl->url[0] != '\0') ? dl->url : "(unknown)",
             (dl->mime_type != NULL && dl->mime_type[0] != '\0') ? dl->mime_type
                                                                 : "(unknown)",
             (dl->destination != NULL && dl->destination[0] != '\0')
                 ? dl->destination : "(not saved yet)",
             size_line, started, ended,
             (dl->error != NULL && dl->error[0] != '\0') ? "\nError: " : "",
             (dl->error != NULL && dl->error[0] != '\0') ? dl->error : "");

    rb_dl_msg(st, GTK_MESSAGE_INFO,
              (dl->file_name != NULL && dl->file_name[0] != '\0')
                  ? dl->file_name : "Download",
              body);
}

/* Stops the transfer.  The record is NOT written here: WebKit reports the stop
 * through its own "failed" signal, and that is what moves the row to
 * CANCELLED.  Writing it optimistically would let the window say CANCELLED
 * while WebKit was still writing the file. */
static void on_dl_cancel(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    (void)button;

    if (dl == NULL) return;
    if (!rb_gw_download_cancel(dl->id)) {
        rb_dl_msg(st, GTK_MESSAGE_INFO, "Nothing left to cancel",
                  "This transfer has already finished or stopped.");
    }
    rb_dl_sync_actions(st);
}

/* Forgets the RECORD.  The file on disk belongs to the user and is never
 * touched — the same rule "Clear list" follows, and the reason the button says
 * "from list" rather than "Delete". */
static void on_dl_remove(GtkButton *button, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_download *dl = rb_dl_selected(st);
    long long id;
    (void)button;

    if (dl == NULL) return;
    id = dl->id;
    if (rb_downloads_remove(st->app->downloads, id) != 1) return;
    if (st->app->path_downloads != NULL) {
        rb_downloads_save(st->app->downloads, st->app->path_downloads);
    }
    rb_dl_fill_rows(st);
}

static gboolean on_dl_tick(gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    const rb_profile *p = rb_active_profile(st->app);
    const char *pid = (p != NULL) ? p->id : "";
    int n = rb_downloads_count_for(st->app->downloads, pid);
    int i;

    /* A row appeared or went away — a new download, or one cleared while the
     * window was open — so the structure has to be rebuilt. */
    if (n != st->rows) {
        rb_dl_fill_rows(st);
        return G_SOURCE_CONTINUE;
    }
    /* Otherwise only the figures moved.  The rows are updated in place: a
     * rebuild would reset the scroll position once a second, which on a long
     * list is worse than a stale figure. */
    for (i = 0; i < n; i++) {
        GtkWidget *row =
            gtk_list_box_get_row_at_index(GTK_LIST_BOX(st->list), i);
        const rb_download *dl = rb_downloads_at_for(st->app->downloads, pid, i);
        GtkWidget *meta;

        if (row == NULL || dl == NULL) continue;
        meta = (GtkWidget *)g_object_get_data(G_OBJECT(row), "rb-meta");
        if (meta != NULL) {
            rb_dl_set_meta(meta, dl);
        }
    }
    /* A row can also have changed what it can offer between two ticks — a
     * running download that just finished now has a file to open. */
    rb_dl_sync_actions(st);
    return G_SOURCE_CONTINUE;
}

static void on_dl_dialog_destroy(GtkWidget *widget, gpointer user_data)
{
    RbDlDialog *st = (RbDlDialog *)user_data;
    (void)widget;
    /* The timer holds a pointer to this state, so it has to be gone before the
     * state is: otherwise the next tick rewrites labels belonging to a list
     * that no longer exists. */
    if (st->timer != 0) {
        g_source_remove(st->timer);
        st->timer = 0;
    }
    g_free(st);
}

static void rb_show_downloads_dialog(App *app)
{
    GtkWidget *dlg, *scroll, *list, *clear, *actions, *box;
    GtkWidget *open, *folder, *copy, *details, *cancel, *remove;
    RbDlDialog *st;

    dlg = gtk_dialog_new_with_buttons("Downloads", GTK_WINDOW(app->win),
            GTK_DIALOG_MODAL | GTK_DIALOG_DESTROY_WITH_PARENT,
            "_Close", GTK_RESPONSE_CLOSE, NULL);
    gtk_window_set_default_size(GTK_WINDOW(dlg), 720, 460);

    scroll = gtk_scrolled_window_new(NULL, NULL);
    gtk_scrolled_window_set_policy(GTK_SCROLLED_WINDOW(scroll),
                                   GTK_POLICY_NEVER, GTK_POLICY_AUTOMATIC);
    list = gtk_list_box_new();
    /* SINGLE rather than NONE: the actions below act on the selected row, so
     * the list is no longer purely informational. */
    gtk_list_box_set_selection_mode(GTK_LIST_BOX(list), GTK_SELECTION_SINGLE);

    st = g_new0(RbDlDialog, 1);
    st->app = app;
    st->dlg = dlg;
    st->list = list;

    actions = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    gtk_widget_set_margin_start(actions, 8);
    gtk_widget_set_margin_end(actions, 8);
    gtk_widget_set_margin_top(actions, 8);

    open = gtk_button_new_with_label("Open");
    folder = gtk_button_new_with_label("Show in folder");
    copy = gtk_button_new_with_label("Copy link");
    details = gtk_button_new_with_label("Details");
    cancel = gtk_button_new_with_label("Cancel");
    remove = gtk_button_new_with_label("Remove from list");
    gtk_widget_set_tooltip_text(open, "Open the downloaded file");
    gtk_widget_set_tooltip_text(folder, "Show the file in the file manager");
    gtk_widget_set_tooltip_text(copy, "Copy the download's source link");
    gtk_widget_set_tooltip_text(details, "Show every recorded detail");
    gtk_widget_set_tooltip_text(cancel, "Stop the transfer");
    gtk_widget_set_tooltip_text(remove,
                                "Forget this record. The file on disk stays.");
    st->btn_open = open;
    st->btn_folder = folder;
    st->btn_copy = copy;
    st->btn_details = details;
    st->btn_cancel = cancel;
    st->btn_remove = remove;

    g_signal_connect(open, "clicked", G_CALLBACK(on_dl_open), st);
    g_signal_connect(folder, "clicked", G_CALLBACK(on_dl_show_folder), st);
    g_signal_connect(copy, "clicked", G_CALLBACK(on_dl_copy_link), st);
    g_signal_connect(details, "clicked", G_CALLBACK(on_dl_details), st);
    g_signal_connect(cancel, "clicked", G_CALLBACK(on_dl_cancel), st);
    g_signal_connect(remove, "clicked", G_CALLBACK(on_dl_remove), st);

    gtk_box_pack_start(GTK_BOX(actions), open, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), folder, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), copy, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), details, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), cancel, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(actions), remove, FALSE, FALSE, 0);

    /* The buttons exist before the first fill, because filling sets their
     * sensitivity from whatever ends up selected. */
    rb_dl_fill_rows(st);

    clear = gtk_button_new_with_label("Clear list");
    g_signal_connect(clear, "clicked", G_CALLBACK(on_dl_clear_clicked), app);
    g_signal_connect(list, "selected-rows-changed",
                     G_CALLBACK(on_dl_selection_changed), st);
    g_signal_connect(dlg, "response", G_CALLBACK(on_dialog_response), NULL);
    g_signal_connect(dlg, "destroy", G_CALLBACK(on_dl_dialog_destroy), st);

    box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_box_pack_start(GTK_BOX(box), actions, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(box), scroll, TRUE, TRUE, 0);
    gtk_container_add(GTK_CONTAINER(scroll), list);
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_content_area(GTK_DIALOG(dlg))),
                      box);
    /* get_action_area is deprecated since GTK 3.12 (GtkHeaderBar is the
     * replacement) but the classic action area is still what a GtkDialog
     * builds, and the deprecation is silenced the same way the menu popup
     * one is below. */
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    gtk_container_add(GTK_CONTAINER(gtk_dialog_get_action_area(GTK_DIALOG(dlg))),
                      clear);
    G_GNUC_END_IGNORE_DEPRECATIONS
    gtk_widget_show_all(dlg);
    st->timer = g_timeout_add(1000, on_dl_tick, st);
}

static void on_menu_downloads(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_downloads_dialog(app);
}

static void on_menu_notes(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_open_notes_window(app);
}

static void on_menu_totp(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_open_totp_window(app);
}

static void on_menu_prefs(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_prefs_dialog(app);
}

static void on_menu_translate(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_do_translate(app);
}

static void on_menu_find(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_find_show(app);
}

/* ------------------------------------------------------------------ */
/* Profiles menu

 * Android's profile switcher (a sheet listing every profile, the active one
 * ticked, plus "add profile").  The desktop builds the same list as a
 * submenu; each item carries its profile id as object data, because the
 * registry row a pointer would name can move when the registry grows. */

static void rb_build_menu(App *app);   /* rebuilt when the profile list grows */

static gboolean rb_rebuild_menu_idle(gpointer data)
{
    rb_build_menu((App *)data);
    return G_SOURCE_REMOVE;
}

static void on_menu_profile(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    const char *id = (const char *)g_object_get_data(G_OBJECT(item),
                                                     "rb-profile-id");
    if (id != NULL) rb_do_switch_profile(app, id);
}

static void on_menu_new_profile(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    rb_profile_registry *reg;
    char name[64];
    char id[RB_PROFILE_ID_LEN + 1];
    int n;
    int made;
    (void)item;

    if (app == NULL || app->profiles == NULL) return;
    reg = app->profiles;
    n = rb_profile_count(reg);
    /* The new profile is created but NOT switched to: the row appears in the
     * menu, and activating it runs the full switch protocol.  Switching
     * straight from a menu click would leave the user with no way to see
     * which profile they landed in. */
    snprintf(name, sizeof name, "Profile %d", n + 1);
    id[0] = '\0';
    /* create() refuses a name that is already taken, so a user who renamed a
     * profile to "Profile 2" makes this call fail — bump the number until it
     * does not, which is the same thing the Android sheet does rather than
     * refusing to add a profile at all. */
    for (;;) {
        made = rb_profile_create(reg, name, NULL, 0, 1);
        if (made == 0 || n >= 99) break;
        n++;
        snprintf(name, sizeof name, "Profile %d", n + 1);
    }
    if (made != 0) {
        rb_switch_error_dialog(app, "Cannot add a profile",
            "The browser could not create another profile.");
        return;
    }
    {
        const rb_profile *p = rb_profile_at(reg, rb_profile_count(reg) - 1);
        if (p != NULL) snprintf(id, sizeof id, "%s", p->id);
    }
    if (id[0] == '\0') return;
    rb_profiles_save(app);
    /* The list is built from the registry, so it has to be rebuilt — but
     * this runs inside the activation of an item in the popup being
     * replaced, so the rebuild is deferred to the next main-loop turn
     * rather than destroying the menu under the signal that is emitting. */
    g_idle_add(rb_rebuild_menu_idle, app);
}

static void rb_profile_append_items(GtkWidget *menu, App *app)
{
    int i;
    int n = rb_profile_count(app->profiles);
    GSList *group = NULL;

    for (i = 0; i < n; i++) {
        const rb_profile *p = rb_profile_at(app->profiles, i);
        GtkWidget *item;
        char label[128];
        if (p == NULL) continue;
        snprintf(label, sizeof label, "%s%s",
                 (p->name && p->name[0]) ? p->name : "Profile",
                 (p->is_locked) ? "  (locked)" : "");
        item = gtk_radio_menu_item_new_with_label(group, label);
        /* The radio group is what puts the tick on the active profile, so it
         * has to be threaded through every item by hand — GTK takes the list
         * head, not a widget. */
        group = gtk_radio_menu_item_get_group(GTK_RADIO_MENU_ITEM(item));
        if (app->active_profile_id != NULL &&
            strcmp(app->active_profile_id, p->id) == 0) {
            gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(item), TRUE);
        }
        g_object_set_data_full(G_OBJECT(item), "rb-profile-id",
                               rb_strdup(p->id), free);
        g_signal_connect(item, "activate", G_CALLBACK(on_menu_profile), app);
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
    }
}

static void on_menu_history(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_history_dialog(app);
}

static void on_menu_about(GtkMenuItem *item, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)item;
    rb_show_about(app);
}

static void rb_build_menu(App *app)
{
    GtkWidget *menu = gtk_menu_new();
    GtkWidget *item;

    item = gtk_menu_item_new_with_label("New Tab");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_new_tab), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Bookmark this page");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_bookmark), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    g_bm_item = gtk_check_menu_item_new_with_label("Show bookmarks bar");
    gtk_check_menu_item_set_active(
        GTK_CHECK_MENU_ITEM(g_bm_item),
        rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1) ? TRUE : FALSE);
    g_signal_connect(g_bm_item, "toggled", G_CALLBACK(on_bm_toggled), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), g_bm_item);

    item = gtk_menu_item_new_with_label("Recent history");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_history), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Downloads");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_downloads), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Find in page");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_find), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Translate this page");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_translate), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Preferences");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_prefs), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    {
        GtkWidget *sub = gtk_menu_new();
        GtkWidget *top = gtk_menu_item_new_with_label("Profiles");
        rb_profile_append_items(sub, app);
        gtk_menu_shell_append(GTK_MENU_SHELL(sub),
                              gtk_separator_menu_item_new());
        item = gtk_menu_item_new_with_label("Add profile");
        g_signal_connect(item, "activate",
                         G_CALLBACK(on_menu_new_profile), app);
        gtk_menu_shell_append(GTK_MENU_SHELL(sub), item);
        gtk_widget_show_all(sub);
        gtk_menu_item_set_submenu(GTK_MENU_ITEM(top), sub);
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), top);
    }

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    item = gtk_menu_item_new_with_label("Notes");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_notes), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("2FA Management");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_totp), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    item = gtk_menu_item_new_with_label("Shield");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_prefs), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    g_js_item = gtk_check_menu_item_new_with_label("Enable JavaScript");
    gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(g_js_item),
                                    app->js_enabled ? TRUE : FALSE);
    g_signal_connect(g_js_item, "toggled", G_CALLBACK(on_js_toggled), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), g_js_item);

    gtk_menu_shell_append(GTK_MENU_SHELL(menu), gtk_separator_menu_item_new());

    item = gtk_menu_item_new_with_label("About");
    g_signal_connect(item, "activate", G_CALLBACK(on_menu_about), app);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);

    gtk_widget_show_all(menu);

    /* gtk_menu_button_set_popup() is deprecated in favour of popovers but
     * still fully functional on GTK 3.x; keep the classic GtkMenu (the
     * check item for JavaScript needs it) and silence the deprecation. */
    G_GNUC_BEGIN_IGNORE_DEPRECATIONS
    gtk_menu_button_set_popup(GTK_MENU_BUTTON(app->menu_btn), menu);
    G_GNUC_END_IGNORE_DEPRECATIONS
}

/* ------------------------------------------------------------------ */
/* Keyboard accelerators (GSimpleAction on the GtkApplicationWindow) */

static void on_action_new_tab(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_new_tab(app);
}

static void on_action_close_tab(GSimpleAction *action, GVariant *parameter,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->active_id) rb_do_close_tab_id(app, app->active_id);
}

static void on_action_focus_omnibox(GSimpleAction *action, GVariant *parameter,
                                    gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (app->omnibox) {
        gtk_widget_grab_focus(app->omnibox);
        gtk_editable_select_region(GTK_EDITABLE(app->omnibox), 0, -1);
    }
}

static void on_action_reload(GSimpleAction *action, GVariant *parameter,
                             gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_do_reload_or_stop(app);
}

static void on_action_back(GSimpleAction *action, GVariant *parameter,
                           gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_back(app);
}

static void on_action_forward(GSimpleAction *action, GVariant *parameter,
                              gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_gw_forward(app);
}

static void on_action_find(GSimpleAction *action, GVariant *parameter,
                           gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    rb_find_show(app);
}

/* Ctrl+G with the bar closed opens it rather than stepping a search nobody
 * can see — the field would otherwise be searching invisibly. */
static void on_action_find_step(GSimpleAction *action, GVariant *parameter,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (!rb_find_is_open(app)) {
        rb_find_show(app);
        return;
    }
    rb_find_step(app, TRUE);
}

static void on_action_find_back(GSimpleAction *action, GVariant *parameter,
                                gpointer user_data)
{
    App *app = (App *)user_data;
    (void)action;
    (void)parameter;
    if (!rb_find_is_open(app)) {
        rb_find_show(app);
        return;
    }
    rb_find_step(app, FALSE);
}

static void rb_add_action(App *app, const char *name, GCallback callback,
                          const gchar *const *accels)
{
    GSimpleAction *action = g_simple_action_new(name, NULL);
    char detailed[64];

    g_signal_connect(action, "activate", callback, app);
    g_action_map_add_action(G_ACTION_MAP(app->win), G_ACTION(action));
    g_object_unref(action);

    if (accels) {
        snprintf(detailed, sizeof detailed, "win.%s", name);
        gtk_application_set_accels_for_action(app->app, detailed, accels);
    }
}

static void rb_add_actions(App *app)
{
    static const gchar *const accels_newtab[]  = { "<Primary>T", NULL };
    static const gchar *const accels_closetab[] = { "<Primary>W", NULL };
    static const gchar *const accels_omni[]   = { "<Primary>L", NULL };
    static const gchar *const accels_reload[] = { "F5", "<Primary>R", NULL };
    static const gchar *const accels_back[]   = { "<Alt>Left", NULL };
    static const gchar *const accels_fwd[]    = { "<Alt>Right", NULL };
    static const gchar *const accels_find[]   = { "<Primary>F", NULL };
    static const gchar *const accels_findn[]  = { "<Primary>G", "F3", NULL };
    static const gchar *const accels_findp[]  = { "<Primary><Shift>G", "<Shift>F3", NULL };

    rb_add_action(app, "new-tab", G_CALLBACK(on_action_new_tab), accels_newtab);
    rb_add_action(app, "close-tab", G_CALLBACK(on_action_close_tab), accels_closetab);
    rb_add_action(app, "focus-omnibox", G_CALLBACK(on_action_focus_omnibox), accels_omni);
    rb_add_action(app, "reload", G_CALLBACK(on_action_reload), accels_reload);
    rb_add_action(app, "back", G_CALLBACK(on_action_back), accels_back);
    rb_add_action(app, "forward", G_CALLBACK(on_action_forward), accels_fwd);
    rb_add_action(app, "find", G_CALLBACK(on_action_find), accels_find);
    rb_add_action(app, "find-next", G_CALLBACK(on_action_find_step), accels_findn);
    rb_add_action(app, "find-previous", G_CALLBACK(on_action_find_back), accels_findp);
}

/* ------------------------------------------------------------------ */
/* Chrome construction (GtkApplication "activate") */

void rb_on_activate(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    GtkWidget *root, *toolbar, *plus, *overlay;

    if (app->win) {
        gtk_window_present(GTK_WINDOW(app->win));
        return;
    }

    rb_css_load(app);
    /* Captures the system font and applies the profile's scale to it.  Before
     * the first window exists is the right moment: nothing has been laid out
     * at the unscaled size, so there is nothing to reflow. */
    rb_apply_font_scale(app);
    rb_apply_reduced_motion(app);

    app->app = gtk_app;
    app->win = GTK_APPLICATION_WINDOW(gtk_application_window_new(gtk_app));
    gtk_window_set_default_size(GTK_WINDOW(app->win), RB_WINDOW_W, RB_WINDOW_H);
    gtk_window_set_title(GTK_WINDOW(app->win), "Room Browser");

    /* The chrome is a column inside an overlay, so the link-target bubble can
     * float over the page instead of stealing a row from it. */
    overlay = gtk_overlay_new();
    app->overlay = overlay;
    root = gtk_box_new(GTK_ORIENTATION_VERTICAL, 0);
    gtk_container_add(GTK_CONTAINER(overlay), root);
    gtk_container_add(GTK_CONTAINER(app->win), overlay);

    /* Tab strip: the notebook pages ARE the per-tab WebKitWebViews. */
    app->notebook = gtk_notebook_new();
    gtk_notebook_set_show_border(GTK_NOTEBOOK(app->notebook), FALSE);
    g_signal_connect(app->notebook, "switch-page",
                     G_CALLBACK(on_switch_page), app);
    plus = gtk_button_new_with_label("+");
    rb_add_class(plus, "rb-btn");
    g_signal_connect(plus, "clicked", G_CALLBACK(on_newtab_clicked), app);
    gtk_notebook_set_action_widget(GTK_NOTEBOOK(app->notebook), plus,
                                    GTK_PACK_END);
    gtk_box_pack_start(GTK_BOX(root), app->notebook, TRUE, TRUE, 0);

    /* The find bar, directly under the page and above the toolbar.  Built
     * hidden: see the hide after show_all below. */
    rb_find_build(app, root);

    /* Toolbar */
    toolbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 4);
    rb_add_class(toolbar, "rb-toolbar");

    app->back = gtk_button_new_with_label("\xE2\x86\x90");
    app->fwd = gtk_button_new_with_label("\xE2\x86\x92");
    app->reload = gtk_button_new_with_label("\xE2\x9F\xB3");
    app->home = gtk_button_new_with_label("\xE2\x8C\x82");
    app->star = gtk_button_new_with_label("\xE2\x98\x86");
    app->menu_btn = gtk_menu_button_new();
    gtk_button_set_label(GTK_BUTTON(app->menu_btn), "\xE2\x98\xB0");
    rb_add_class(app->back, "rb-btn");
    rb_add_class(app->fwd, "rb-btn");
    rb_add_class(app->reload, "rb-btn");
    rb_add_class(app->home, "rb-btn");
    rb_add_class(app->star, "rb-btn");
    rb_add_class(app->menu_btn, "rb-btn");
    g_signal_connect(app->back, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->fwd, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->reload, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->home, "clicked", G_CALLBACK(on_nav_clicked), app);
    g_signal_connect(app->star, "clicked", G_CALLBACK(on_nav_clicked), app);

    app->omnibox = gtk_entry_new();
    rb_add_class(app->omnibox, "rb-omni");
    gtk_entry_set_placeholder_text(GTK_ENTRY(app->omnibox),
                                   "Search or enter address");
    gtk_widget_set_hexpand(app->omnibox, TRUE);
    g_signal_connect(app->omnibox, "activate",
                     G_CALLBACK(on_omni_activate), app);
    g_signal_connect(app->omnibox, "key-press-event",
                     G_CALLBACK(on_omni_key), app);

    rb_build_menu(app);
    rb_gw_context_new(app);

    gtk_box_pack_start(GTK_BOX(toolbar), app->back, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->fwd, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->reload, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->home, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->omnibox, TRUE, TRUE, 6);
    gtk_box_pack_start(GTK_BOX(toolbar), app->star, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(toolbar), app->menu_btn, FALSE, FALSE, 0);
    gtk_box_pack_start(GTK_BOX(root), toolbar, FALSE, FALSE, 0);

    /* The bookmarks bar: one more chrome row, under the toolbar and above the
     * progress strip.  Built empty here and filled by the refresh below. */
    app->bmbar = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 2);
    rb_add_class(app->bmbar, "rb-bmbar");
    gtk_box_pack_start(GTK_BOX(root), app->bmbar, FALSE, FALSE, 0);

    /* The page-load indicator: its own 2px strip between the toolbar and the
     * page.  A drawing area rather than CSS because the segment moves. */
    app->prog_area = gtk_drawing_area_new();
    gtk_widget_set_size_request(app->prog_area, -1, RB_PROGRESS_H);
    g_signal_connect(app->prog_area, "draw", G_CALLBACK(rb_progress_draw), app);
    g_signal_connect(app->prog_area, "destroy",
                     G_CALLBACK(rb_progress_area_gone), app);
    gtk_box_pack_start(GTK_BOX(root), app->prog_area, FALSE, FALSE, 0);

    rb_add_actions(app);

    gtk_widget_show_all(GTK_WIDGET(app->win));

    /* Built AFTER show_all so it starts hidden: a child added to an
     * already-shown container stays hidden until it is shown explicitly,
     * which is what hovering a link does.  (gtk_widget_set_no_show_all would
     * do the same job but is deprecated.) */
    app->status = gtk_label_new(NULL);
    rb_add_class(app->status, "rb-status");
    gtk_label_set_ellipsize(GTK_LABEL(app->status), PANGO_ELLIPSIZE_MIDDLE);
    gtk_label_set_max_width_chars(GTK_LABEL(app->status), 100);
    gtk_widget_set_halign(app->status, GTK_ALIGN_START);
    gtk_widget_set_valign(app->status, GTK_ALIGN_END);
    gtk_widget_set_margin_start(app->status, 8);
    gtk_widget_set_margin_bottom(app->status, 8);
    gtk_overlay_add_overlay(GTK_OVERLAY(overlay), app->status);

    /* Filled after show_all for the same reason as the bubble above: children
     * packed into an already-shown box stay hidden until shown, and the
     * refresh is what shows them — and what hides the whole bar when the
     * profile has no bookmarks or has the bar switched off. */
    rb_bookmarks_bar_refresh(app);

    /* The find bar is built before show_all, unlike the two above, because it
     * belongs in the middle of the column rather than floating over it — so
     * it is shown along with the window and has to be put away again. */
    gtk_widget_hide(app->findbar);

    /* First tab: navigates to the "home" setting. */
    rb_do_new_tab(app);
}

void rb_on_shutdown(GtkApplication *gtk_app, gpointer user_data)
{
    App *app = (App *)user_data;
    (void)gtk_app;
    rb_data_shutdown(app);
}
