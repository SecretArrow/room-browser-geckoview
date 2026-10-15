/*
 * Room Browser (desktop) - Windows chrome implementation.
 *
 * Win32 chrome in pure C11: tab strip (owner-drawn buttons), toolbar with
 * omnibox, bookmark star and menu, plus the shared persistence glue.
 * Dark Brave-inspired palette with purple accent (#A78BFA).
 *
 * Design notes (v1, documented honestly in desktop/README.md):
 *  - All buttons are standard BUTTON controls drawn via BS_OWNERDRAW so the
 *    dark palette is actually rendered (plain WM_CTLCOLOR theming cannot
 *    restyle themed push buttons reliably).
 *  - The omnibox is a standard EDIT control, dark-themed through
 *    WM_CTLCOLORECT + a purple focus ring painted by the parent.
 *  - Keyboard shortcuts are handled in the main window proc; while the
 *    WebView2 child has focus those accelerators are inactive (v1).
 */
#include <windows.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <objbase.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "chrome.h"
#include "downloads.h"
#include "webview.h"
#include "prefs.h"
#include "notes.h"
#include "totp.h"
#include "resource.h"
#include "rb_version.h"

/* ------------------------------------------------------------------ */
/* Small helpers */

/* Single global application state (declared extern in chrome.h). */
App g_app;

wchar_t *rb_utf8_to_wide(const char *s)
{
    int n;
    wchar_t *w;
    if (!s) return NULL;
    n = MultiByteToWideChar(CP_UTF8, 0, s, -1, NULL, 0);
    if (n <= 0) return NULL;
    w = (wchar_t *)malloc((size_t)n * sizeof(wchar_t));
    if (!w) return NULL;
    if (MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n) <= 0) {
        free(w);
        return NULL;
    }
    return w;
}

char *rb_wide_to_utf8(const wchar_t *s)
{
    int n;
    char *u;
    if (!s) return NULL;
    n = WideCharToMultiByte(CP_UTF8, 0, s, -1, NULL, 0, NULL, NULL);
    if (n <= 0) return NULL;
    u = (char *)malloc((size_t)n);
    if (!u) return NULL;
    if (WideCharToMultiByte(CP_UTF8, 0, s, -1, u, n, NULL, NULL) <= 0) {
        free(u);
        return NULL;
    }
    return u;
}

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

void rb_mkdirs_wide(const wchar_t *path)
{
    wchar_t buf[1024];
    size_t len, i;
    if (!path) return;
    len = wcslen(path);
    if (len == 0 || len >= 1024) return;
    memcpy(buf, path, (len + 1) * sizeof(wchar_t));
    for (i = 0; buf[i]; i++) {
        if (buf[i] == L'\\' && i > 0) {
            buf[i] = 0;
            CreateDirectoryW(buf, NULL);
            buf[i] = L'\\';
        }
    }
    CreateDirectoryW(buf, NULL);
}

void rb_mkdirs_utf8(const char *path)
{
    wchar_t *w = rb_utf8_to_wide(path);
    if (!w) return;
    rb_mkdirs_wide(w);
    free(w);
}

/* ------------------------------------------------------------------ */
/* Persistence: load at startup, save on change (and a final save at quit). */

/* The name given to the profile created for a data directory that has none.
 * Android calls its first profile "Personal" too (ProfileManager.create()). */
#define RB_PROFILE_FIRST_NAME "Personal"

/* Where downloads land: the user's Downloads folder, under the subfolder
 * the profile names in RB_PREF_DOWNLOAD_SUBFOLDER ("RoomBrowser" by
 * default) — the same place, and the same setting, as the Android edition.
 * Falls back to <data dir>/downloads when the known folder is unavailable. */
void rb_downloads_dir_refresh(App *app)
{
    const char *sub = rb_pref(app, RB_PREF_DOWNLOAD_SUBFOLDER, "RoomBrowser");
    PWSTR wide = NULL;

    if (sub == NULL) sub = "RoomBrowser";
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_Downloads, 0, NULL, &wide)) &&
        wide != NULL) {
        char *base = rb_wide_to_utf8(wide);
        CoTaskMemFree(wide);
        if (base != NULL) {
            app->download_dir = (sub[0] != '\0')
                ? rb_paths_join(base, sub) : base;
            if (sub[0] != '\0') free(base);
        }
    } else {
        char *data = rb_paths_data_dir();
        app->download_dir = data ? rb_paths_join(data, "downloads") : NULL;
        if (data) rb_paths_free(data);
    }
    if (app->download_dir != NULL) {
        rb_mkdirs_utf8(app->download_dir);
    }
}

int rb_data_init(App *app)
{
    char *dir = rb_paths_data_dir();
    int fresh_profiles = 0;

    app->tabs = rb_tabs_new();
    app->history = rb_history_new();
    app->bookmarks = rb_bookmarks_new();
    app->settings = rb_settings_new();
    app->profiles = rb_profile_registry_new();
    app->switcher = rb_switch_new();
    app->https = rb_https_pending_new();
    app->downloads = rb_downloads_new();
    app->filters = rb_filters_new();
    if (!app->tabs || !app->history || !app->bookmarks || !app->settings ||
        !app->profiles || !app->switcher || !app->https || !app->downloads ||
        !app->filters) {
        return -1;
    }
    /* The bundled list, compiled in: no network, no update check, the same
     * hosts the Android edition ships.  Loading it is what makes the filter
     * engine able to answer at all — the per-profile switches decide which
     * of its categories are consulted. */
    rb_filters_load_builtin(app->filters);
    if (!dir) return -1;

    app->path_history = rb_paths_join(dir, "history.jsonl");
    app->path_bookmarks = rb_paths_join(dir, "bookmarks.jsonl");
    app->path_settings = rb_paths_join(dir, "settings.txt");
    app->path_profiles = rb_paths_join(dir, "profiles.jsonl");
    app->path_downloads = rb_paths_join(dir, "downloads.jsonl");
    if (!app->path_history || !app->path_bookmarks || !app->path_settings ||
        !app->path_profiles || !app->path_downloads) {
        rb_paths_free(dir);
        return -1;
    }
    rb_mkdirs_utf8(dir);

    /* The GLOBAL store is BrowserGlobalSettings: the handful of settings that
     * belong to the installation rather than to a profile (DNS overrides,
     * network-change retention, telemetry).  Everything a user toggles in the
     * menu lives in the active profile — see rb_pref(). */
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
        if (first == NULL) first = rb_profile_at(app->profiles, 0);
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
    rb_downloads_dir_refresh(app);
    /* The palette is resolved (and its brushes built) by rb_theme_apply()
     * from rb_chrome_create: there is no window to repaint yet. */

    /* A profile file from the build whose theme combo wrote the theme ID into
     * the MODE setting is repaired once, here, so a choice made with that
     * picker shows up instead of sitting inert in the wrong key. */
    rb_profile_repair_theme_setting(app->profiles);

    rb_profiles_save(app);
    rb_paths_free(dir);
    return 0;
}

void rb_data_shutdown(App *app)
{
    if (app->history && app->path_history) rb_history_save(app->history, app->path_history);
    if (app->bookmarks && app->path_bookmarks) rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    if (app->settings && app->path_settings) rb_settings_save(app->settings, app->path_settings);
    if (app->downloads && app->path_downloads) rb_downloads_save(app->downloads, app->path_downloads);
    rb_profiles_save(app);
}

void rb_data_free(App *app)
{
    int i;
    for (i = 0; i < RB_HIST_MENU_MAX; i++) {
        free(app->hist_menu[i]);
        app->hist_menu[i] = NULL;
    }
    app->hist_menu_n = 0;
    for (i = 0; i < RB_PROF_MENU_MAX; i++) {
        free(app->prof_menu[i]);
        app->prof_menu[i] = NULL;
    }
    app->prof_menu_n = 0;
    free(app->path_history);   app->path_history = NULL;
    free(app->path_bookmarks); app->path_bookmarks = NULL;
    free(app->path_settings);  app->path_settings = NULL;
    free(app->path_profiles);  app->path_profiles = NULL;
    free(app->path_downloads); app->path_downloads = NULL;
    free(app->download_dir);   app->download_dir = NULL;
    free(app->home_url);       app->home_url = NULL;
    free(app->active_profile_id); app->active_profile_id = NULL;
    if (app->tabs)      { rb_tabs_free(app->tabs);        app->tabs = NULL; }
    if (app->history)   { rb_history_free(app->history);  app->history = NULL; }
    if (app->bookmarks) { rb_bookmarks_free(app->bookmarks); app->bookmarks = NULL; }
    if (app->downloads) { rb_downloads_free(app->downloads); app->downloads = NULL; }
    if (app->settings)  { rb_settings_free(app->settings); app->settings = NULL; }
    if (app->profiles)  { rb_profile_registry_free(app->profiles); app->profiles = NULL; }
    if (app->switcher)  { rb_switch_free(app->switcher);  app->switcher = NULL; }
    if (app->https)     { rb_https_pending_free(app->https); app->https = NULL; }
    if (app->filters)   { rb_filters_free(app->filters);  app->filters = NULL; }
    free(app->tab_btns);   app->tab_btns = NULL;
    free(app->tab_closes); app->tab_closes = NULL;
    app->tab_slots = 0;
    if (app->br_chrome)  { DeleteObject(app->br_chrome);  app->br_chrome = NULL; }
    if (app->br_toolbar) { DeleteObject(app->br_toolbar); app->br_toolbar = NULL; }
    if (app->br_tab_idle){ DeleteObject(app->br_tab_idle);app->br_tab_idle = NULL; }
    if (app->br_omni)    { DeleteObject(app->br_omni);    app->br_omni = NULL; }
    if (app->br_accent)  { DeleteObject(app->br_accent);  app->br_accent = NULL; }
    if (app->fnt_ui)     { DeleteObject(app->fnt_ui);     app->fnt_ui = NULL; }
    if (app->fnt_omni)   { DeleteObject(app->fnt_omni);   app->fnt_omni = NULL; }
}

/* ------------------------------------------------------------------ */
/* Profile-scoped settings.
 *
 * Android reads every feature switch off the active ProfileSettings.  These
 * are the Win32 spelling of that read, so a feature never has to know
 * whether a profile exists, and a missing key falls back to the same default
 * the Android edition uses. */

const rb_profile *rb_active_profile(App *app)
{
    if (!app || !app->profiles || !app->active_profile_id) return NULL;
    return rb_profile_by_id(app->profiles, app->active_profile_id);
}

const char *rb_pref(App *app, const char *key, const char *fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) return fallback;
    return rb_settings_get(p->settings, key, fallback);
}

int rb_pref_int(App *app, const char *key, int fallback)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p || !p->settings) return fallback;
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
    if (!p) return;
    /* rb_profile_by_id hands out a const row, but the settings store it
     * points at is not itself const: the pointer member is what carries the
     * qualification, so reading it out yields a mutable rb_settings *.  The
     * registry owns the store, so it is saved with the profile. */
    rb_settings_set(p->settings, key, value);
    rb_profiles_save(app);
}

void rb_pref_set_int(App *app, const char *key, int value)
{
    const rb_profile *p = rb_active_profile(app);
    if (!p) return;
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
 * engine default should be sent untouched.  A device, when the profile
 * presents one, decides the UA on its own; otherwise ua_mode picks between
 * the engine default, a preset, and a free-form string.  The device wins
 * because it also decides the shim: see rb_ua_current in the GTK edition. */
char *rb_ua_current(App *app)
{
    const char *mode;
    rb_ua_mode m = RB_UA_MODE_DEFAULT;
    char *device_ua = rb_device_ua_for(rb_pref(app, RB_PREF_DEVICE_ID, NULL));

    if (device_ua != NULL) return device_ua;
    mode = rb_pref(app, RB_PREF_UA_MODE, "default");
    if (mode != NULL) {
        if (strcmp(mode, "preset") == 0) m = RB_UA_MODE_PRESET;
        else if (strcmp(mode, "custom") == 0) m = RB_UA_MODE_CUSTOM;
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
/* Theme.
 *
 * The profile stores only the theme id (inside its theme_json); the palette
 * itself is compiled into the core.  Win32 has no stylesheet, so the theme
 * shows up as the brush set plus the text colour the owner-draw code uses —
 * which is why the palette is kept in App rather than recomputed per paint.
 */

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

/* "system|light|dark|amoled" -> the core's mode enum. */
static rb_theme_mode rb_theme_mode_of(App *app)
{
    const char *m = rb_pref(app, RB_PREF_THEME, "system");
    if (m != NULL) {
        if (strcmp(m, "light") == 0)  return RB_THEME_LIGHT;
        if (strcmp(m, "dark") == 0)   return RB_THEME_DARK;
        if (strcmp(m, "amoled") == 0) return RB_THEME_AMOLED;
    }
    return RB_THEME_AUTO;
}

/* Whether the OS is currently in its dark app mode.  AUTO consults this and
 * nothing else; the other modes ignore it. */
static int rb_system_is_dark(void)
{
    DWORD light = 1;
    DWORD n = sizeof light;
    HKEY k;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,
                      L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                      0, KEY_QUERY_VALUE, &k) == ERROR_SUCCESS) {
        RegQueryValueExW(k, L"AppsUseLightTheme", NULL, NULL, (LPBYTE)&light, &n);
        RegCloseKey(k);
    }
    return light == 0;
}

COLORREF rb_col(unsigned int argb)
{
    int r = 0, g = 0, b = 0;
    rb_theme_rgb(argb, &r, &g, &b);
    return RGB(r, g, b);
}

void rb_theme_apply(App *app)
{
    const rb_theme *t = rb_theme_current(app);

    app->pal = rb_theme_palette(t, rb_theme_mode_of(app), rb_system_is_dark());

    /* "High contrast" is a property of the chrome, like the theme itself, so
     * it is applied where the palette is resolved rather than at every use. */
    if (rb_pref_int(app, RB_PREF_HIGH_CONTRAST, 0)) {
        app->pal = rb_theme_high_contrast(app->pal);
    }

    if (app->br_chrome)   DeleteObject(app->br_chrome);
    if (app->br_toolbar)  DeleteObject(app->br_toolbar);
    if (app->br_tab_idle) DeleteObject(app->br_tab_idle);
    if (app->br_omni)     DeleteObject(app->br_omni);
    if (app->br_accent)   DeleteObject(app->br_accent);
    /* The token mapping the Win32 chrome has always used: the window is the
     * background, the toolbar and the active tab are the surface, idle tabs
     * are the tab bar, the omnibox is the address bar and the accent is the
     * theme's primary.  With the default theme this reproduces the hard-coded
     * Brave-inspired palette these brushes used to be built from. */
    app->br_chrome   = CreateSolidBrush(rb_col(app->pal.background));
    app->br_toolbar  = CreateSolidBrush(rb_col(app->pal.surface));
    app->br_tab_idle = CreateSolidBrush(rb_col(app->pal.tab_bar));
    app->br_omni     = CreateSolidBrush(rb_col(app->pal.address_bar));
    app->br_accent   = CreateSolidBrush(rb_col(app->pal.primary));

    if (app->hwnd) {
        InvalidateRect(app->hwnd, NULL, TRUE);
        /* The toolbar buttons and tab strip are owner-drawn but read the
         * palette, so they need a repaint too. */
        rb_tabs_rebuild(app);
    }
}

/* ------------------------------------------------------------------ */
/* Message box.  Falls back to a stderr line when there is no window yet
 * (a preference applied before the chrome exists, a startup failure). */

void rb_warn(App *app, const char *title, const char *body)
{
    wchar_t *wt = rb_utf8_to_wide(title ? title : "Room Browser");
    wchar_t *wb = rb_utf8_to_wide(body ? body : "");

    if (app && app->hwnd && wt) {
        MessageBoxW(app->hwnd, wb ? wb : L"", wt, MB_OK | MB_ICONWARNING);
    } else {
        fprintf(stderr, "%s: %s\n", title ? title : "Room Browser",
                body ? body : "");
    }
    free(wt);
    free(wb);
}

/* ------------------------------------------------------------------ */
/* UI refresh helpers */

void rb_update_omni(App *app, const char *url)
{
    wchar_t *w = rb_utf8_to_wide(url ? url : "");
    if (!w) return;
    SetWindowTextW(app->omni, w);
    free(w);
}

void rb_update_titlebar(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (t && t->title && t->title[0]) {
        wchar_t *wt = rb_utf8_to_wide(t->title);
        if (wt) {
            wchar_t buf[600];
            if (swprintf(buf, 600, L"%ls \x2014 Room Browser", wt) > 0) {
                SetWindowTextW(app->hwnd, buf);
            }
            free(wt);
            return;
        }
    }
    SetWindowTextW(app->hwnd, L"Room Browser");
}

void rb_update_nav(App *app)
{
    int back = 0, fwd = 0;
    rb_wv_can_nav(app, &back, &fwd);
    EnableWindow(app->back, back ? TRUE : FALSE);
    EnableWindow(app->fwd, fwd ? TRUE : FALSE);
}

void rb_update_star(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    int on = (t && t->url && rb_bookmarks_contains(app->bookmarks, t->url));
    SetWindowTextW(app->star, on ? L"\x2605" : L"\x2606");
}

void rb_update_reloadbtn(App *app)
{
    SetWindowTextW(app->reload, app->loading ? L"\x00D7" : L"\x21BB");
}

/* ------------------------------------------------------------------ */
/* The page-load indicator.
 *
 * A 2px accent segment sliding along the bottom edge of the toolbar while a
 * page is loading, and nothing at all when none is.  It is deliberately
 * minimal: no track, no text, no percentage, and it is drawn in the two
 * pixels between the toolbar buttons (which end at RB_TABSTRIP_H +
 * RB_TOOLBAR_H - 2) and the webview (which starts at RB_TABSTRIP_H +
 * RB_TOOLBAR_H), so it never covers anything being read.  The reload button
 * flipping to a stop glyph stays the authoritative signal; this only makes a
 * slow load visible at a glance.
 *
 * A tick repaints the 2-row strip alone, not the window, so an animation that
 * runs for the length of a page load costs two rows of blitting per frame. */

#define RB_TIMER_PROGRESS  1
#define RB_PROGRESS_H      2
#define RB_PROGRESS_MS     16      /* ~60 Hz */
#define RB_PROGRESS_STEPS  140     /* ticks for one left-to-right sweep */
#define RB_PROGRESS_DIV    4       /* segment width = span / this */

static int g_prog_on;              /* 1 while the animation timer is running */
static int g_prog_show;            /* 1 while the strip should be drawn at all;
                                    * a load with "Reduce motion" on shows the
                                    * strip without animating it */
static int g_prog_phase;           /* 0..RB_PROGRESS_STEPS */

static void rb_progress_rect(App *app, RECT *out)
{
    RECT rc;
    GetClientRect(app->hwnd, &rc);
    out->left   = 0;
    out->right  = rc.right;
    out->bottom = rb_scaled(app, RB_TABSTRIP_H) + rb_scaled(app, RB_TOOLBAR_H);
    out->top    = out->bottom - RB_PROGRESS_H;
}

/* Draws the segment into an existing DC.  Called from WM_PAINT, whose
 * FillRect over the same strip has just erased the previous frame. */
static void rb_progress_paint(App *app, HDC dc)
{
    RECT strip, seg;
    int span, seg_w;

    if (!g_prog_show || app->br_accent == NULL) return;
    rb_progress_rect(app, &strip);
    span = strip.right - strip.left;
    if (span <= 0) return;

    seg_w = span / RB_PROGRESS_DIV;
    if (seg_w < 32) seg_w = 32;

    /* Enters from the left edge, leaves past the right edge, repeats — the
     * standard indeterminate sweep, with no notion of "how far along". */
    seg.left   = strip.left + (g_prog_phase * (span + seg_w)) / RB_PROGRESS_STEPS - seg_w;
    seg.right  = seg.left + seg_w;
    seg.top    = strip.top;
    seg.bottom = strip.bottom;

    if (seg.right <= strip.left || seg.left >= strip.right) return;
    if (seg.left  < strip.left)  seg.left  = strip.left;
    if (seg.right > strip.right) seg.right = strip.right;
    FillRect(dc, &seg, app->br_accent);
}

static void rb_progress_tick(App *app)
{
    RECT strip;

    g_prog_phase++;
    if (g_prog_phase > RB_PROGRESS_STEPS) g_prog_phase = 0;
    rb_progress_rect(app, &strip);
    InvalidateRect(app->hwnd, &strip, FALSE);
}

/* Shows the strip while a load is in flight, and sweeps it only when the
 * profile has not asked for reduced motion.  The strip is still the honest
 * signal that a page is loading, so with the animation off it parks as a
 * stationary segment in the middle rather than disappearing; on the way out
 * it is invalidated one last time with g_prog_show already 0. */
static void rb_progress_sync(App *app)
{
    RECT strip;
    int still = rb_pref_int(app, RB_PREF_REDUCED_MOTION, 0) ? 1 : 0;
    int want  = (app->loading && !still) ? 1 : 0;

    g_prog_show = app->loading ? 1 : 0;

    /* The parked frame is the sweep's own midpoint, so switching the setting
     * on mid-load leaves the segment where the animation already was. */
    if (still)                   g_prog_phase = RB_PROGRESS_STEPS / 2;
    else if (want && !g_prog_on) g_prog_phase = 0;

    if (want != g_prog_on) {
        g_prog_on = want;
        if (want) SetTimer(app->hwnd, RB_TIMER_PROGRESS, RB_PROGRESS_MS, NULL);
        else      KillTimer(app->hwnd, RB_TIMER_PROGRESS);
    }
    rb_progress_rect(app, &strip);
    InvalidateRect(app->hwnd, &strip, FALSE);
}

/* The same repaint for callers outside this file (the preferences window, when
 * "Reduce motion" is toggled): the sync above is the only thing that knows
 * whether a load is in flight, so it must run rather than the callers guessing. */
void rb_progress_refresh(App *app)
{
    rb_progress_sync(app);
}

/* The one place app->loading changes.  Everything that shows loading state —
 * the reload/stop glyph and the progress strip — is driven from here, so the
 * three call sites (navigation starting, navigation done, tabs torn down)
 * cannot disagree about whether a page is loading. */
void rb_set_loading(App *app, int on)
{
    app->loading = on ? 1 : 0;
    rb_update_reloadbtn(app);
    rb_progress_sync(app);
}

void rb_update_all(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    rb_update_omni(app, t && t->url ? t->url : "");
    rb_update_titlebar(app);
    rb_update_nav(app);
    rb_update_star(app);
    rb_update_reloadbtn(app);
}

/* ------------------------------------------------------------------ */
/* Actions */

void rb_do_new_tab(App *app)
{
    rb_do_add_tab(app, NULL);
}

/* Adds a tab pointing at `url` (NULL means the homepage) and makes it
 * active.  Split out of rb_do_new_tab because the session restore has a URL
 * for every tab it re-creates and the homepage is only the fallback. */
void rb_do_add_tab(App *app, const char *url)
{
    /* A tab opened without an address lands on the homepage, unless the
     * profile turned that off — in which case it opens empty.  Reading the
     * switch here is what makes it real: it used to be stored and ignored. */
    const char *target = (url != NULL && url[0] != '\0')
        ? url
        : (rb_pref_int(app, RB_PREF_HOMEPAGE_ENABLED, 1)
               ? (app->home_url ? app->home_url : "https://duckduckgo.com")
               : "about:blank");
    long id = rb_tabs_add(app->tabs, "New Tab", target, rb_profile_now_ms());
    app->active_id = id;
    rb_tabs_rebuild(app);
    rb_update_all(app);
    rb_wv_activate(app, id);
}

void rb_do_close_tab(App *app, long id)
{
    rb_wv_drop_tab(app, id);
    rb_tabs_close(app->tabs, id, rb_profile_now_ms());
    if (app->active_id == id) app->active_id = 0;
    if (rb_tabs_count(app->tabs) == 0) {
        rb_do_new_tab(app);
        return;
    }
    if (app->active_id == 0) {
        const rb_tab *first = rb_tabs_at(app->tabs, 0);
        app->active_id = first ? first->id : 0;
    }
    rb_tabs_rebuild(app);
    rb_update_all(app);
    if (app->active_id) rb_wv_activate(app, app->active_id);
}

void rb_do_activate(App *app, long id)
{
    app->active_id = id;
    rb_tabs_rebuild(app);
    rb_update_all(app);
    rb_wv_activate(app, id);
    /* After the view is up: the bar's count is the new page's, and the old
     * one is now wrong.  A no-op while the bar is closed. */
    rb_findbar_retarget(app);
}

/* Android's TranslateDialog, minus the dialog: the target language already
 * has a row in Preferences, so the only thing a menu item has to decide is
 * whether to translate this page.  The wrapper URL comes from core, so both
 * editions send the identical request. */
static void rb_do_translate(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
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
    t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (!t) return;

    /* HTTPS-First.  The omnibox already sends a bare domain straight to
     * https, so what this catches is everything else that is still plain
     * http at load time: a typed http:// URL, a bookmark or homepage entry,
     * a session-restored tab.  The upgrade is recorded so that the failure
     * handler can retry the original once — see the NavigationCompleted
     * handler in webview.c.  With the setting off, http URLs load as typed,
     * exactly as on Android. */
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
    rb_wv_navigate(app, target);
    free(upgraded);
}

/* ------------------------------------------------------------------ */
/* Profile switching.
 *
 * The steps are the core's (rb_switch_step), and each one does here what the
 * GTK edition does there.  The reason the protocol exists at all is the user
 * data folder: WebView2 binds cookies, storage and the cache to the
 * environment, and an environment can never be reused for a second profile.
 * So a switch tears the whole browser context down and builds a new one —
 * which is also why the tabs are saved, destroyed and restored around it
 * rather than kept. */

/* Open tabs and the active index are desktop-only keys: Android keeps its
 * tabs in a per-profile Room database, the desktop keeps them in the
 * profile's own settings.  The NAMES are a file format shared with the GTK
 * edition, so they are spelled identically in both. */
#define RB_PREF_OPEN_TABS_LOCAL  "open_tabs"    /* '\n'-separated URLs */
#define RB_PREF_ACTIVE_TAB_LOCAL "active_tab"   /* index into open_tabs  */

static void rb_tabs_save_state(App *app)
{
    size_t cap = 1;
    char *joined;
    char *w;
    int i;
    int n;
    int active = 0;
    int written = 0;

    /* Sizing pass, then one write: the URLs can be long and there are as
     * many of them as the user has tabs. */
    n = rb_tabs_count(app->tabs);
    for (i = 0; i < n; i++) {
        const rb_tab *t = rb_tabs_at(app->tabs, i);
        if (t && t->url && t->url[0]) cap += strlen(t->url) + 1;
    }
    joined = (char *)malloc(cap);
    if (joined == NULL) return;
    w = joined;
    for (i = 0; i < n; i++) {
        const rb_tab *t = rb_tabs_at(app->tabs, i);
        size_t len;
        if (!t || !t->url || !t->url[0]) continue;
        /* The index has to be into what was WRITTEN, not into the store:
         * the restore reads it back positionally. */
        if (t->id == app->active_id) active = written;
        len = strlen(t->url);
        memcpy(w, t->url, len);
        w += len;
        *w++ = '\n';
        written++;
    }
    *w = '\0';

    rb_pref_set(app, RB_PREF_OPEN_TABS_LOCAL, joined);
    rb_pref_set_int(app, RB_PREF_ACTIVE_TAB_LOCAL, active);
    free(joined);
}

/* Re-opens the tabs the profile had.  `want` is the index into the list that
 * was written, which is why the restore counts as it opens rather than
 * trusting the store's ordering. */
static void rb_tabs_restore_state(App *app)
{
    const char *joined = rb_pref(app, RB_PREF_OPEN_TABS_LOCAL, NULL);
    int want = rb_pref_int(app, RB_PREF_ACTIVE_TAB_LOCAL, 0);
    long open_ids[64];
    int opened = 0;

    if (joined != NULL && joined[0] != '\0') {
        const char *a = joined;
        while (*a != '\0' && opened < 64) {
            const char *b = strchr(a, '\n');
            size_t len = (b != NULL) ? (size_t)(b - a) : strlen(a);
            if (len > 0) {
                char *url = (char *)malloc(len + 1);
                if (url == NULL) break;
                memcpy(url, a, len);
                url[len] = '\0';
                rb_do_add_tab(app, url);
                open_ids[opened] = app->active_id;
                opened++;
                free(url);
            }
            if (b == NULL) break;
            a = b + 1;
        }
    }
    if (opened == 0) {
        rb_do_add_tab(app, NULL);   /* a fresh profile opens on the homepage */
        return;
    }
    if (want >= 0 && want < opened) {
        rb_do_activate(app, open_ids[want]);
    }
}

/* Removes every tab and drops its view.  The views have to be gone before the
 * environment they were built on is released. */
static void rb_tabs_destroy_all(App *app)
{
    app->active_id = 0;
    rb_set_loading(app, 0);
    while (rb_tabs_count(app->tabs) > 0) {
        const rb_tab *t = rb_tabs_at(app->tabs, 0);
        long id;
        if (t == NULL) break;
        id = t->id;   /* close() frees the row this pointer names */
        rb_wv_drop_tab(app, id);
        rb_tabs_close(app->tabs, id, rb_profile_now_ms());
    }
    rb_tabs_forget_closed(app->tabs);
    rb_tabs_rebuild(app);
    rb_update_all(app);
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
        rb_warn(app, "Cannot switch profile", why);
        return;
    }

    while ((step = rb_switch_step_of(app->switcher)) >= 0) {
        switch (step) {
        case RB_SWITCH_STOP_NAVIGATION:
            rb_wv_stop_all(app);
            break;
        case RB_SWITCH_SAVE_TAB_STATE:
            /* The OLD profile is still active, so the tabs land in its own
             * settings — which is the whole point of the step. */
            rb_tabs_save_state(app);
            break;
        case RB_SWITCH_DESTROY_BROWSER_CONTEXT:
            rb_tabs_destroy_all(app);
            /* Releases the environment, the controllers and the views.  The
             * user data folder it was bound to is left on disk, which is
             * what makes the profile still there when it is switched back
             * to. */
            rb_wv_shutdown(app);
            /* The engine that owned this profile's transfers went with it, so
             * any row of this profile still marked QUEUED or RUNNING is
             * describing a download nothing is carrying any more.  Done here
             * rather than later because rb_data_shutdown, a few steps down,
             * is what writes the store back to disk. */
            rb_downloads_reconcile(app->downloads, from_id,
                                   "Profile switched");
            break;
        case RB_SWITCH_FLUSH_PROFILE_STATE:
            /* The notes and 2FA windows keep their stores themselves, so
             * they commit under the OLD profile here, where the other
             * per-profile state is flushed. */
            rb_notes_flush(app);
            rb_totp_flush(app);
            rb_data_shutdown(app);
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
            /* Re-read everything the new profile owns.  This has to happen
             * BEFORE the environment is created, because the environment is
             * what the settings are applied to. */
            app->js_enabled = rb_pref_int(app, RB_PREF_JAVASCRIPT, 1);
            rb_set_str(&app->home_url,
                       rb_strdup(rb_pref(app, RB_PREF_HOME_LOCAL,
                                         "https://duckduckgo.com")));
            rb_set_str(&app->download_dir, NULL);
            rb_downloads_dir_refresh(app);
            /* The windows over the profile's own files (notes, 2FA) re-read
             * here, next to everything else the new profile owns. */
            rb_notes_reload(app);
            rb_totp_reload(app);
            rb_theme_apply(app);       /* the new profile's palette */
            rb_apply_font_scale(app);  /* ...and its font scale */
            rb_progress_refresh(app);  /* ...and whether a load may animate */
            rb_profiles_save(app);
            /* A fresh environment on the new profile's own user data folder.
             * This is the step that actually isolates the profile. */
            rb_wv_init(app);
            /* The bar is per profile, so the new one may show a different set
             * of rows or hide the bar entirely.  After rb_wv_init, because
             * the refresh re-lays the page out. */
            rb_bmbar_refresh(app);
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
        rb_warn(app, "Profile switch failed", err ? err : "unknown error");
    }
    /* Leave the machine ready for the next switch.  reset() clears the
     * snapshot, so it is read above, before this. */
    rb_switch_reset(app->switcher);
    rb_update_all(app);
}

/* Creates a profile and leaves it in the menu without switching to it — the
 * row is how the user sees which profile they landed in, so switching
 * straight from the click would take that away. */
static void rb_profiles_add(App *app, const char *suffix)
{
    char name[64];
    char id[RB_PROFILE_ID_LEN + 1];
    int n;
    int made;

    if (app == NULL || app->profiles == NULL) return;
    n = rb_profile_count(app->profiles);
    snprintf(name, sizeof name, "%s %d",
             (suffix != NULL && suffix[0]) ? suffix : "Profile", n + 1);
    id[0] = '\0';
    /* create() refuses a name that is already taken, so a user who renamed a
     * profile to "Profile 2" makes this call fail — bump the number until it
     * does not, which is what the GTK edition and the Android sheet do,
     * rather than refusing to add a profile at all. */
    for (;;) {
        made = rb_profile_create(app->profiles, name, NULL, 0, 1);
        if (made == 0 || n >= 99) break;
        n++;
        snprintf(name, sizeof name, "%s %d",
                 (suffix != NULL && suffix[0]) ? suffix : "Profile", n + 1);
    }
    if (made != 0) {
        rb_warn(app, "Cannot add a profile",
                "The browser could not create another profile.");
        return;
    }
    {
        const rb_profile *p =
            rb_profile_at(app->profiles, rb_profile_count(app->profiles) - 1);
        if (p != NULL) snprintf(id, sizeof id, "%s", p->id);
    }
    if (id[0] == '\0') return;
    rb_profiles_save(app);
}

void rb_do_toggle_bookmark(App *app)
{
    rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
    if (!t || !t->url || !t->url[0]) return;
    /* BrowserViewModel.toggleBookmark, policy included: the same call stars
     * and unstars, and a blank title falls back to the URL. */
    (void)rb_bookmarks_toggle(app->bookmarks, t->url,
                              t->title && t->title[0] ? t->title : t->url,
                              rb_profile_now_ms());
    if (app->path_bookmarks) rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
    rb_update_star(app);
    rb_bmbar_refresh(app);
}

/* ------------------------------------------------------------------ */
/* The bookmarks bar
 *
 * Brave's bookmarks bar, built from the two shapes the store's ordering
 * gives: a run of contiguous rows sharing a folder name becomes one button
 * that pops the folder's contents, and a folder-less row becomes a button
 * that opens it.  The GTK edition builds the same two shapes from the same
 * rule, so the two bars list the same things in the same order.
 *
 * The rows are SNAPSHOTTED into s_bm when the bar is built, for the reason
 * rb_menu_show gives about the history and profile menus: the store can be
 * mutated while a popup built from it is still open, and a pointer taken at
 * build time would be stale by the time a click landed.
 *
 * A button whose id is in [RB_ID_BM_FIRST, RB_ID_BM_FIRST + s_bm_n) is a
 * bookmarks-bar button.  The base sits BELOW RB_ID_TAB_FIRST on purpose:
 * rb_draw_button derives a tab index by subtracting RB_ID_TAB_FIRST, so an
 * id above it would be painted as a tab. */

#define RB_BM_MAX 512   /* buttons; the bar clips whatever does not fit */

typedef struct {
    char  *label;    /* what the button shows */
    int    folder;   /* 1 = pop the members up, 0 = open url[0] */
    char **url;
    char **title;
    int    n;
} RbBmSlot;

static RbBmSlot *s_bm;
static int       s_bm_n;

static void rb_bm_slots_free(void)
{
    int i, k;

    for (i = 0; i < s_bm_n; i++) {
        for (k = 0; k < s_bm[i].n; k++) {
            free(s_bm[i].url[k]);
            free(s_bm[i].title[k]);
        }
        free(s_bm[i].url);
        free(s_bm[i].title);
        free(s_bm[i].label);
    }
    free(s_bm);
    s_bm = NULL;
    s_bm_n = 0;
}

/* One slot per folder group or folder-less bookmark, in the store's order. */
static void rb_bm_slots_build(App *app)
{
    int i, n;

    rb_bm_slots_free();
    n = rb_bookmarks_count(app->bookmarks);
    if (n <= 0) return;

    s_bm = (RbBmSlot *)calloc((size_t)n, sizeof *s_bm);
    if (s_bm == NULL) return;

    for (i = 0; i < n && s_bm_n < RB_BM_MAX; ) {
        const rb_bookmark *bm = rb_bookmarks_at(app->bookmarks, i);
        RbBmSlot *s;

        if (bm == NULL || bm->url == NULL || !bm->url[0]) { i++; continue; }
        s = &s_bm[s_bm_n];

        if (bm->folder != NULL && bm->folder[0]) {
            /* Folder rows are contiguous, so this group runs until the folder
             * name changes — or until a folder-less row, which sorts last. */
            const char *folder = bm->folder;
            int cap = 0, k = 0;

            s->folder = 1;
            s->label = rb_strdup(folder);
            while (i < n) {
                const rb_bookmark *f = rb_bookmarks_at(app->bookmarks, i);
                if (f == NULL || f->url == NULL || !f->url[0]) { i++; continue; }
                if (f->folder == NULL || strcmp(f->folder, folder) != 0) break;
                if (k == cap) {
                    /* Each buffer is adopted as soon as it is allocated: a
                     * realloc that fails has already freed the old block, so
                     * holding both results and assigning them together would
                     * leave one of the two pointers dangling for
                     * rb_bm_slots_free() to free a second time. */
                    char **nu, **nt;
                    int ncap = (cap > 0) ? cap * 2 : 4;
                    nu = (char **)realloc(s->url, (size_t)ncap * sizeof(char *));
                    if (nu == NULL) break;
                    s->url = nu;
                    nt = (char **)realloc(s->title, (size_t)ncap * sizeof(char *));
                    if (nt == NULL) break;
                    s->title = nt;
                    cap = ncap;
                }
                s->url[k] = rb_strdup(f->url);
                s->title[k] = rb_strdup((f->title != NULL && f->title[0])
                                            ? f->title : f->url);
                k++;
                i++;
            }
            s->n = k;
        } else {
            s->folder = 0;
            s->label = rb_strdup((bm->title != NULL && bm->title[0])
                                     ? bm->title : bm->url);
            s->url = (char **)calloc(1, sizeof(char *));
            s->title = (char **)calloc(1, sizeof(char *));
            if (s->url != NULL && s->title != NULL) {
                s->url[0] = rb_strdup(bm->url);
                s->title[0] = rb_strdup(s->label);
                s->n = 1;
            }
            i++;
        }
        s_bm_n++;
    }
}

static int rb_bm_context(App *app, HWND child);

/* Middle-click opens a bookmark in a new tab, the way the GTK bar does, and
 * a right click is answered here rather than left to DefWindowProc.  A
 * standard control does forward WM_RBUTTONUP onward as WM_CONTEXTMENU, but
 * that is a convention of the default procedure rather than a promise, and
 * handling it here means one path and no chance of the menu opening twice. */
static LRESULT CALLBACK rb_bm_subclass(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp,
                                       UINT_PTR uSubclass, DWORD_PTR dwData)
{
    (void)wp; (void)lp; (void)uSubclass;
    if (msg == WM_MBUTTONUP) {
        int i = (int)dwData;
        if (i >= 0 && i < s_bm_n && s_bm[i].n > 0 && s_bm[i].url[0] != NULL) {
            rb_do_add_tab(&g_app, s_bm[i].url[0]);
        }
        return 0;
    }
    if (msg == WM_RBUTTONUP) {
        rb_bm_context(&g_app, hwnd);
        return 0;
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

/* The folder button's popup.  TPM_RETURNCMD rather than a posted WM_COMMAND:
 * with the command posted, the id would arrive after this function had
 * released the snapshot it names, which is the race s_bm exists to avoid. */
static void rb_bm_folder_popup(App *app, int slot)
{
    HMENU m;
    RECT r;
    int k, cmd;

    if (slot < 0 || slot >= s_bm_n || slot >= app->bm_slots) return;
    m = CreatePopupMenu();
    if (m == NULL) return;

    for (k = 0; k < s_bm[slot].n; k++) {
        wchar_t *w = rb_utf8_to_wide(s_bm[slot].title[k]);
        if (w != NULL) {
            AppendMenuW(m, MF_STRING, (UINT_PTR)(RB_ID_BM_ITEM_FIRST + k), w);
            free(w);
        }
    }
    if (s_bm[slot].n == 0) AppendMenuW(m, MF_STRING | MF_GRAYED, 0, L"(empty)");

    GetWindowRect(app->bm_btns[slot], &r);
    SetForegroundWindow(app->hwnd);
    cmd = (int)TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_RETURNCMD,
                             r.left, r.bottom, 0, app->hwnd, NULL);
    DestroyMenu(m);

    if (cmd >= RB_ID_BM_ITEM_FIRST && cmd < RB_ID_BM_ITEM_FIRST + s_bm[slot].n) {
        rb_do_navigate(app, s_bm[slot].url[cmd - RB_ID_BM_ITEM_FIRST]);
    }
}

static int rb_bm_index_of(App *app, HWND btn)
{
    int i;

    for (i = 0; i < app->bm_slots; i++) {
        if (app->bm_btns[i] == btn) return i;
    }
    return -1;
}

/* Returns 1 when the message was a right-click on a bar button. */
static int rb_bm_context(App *app, HWND child)
{
    int i = rb_bm_index_of(app, child);
    HMENU m;
    RECT r;
    int cmd;

    if (i < 0 || i >= s_bm_n) return 0;

    m = CreatePopupMenu();
    if (m == NULL) return 1;
    AppendMenuW(m, MF_STRING, RB_BM_CTX_OPEN, L"Open in new tab");
    /* A folder button has no single URL to remove, so it offers only the
     * popup a left click already gives it. */
    if (!s_bm[i].folder) {
        AppendMenuW(m, MF_STRING, RB_BM_CTX_REMOVE, L"Remove bookmark");
    }

    GetWindowRect(child, &r);
    SetForegroundWindow(app->hwnd);
    cmd = (int)TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_RETURNCMD,
                             r.left, r.top, 0, app->hwnd, NULL);
    DestroyMenu(m);

    if (cmd == RB_BM_CTX_OPEN && s_bm[i].n > 0) {
        rb_do_add_tab(app, s_bm[i].url[0]);
    } else if (cmd == RB_BM_CTX_REMOVE && !s_bm[i].folder && s_bm[i].n > 0) {
        rb_bookmarks_delete_url(app->bookmarks, s_bm[i].url[0]);
        if (app->path_bookmarks) rb_bookmarks_save(app->bookmarks, app->path_bookmarks);
        rb_update_star(app);
        rb_bmbar_refresh(app);
    }
    return 1;
}

/* Rebuilds the bar for the ACTIVE profile: the preference is per profile, so
 * this runs on a switch and on a text-size change as well as on a bookmark
 * being added or removed. */
void rb_bmbar_refresh(App *app)
{
    int i, n, shown;

    if (app == NULL || app->hwnd == NULL) return;

    for (i = 0; i < app->bm_slots; i++) {
        if (app->bm_btns[i]) DestroyWindow(app->bm_btns[i]);
        app->bm_btns[i] = NULL;
    }

    shown = rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1);
    app->bmbar_h = shown ? rb_scaled(app, RB_BMBAR_H) : 0;

    rb_bm_slots_free();
    n = 0;
    if (shown) {
        rb_bm_slots_build(app);
        n = s_bm_n;
    }

    if (n > app->bm_slots) {
        HWND *nb = (HWND *)calloc((size_t)n, sizeof(HWND));
        if (nb == NULL) { n = app->bm_slots; }
        else {
            free(app->bm_btns);
            app->bm_btns = nb;
            app->bm_slots = n;
        }
    }

    for (i = 0; i < n && i < app->bm_slots; i++) {
        wchar_t *w = rb_utf8_to_wide(s_bm[i].label);
        app->bm_btns[i] = CreateWindowExW(0, L"BUTTON",
            (w != NULL) ? w : L"",
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON | BS_OWNERDRAW,
            0, 0, 100, app->bmbar_h, app->hwnd,
            (HMENU)(INT_PTR)(RB_ID_BM_FIRST + i), app->hinst, NULL);
        free(w);
        if (app->bm_btns[i] != NULL) {
            SendMessageW(app->bm_btns[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
            SetWindowSubclass(app->bm_btns[i], rb_bm_subclass, RB_BM_SUBID,
                              (DWORD_PTR)i);
        }
    }

    rb_layout(app);
}


void rb_do_reload_or_stop(App *app)
{
    if (app->loading) {
        rb_wv_stop(app);
    } else {
        rb_wv_reload(app);
    }
}

/* ------------------------------------------------------------------ */
/* Find in page
 *
 * Android's FindInPageBar: a field, previous, next and close, with the search
 * re-running on every keystroke.  There is no engine-side find in the WebView2
 * surface this edition targets, so the page is asked instead, with the calls
 * Android itself makes — window.find(query, caseSensitive, backwards,
 * wrapAround) with caseSensitive false and wrapAround true, and a count taken
 * by walking the document's text.
 *
 * The strip sits at the BOTTOM of the client area, under the page: this
 * edition puts its toolbar at the top, so the page's near edge is its bottom
 * one.  It starts hidden, and findbar_h is what tells webview.c where the page
 * now ends.
 *
 * The count is a text count, not WebKit's match count, and the two can differ:
 * it walks document.body.innerText, so it counts a phrase the way a reader
 * would see it and not the way the engine's find does.  Android's bar shows no
 * count at all, so this is already more than parity; the GTK edition shows
 * WebKit's own number, which is the more precise of the two.
 */

#define RB_FIND_MAX_JS 8192

/* rb_mk_button is defined with the rest of the chrome construction, below:
 * the find bar's three buttons are the same owner-drawn chrome buttons as
 * everything else, so they are made by the same helper. */
static HWND rb_mk_button(App *app, const wchar_t *text, int id);

/* The query, as the wide text the JS is built from.  Caller frees. */
static wchar_t *rb_find_query(App *app)
{
    wchar_t buf[512];
    int len;

    if (app->find_edit == NULL) return NULL;
    len = GetWindowTextW(app->find_edit, buf, 512);
    if (len <= 0) return NULL;
    return _wcsdup(buf);
}

/* The query as a JS string literal's contents, quotes left off.  A real
 * escaper rather than Android's two replaces: a query containing a newline or
 * a control character would otherwise end the literal and run as code. */
static wchar_t *rb_find_quoted(const wchar_t *q)
{
    char *u8, *esc;
    wchar_t *w;

    u8 = rb_wide_to_utf8(q);
    if (u8 == NULL) return NULL;
    esc = rb_json_escape(u8);
    free(u8);
    if (esc == NULL) return NULL;
    w = rb_utf8_to_wide(esc);
    free(esc);
    return w;
}

/* window.find() moves the page's selection onto the match, which is the only
 * highlight this edition gets — the same feedback, and the same call,
 * Android's own bar has. */
static void rb_find_step(App *app, int forward)
{
    wchar_t js[RB_FIND_MAX_JS];
    wchar_t *q, *esc;

    q = rb_find_query(app);
    if (q == NULL) return;
    esc = rb_find_quoted(q);
    free(q);
    if (esc == NULL) return;

    /* Android's argument list, in Android's order: caseSensitive false,
     * backwards, wrapAround true. */
    if (swprintf(js, RB_FIND_MAX_JS, L"window.find(\"%ls\", false, %ls, true);",
                 esc, forward ? L"false" : L"true") <= 0) {
        free(esc);
        return;
    }
    free(esc);
    rb_wv_run_js(app, js, NULL);
}

/* The count comes from the page: WebView2 has no find API in the surface this
 * edition targets, so the document's own text is walked.  It counts what a
 * reader would see (innerText skips script and hidden nodes) and does not
 * overlap matches, which is what a count is expected to mean. */
static const wchar_t *RB_FIND_COUNT_JS =
    L"(function(){var q=\"%ls\";if(!q)return 0;"
    L"var b=document.body?document.body.innerText:'';"
    L"var L=b.toLowerCase(),Q=q.toLowerCase(),n=0,i=0;"
    L"while((i=L.indexOf(Q,i))!==-1){n++;i+=Q.length;}return n;})()";

static void rb_find_counted(App *app, const wchar_t *json)
{
    wchar_t buf[64];
    unsigned long n;

    if (app->find_label == NULL) return;
    if (json == NULL) {   /* the page went away under us */
        SetWindowTextW(app->find_label, L"");
        return;
    }
    /* A result that lands after the user moved to another tab is that other
     * tab's count, not this bar's.  The GTK edition drops it the same way, by
     * asking which view the reporting controller belongs to. */
    if (app->active_id != app->find_id) return;
    n = wcstoul(json, NULL, 10);
    if (n == 0) {
        wcscpy(buf, L"No matches");
    } else {
        swprintf(buf, 64, (n == 1) ? L"%lu match" : L"%lu matches", n);
    }
    SetWindowTextW(app->find_label, buf);
}

/* The search the field's text asks for.  Runs on every keystroke, which is
 * what Android's bar does with the same text. */
static void rb_find_run(App *app)
{
    wchar_t js[RB_FIND_MAX_JS];
    wchar_t *q, *esc;
    int n;

    if (app->find_label != NULL) SetWindowTextW(app->find_label, L"");
    q = rb_find_query(app);
    if (q == NULL) return;
    esc = rb_find_quoted(q);
    free(q);
    if (esc == NULL) return;

    n = swprintf(js, RB_FIND_MAX_JS, RB_FIND_COUNT_JS, esc);
    free(esc);
    if (n <= 0) return;
    /* Recorded before the call, so the callback can tell whether the answer
     * still belongs to the tab that asked. */
    app->find_id = app->active_id;
    rb_wv_run_js(app, js, rb_find_counted);
}

void rb_findbar_retarget(App *app)
{
    if (app == NULL || app->findbar_h <= 0) return;
    rb_find_run(app);
}

static void rb_findbar_set_visible(App *app, int show)
{
    int cmd = show ? SW_SHOW : SW_HIDE;

    if (app->find_edit)  ShowWindow(app->find_edit, cmd);
    if (app->find_label) ShowWindow(app->find_label, cmd);
    if (app->find_prev)  ShowWindow(app->find_prev, cmd);
    if (app->find_next)  ShowWindow(app->find_next, cmd);
    if (app->find_close) ShowWindow(app->find_close, cmd);
}

void rb_findbar_show(App *app)
{
    if (app == NULL || app->find_edit == NULL) return;
    app->findbar_h = rb_scaled(app, RB_FINDBAR_H);
    rb_findbar_set_visible(app, 1);
    rb_layout(app);
    rb_wv_resize(app);
    InvalidateRect(app->hwnd, NULL, TRUE);
    SetFocus(app->find_edit);
    SendMessageW(app->find_edit, EM_SETSEL, 0, (LPARAM)-1);
    /* Text left over from last time is re-run rather than left as a stale
     * count: the page may have changed while the bar was closed. */
    rb_find_run(app);
}

void rb_findbar_hide(App *app)
{
    if (app == NULL || app->find_edit == NULL || app->findbar_h == 0) return;
    /* Dropping the selection is what takes the highlight off the page: unlike
     * WebKit there is no search to finish, only the selection window.find()
     * left behind. */
    rb_wv_run_js(app, L"window.getSelection().removeAllRanges();", NULL);
    if (app->find_label) SetWindowTextW(app->find_label, L"");
    app->findbar_h = 0;
    rb_findbar_set_visible(app, 0);
    rb_layout(app);
    rb_wv_resize(app);
    InvalidateRect(app->hwnd, NULL, TRUE);
    /* Back to the window: the shortcuts in rb_wndproc are only reached while
     * the chrome has the focus, and the bar is done with the keyboard. */
    SetFocus(app->hwnd);
}

static LRESULT CALLBACK rb_find_subclass(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp,
                                         UINT_PTR uSubclass, DWORD_PTR dwData)
{
    App *app = (App *)dwData;
    (void)uSubclass;

    if (app) {
        if (msg == WM_KEYDOWN && wp == VK_RETURN) {
            rb_find_step(app, (GetKeyState(VK_SHIFT) & 0x8000) ? 0 : 1);
            return 0;
        }
        if (msg == WM_KEYDOWN && wp == VK_ESCAPE) {
            rb_findbar_hide(app);
            return 0;
        }
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

/* Created hidden, like the bookmarks bar's buttons, and shown by
 * rb_findbar_show — a child created with WS_VISIBLE would appear over the page
 * before the first Ctrl+F. */
static void rb_findbar_create(App *app)
{
    app->find_edit = CreateWindowExW(0, L"EDIT", L"",
        WS_CHILD | WS_TABSTOP | ES_LEFT | ES_AUTOHSCROLL,
        0, 0, 200, 26, app->hwnd, (HMENU)(INT_PTR)RB_ID_FIND_EDIT,
        app->hinst, NULL);
    if (app->find_edit) {
        SendMessageW(app->find_edit, WM_SETFONT, (WPARAM)app->fnt_omni, TRUE);
        SetWindowSubclass(app->find_edit, rb_find_subclass, RB_FIND_SUBID,
                          (DWORD_PTR)app);
    }

    app->find_label = CreateWindowExW(0, L"STATIC", L"",
        WS_CHILD | SS_LEFT | SS_CENTERIMAGE | SS_ENDELLIPSIS,
        0, 0, 110, 26, app->hwnd, NULL, app->hinst, NULL);
    if (app->find_label) {
        SendMessageW(app->find_label, WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    }

    app->find_prev = rb_mk_button(app, L"\x2191", RB_ID_FIND_PREV);
    app->find_next = rb_mk_button(app, L"\x2193", RB_ID_FIND_NEXT);
    app->find_close = rb_mk_button(app, L"\x00D7", RB_ID_FIND_CLOSE);
    rb_findbar_set_visible(app, 0);
}

static void rb_findbar_layout(App *app, const RECT *rc)
{
    int h, y, bh, bx, right, field_w;

    if (app->findbar_h <= 0 || app->find_edit == NULL) return;

    h  = app->findbar_h;
    y  = rc->bottom - h + rb_scaled(app, 4);
    bh = h - rb_scaled(app, 8);
    bx = rb_scaled(app, 28);

    /* From the right: close, next, previous.  The field takes what is left,
     * with the count label between them. */
    right = rc->right - rb_scaled(app, 6) - bx;
    if (app->find_close) MoveWindow(app->find_close, right, y, bx, bh, TRUE);
    right -= bx + rb_scaled(app, 2);
    if (app->find_next) MoveWindow(app->find_next, right, y, bx, bh, TRUE);
    right -= bx + rb_scaled(app, 2);
    if (app->find_prev) MoveWindow(app->find_prev, right, y, bx, bh, TRUE);

    right -= rb_scaled(app, 6);
    field_w = right - rb_scaled(app, 6) - rb_scaled(app, 110);
    if (field_w < rb_scaled(app, 80)) field_w = rb_scaled(app, 80);
    MoveWindow(app->find_edit, rb_scaled(app, 6), y, field_w, bh, TRUE);
    if (app->find_label) {
        MoveWindow(app->find_label, rb_scaled(app, 6) + field_w + rb_scaled(app, 6),
                   y, rb_scaled(app, 110), bh, TRUE);
    }
}

/* ------------------------------------------------------------------ */
/* Layout */

int rb_scaled(App *app, int design_px)
{
    const rb_profile *p = rb_active_profile(app);
    int pct = rb_font_scale_percent(p ? p->settings : NULL);
    int v;

    if (pct == RB_FONT_SCALE_DEFAULT) return design_px;
    v = (design_px * pct + 50) / 100;   /* to nearest, so 1px stays 1px */
    return (v > 0) ? v : 1;
}

/* The two fonts the chrome uses, built at the profile's scale. */
static HFONT rb_mk_ui_font(App *app, int design_px)
{
    return CreateFontW(-rb_scaled(app, design_px), 0, 0, 0, FW_NORMAL, 0, 0, 0,
                       DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                       CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY,
                       DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");
}

void rb_apply_font_scale(App *app)
{
    int i;
    HWND nav[7];

    if (app == NULL) return;

    if (app->fnt_ui)   DeleteObject(app->fnt_ui);
    if (app->fnt_omni) DeleteObject(app->fnt_omni);
    app->fnt_ui   = rb_mk_ui_font(app, 15);
    app->fnt_omni = rb_mk_ui_font(app, 16);

    /* Every control has to be told again.  WM_SETFONT is a copy, not a
     * binding: replacing the HFONT leaves the old object selected in whatever
     * already had it, and the deleted handle would still be in use. */
    for (i = 0; i < app->tab_slots; i++) {
        if (app->tab_btns[i]) {
            SendMessageW(app->tab_btns[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
        if (app->tab_closes[i]) {
            SendMessageW(app->tab_closes[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
    }
    if (app->omni) {
        SendMessageW(app->omni, WM_SETFONT, (WPARAM)app->fnt_omni, TRUE);
    }

    nav[0] = app->back;     nav[1] = app->fwd;      nav[2] = app->reload;
    nav[3] = app->home;     nav[4] = app->star;     nav[5] = app->menu_btn;
    nav[6] = app->newtab;
    for (i = 0; i < 7; i++) {
        if (nav[i]) SendMessageW(nav[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    }

    /* The find bar's controls carry the same two fonts (its field the omni
     * one, the rest the UI one), so they are re-handed for the same reason. */
    if (app->find_edit) {
        SendMessageW(app->find_edit, WM_SETFONT, (WPARAM)app->fnt_omni, TRUE);
    }
    if (app->find_label) {
        SendMessageW(app->find_label, WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    }
    nav[0] = app->find_prev; nav[1] = app->find_next; nav[2] = app->find_close;
    for (i = 0; i < 3; i++) {
        if (nav[i]) SendMessageW(nav[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    }

    rb_layout(app);
    if (app->hwnd) InvalidateRect(app->hwnd, NULL, TRUE);
}

void rb_layout(App *app)
{
    RECT rc;
    int w, n, i, x, tw, ow;
    /* The design sizes, each scaled to the profile's font scale.  Taken once
     * here rather than at every use so the grid stays readable as what it is:
     * a 34px strip holding 30px tabs inset by 2, over a 40px toolbar holding
     * 36px buttons inset by 2.  Scaling each number by the same factor keeps
     * those relationships, which is what stops a scaled font from spilling
     * out of a box that did not move with it. */
    const int tab_y   = rb_scaled(app, 2);
    const int tab_h   = rb_scaled(app, 30);
    const int tab_max = rb_scaled(app, 180);
    const int tab_min = rb_scaled(app, 40);
    const int close_w = rb_scaled(app, 18);
    const int close_h = rb_scaled(app, 20);
    const int close_dx = rb_scaled(app, 24);
    const int close_y = rb_scaled(app, 7);
    const int new_w   = rb_scaled(app, 30);
    const int btn_y   = rb_scaled(app, 36);
    const int btn_w   = rb_scaled(app, 36);
    const int omni_y  = rb_scaled(app, 38);
    const int omni_h  = rb_scaled(app, 32);
    const int omni_x  = rb_scaled(app, 166);

    if (!app->hwnd) return;
    GetClientRect(app->hwnd, &rc);
    w = rc.right > 0 ? rc.right : 0;

    n = rb_tabs_count(app->tabs);
    tw = (n > 0) ? (w - rb_scaled(app, 40)) / n : tab_max;
    if (tw > tab_max) tw = tab_max;
    if (tw < tab_min) tw = tab_min;
    x = 0;
    for (i = 0; i < n && i < app->tab_slots; i++) {
        if (app->tab_btns[i]) {
            MoveWindow(app->tab_btns[i], x, tab_y, tw, tab_h, TRUE);
        }
        if (app->tab_closes[i]) {
            MoveWindow(app->tab_closes[i], x + tw - close_dx, close_y,
                       close_w, close_h, TRUE);
        }
        x += tw;
    }
    if (app->newtab) {
        MoveWindow(app->newtab, x + tab_y, tab_y, new_w, tab_h, TRUE);
    }

    if (app->back)   MoveWindow(app->back,   rb_scaled(app, 4),   btn_y, btn_w, btn_w, TRUE);
    if (app->fwd)    MoveWindow(app->fwd,    rb_scaled(app, 44),  btn_y, btn_w, btn_w, TRUE);
    if (app->reload) MoveWindow(app->reload, rb_scaled(app, 84),  btn_y, btn_w, btn_w, TRUE);
    if (app->home)   MoveWindow(app->home,   rb_scaled(app, 124), btn_y, btn_w, btn_w, TRUE);
    if (app->star) {
        MoveWindow(app->star, w - rb_scaled(app, 116), btn_y, btn_w, btn_w, TRUE);
    }
    if (app->menu_btn) {
        MoveWindow(app->menu_btn, w - rb_scaled(app, 72), btn_y, btn_w, btn_w, TRUE);
    }
    ow = w - rb_scaled(app, 286);
    if (ow < rb_scaled(app, 60)) ow = rb_scaled(app, 60);
    if (app->omni) MoveWindow(app->omni, omni_x, omni_y, ow, omni_h, TRUE);

    /* The bookmarks bar, in the strip between the toolbar and the page.  The
     * buttons share the width evenly, clamped at both ends: a bar with two
     * bookmarks does not stretch them across the window, and a bar with
     * thirty does not shrink them below the point where a title is readable —
     * past that they run off the right edge and are clipped, which is what
     * the GTK bar's box does with the same overflow. */
    if (app->bmbar_h > 0 && s_bm_n > 0) {
        const int by = rb_scaled(app, RB_TABSTRIP_H) + rb_scaled(app, RB_TOOLBAR_H)
                       + rb_scaled(app, 2);
        const int bh = app->bmbar_h - rb_scaled(app, 4);
        int bw = (w - rb_scaled(app, 8)) / s_bm_n;
        if (bw > rb_scaled(app, 180)) bw = rb_scaled(app, 180);
        if (bw < rb_scaled(app, 64))  bw = rb_scaled(app, 64);
        x = rb_scaled(app, 4);
        for (i = 0; i < s_bm_n && i < app->bm_slots; i++) {
            if (app->bm_btns[i]) {
                MoveWindow(app->bm_btns[i], x, by, bw, bh, TRUE);
            }
            x += bw + rb_scaled(app, 2);
        }
    }

    /* The find bar's strip, at the bottom of the client area — the page's near
     * edge on this edition, since the toolbar is at the top. */
    rb_findbar_layout(app, &rc);

    rb_wv_resize(app);
}

void rb_tabs_rebuild(App *app)
{
    int n = rb_tabs_count(app->tabs);
    int i;

    for (i = 0; i < app->tab_slots; i++) {
        if (app->tab_btns[i])   DestroyWindow(app->tab_btns[i]);
        if (app->tab_closes[i]) DestroyWindow(app->tab_closes[i]);
        app->tab_btns[i] = NULL;
        app->tab_closes[i] = NULL;
    }

    if (n > app->tab_slots) {
        int cap = app->tab_slots ? app->tab_slots * 2 : 8;
        HWND *nb, *nc;
        while (cap < n) cap *= 2;
        nb = (HWND *)calloc((size_t)cap, sizeof(HWND));
        nc = (HWND *)calloc((size_t)cap, sizeof(HWND));
        if (nb && nc) {
            if (app->tab_slots > 0) {
                memcpy(nb, app->tab_btns,   (size_t)app->tab_slots * sizeof(HWND));
                memcpy(nc, app->tab_closes, (size_t)app->tab_slots * sizeof(HWND));
            }
            free(app->tab_btns);
            free(app->tab_closes);
            app->tab_btns = nb;
            app->tab_closes = nc;
            app->tab_slots = cap;
        } else {
            free(nb);
            free(nc);
            n = app->tab_slots;   /* cannot grow: draw what fits */
        }
    }

    for (i = 0; i < n && i < app->tab_slots; i++) {
        const rb_tab *t = rb_tabs_at(app->tabs, i);
        const wchar_t *txt = L"New Tab";
        wchar_t *tmp = NULL;
        if (t && t->title && t->title[0]) {
            tmp = rb_utf8_to_wide(t->title);
            if (tmp) txt = tmp;
        }
        app->tab_btns[i] = CreateWindowExW(0, L"BUTTON", txt,
            WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON | BS_OWNERDRAW,
            0, 2, 180, 30, app->hwnd,
            (HMENU)(INT_PTR)(RB_ID_TAB_FIRST + 2 * i), app->hinst, NULL);
        if (app->tab_btns[i]) {
            SendMessageW(app->tab_btns[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
        free(tmp);
        app->tab_closes[i] = CreateWindowExW(0, L"BUTTON", L"\x00D7",
            WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON | BS_OWNERDRAW,
            0, 7, 18, 20, app->hwnd,
            (HMENU)(INT_PTR)(RB_ID_TAB_FIRST + 2 * i + 1), app->hinst, NULL);
        if (app->tab_closes[i]) {
            SendMessageW(app->tab_closes[i], WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
        }
    }
    rb_layout(app);
}

/* ------------------------------------------------------------------ */
/* Owner-drawn buttons */

static LRESULT rb_draw_button(App *app, const DRAWITEMSTRUCT *dis)
{
    int id = (int)dis->CtlID;
    int idx = id - RB_ID_TAB_FIRST;
    BOOL is_tab = (idx >= 0 && (idx & 1) == 0);
    BOOL is_close = (idx >= 0 && (idx & 1) == 1);
    BOOL active = FALSE;
    HBRUSH bg = app->br_toolbar;
    wchar_t text[512];
    UINT fmt = DT_SINGLELINE | DT_VCENTER | DT_CENTER | DT_NOPREFIX;
    RECT tr = dis->rcItem;
    HFONT old;

    text[0] = 0;
    GetWindowTextW(dis->hwndItem, text, 512);

    if (is_tab || is_close) {
        int i = idx / 2;
        int n = rb_tabs_count(app->tabs);
        const rb_tab *t = (i >= 0 && i < n) ? rb_tabs_at(app->tabs, i) : NULL;
        active = (t && t->id == app->active_id);
        bg = active ? app->br_toolbar : app->br_tab_idle;
    }

    FillRect(dis->hDC, &dis->rcItem, bg);
    if (is_tab && active) {
        RECT ul = dis->rcItem;   /* 2px purple underline for the active tab */
        ul.top = ul.bottom - 2;
        FillRect(dis->hDC, &ul, app->br_accent);
    }
    if (dis->itemState & ODS_SELECTED) {
        FrameRect(dis->hDC, &dis->rcItem, app->br_accent);
    }

    SetBkMode(dis->hDC, TRANSPARENT);
    /* A disabled button must not read as enabled.  rb_update_nav greys
     * back/forward out with EnableWindow, and an owner-drawn button gets no
     * help from the theme — without this it paints exactly like a live one,
     * so the only sign the control is dead is that clicking does nothing. */
    SetTextColor(dis->hDC, (dis->itemState & ODS_DISABLED)
                               ? rb_col(app->pal.text_secondary)
                               : rb_col(app->pal.text_primary));
    old = (HFONT)SelectObject(dis->hDC, app->fnt_ui);
    if (is_tab) {
        tr.left += 8;
        tr.right -= 26;   /* keep clear of the close button */
        fmt = DT_SINGLELINE | DT_VCENTER | DT_LEFT | DT_END_ELLIPSIS | DT_NOPREFIX;
    }
    DrawTextW(dis->hDC, text, -1, &tr, fmt);
    SelectObject(dis->hDC, old);
    return TRUE;
}

/* ------------------------------------------------------------------ */
/* Menu (popup) */

static void rb_show_about(App *app)
{
    wchar_t *v = rb_utf8_to_wide(RB_VERSION);
    wchar_t msg[192];
    if (v) {
        if (swprintf(msg, 192, L"Room Browser %ls \x2014 desktop edition by Maragung", v) <= 0) {
            swprintf(msg, 192, L"Room Browser \x2014 desktop edition by Maragung");
        }
        free(v);
    } else {
        swprintf(msg, 192, L"Room Browser \x2014 desktop edition by Maragung");
    }
    MessageBoxW(app->hwnd, msg, L"About Room Browser", MB_OK | MB_ICONINFORMATION);
}

static void rb_menu_show(App *app)
{
    HMENU m = CreatePopupMenu();
    HMENU hist = CreatePopupMenu();
    HMENU prof = CreatePopupMenu();
    int i, n = 0;
    const rb_hist_entry *rec;
    RECT r;

    AppendMenuW(m, MF_STRING, IDM_NEW_TAB, L"New Tab");
    AppendMenuW(m, MF_STRING, IDM_BOOKMARK, L"Bookmark this page");

    /* Snapshot the recent history URLs; WM_COMMAND for these ids arrives
       after TrackPopupMenu returns, so the snapshot lives until the next
       time the menu is opened. */
    for (i = 0; i < app->hist_menu_n; i++) {
        free(app->hist_menu[i]);
        app->hist_menu[i] = NULL;
    }
    app->hist_menu_n = 0;
    rec = rb_history_recent(app->history, 10, &n);
    for (i = 0; i < n && i < RB_HIST_MENU_MAX; i++) {
        const char *url = rec[i].url ? rec[i].url : "";
        const char *title = (rec[i].title && rec[i].title[0]) ? rec[i].title : url;
        wchar_t *wl = rb_utf8_to_wide(title);
        app->hist_menu[i] = rb_strdup(url);
        app->hist_menu_n++;
        if (wl) {
            AppendMenuW(hist, MF_STRING, IDM_HIST_FIRST + i, wl);
            free(wl);
        }
    }
    if (app->hist_menu_n == 0) {
        AppendMenuW(hist, MF_STRING | MF_GRAYED, 0, L"(empty)");
    }
    AppendMenuW(m, MF_POPUP, (UINT_PTR)hist, L"Recent history");

    /* The profile list, ticked on the active one.  Like the history items
       these carry a snapshot rather than a live pointer, because the registry
       row can be relocated by a mutation between the menu opening and the
       click landing. */
    for (i = 0; i < app->prof_menu_n; i++) {
        free(app->prof_menu[i]);
        app->prof_menu[i] = NULL;
    }
    app->prof_menu_n = 0;
    for (i = 0; i < rb_profile_count(app->profiles) && i < RB_PROF_MENU_MAX; i++) {
        const rb_profile *p = rb_profile_at(app->profiles, i);
        char label[128];
        wchar_t *wl;
        UINT flags = MF_STRING;
        if (p == NULL) continue;
        snprintf(label, sizeof label, "%s%s",
                 (p->name && p->name[0]) ? p->name : "Profile",
                 (p->is_locked) ? "  (locked)" : "");
        wl = rb_utf8_to_wide(label);
        if (wl == NULL) continue;
        if (app->active_profile_id != NULL &&
            strcmp(app->active_profile_id, p->id) == 0) {
            flags |= MF_CHECKED;
        }
        app->prof_menu[i] = rb_strdup(p->id);
        app->prof_menu_n++;
        AppendMenuW(prof, flags, IDM_PROF_FIRST + i, wl);
        free(wl);
    }
    AppendMenuW(prof, MF_SEPARATOR, 0, NULL);
    AppendMenuW(prof, MF_STRING, IDM_PROF_ADD, L"Add profile");
    AppendMenuW(m, MF_POPUP, (UINT_PTR)prof, L"Profile");

    /* The privacy tools, under their own separator.  "Shield" is the
     * profile-settings window by another name - the menu offers the same
     * room under the word people look for - so it reuses the IDM_PREFS
     * handler rather than owning a window. */
    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING, IDM_NOTES, L"Notes");
    AppendMenuW(m, MF_STRING, IDM_TOTP, L"2FA Management");
    AppendMenuW(m, MF_STRING, IDM_SHIELD, L"Shield");

    AppendMenuW(m, MF_SEPARATOR, 0, NULL);
    AppendMenuW(m, MF_STRING | (rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1)
                                     ? MF_CHECKED : 0),
                IDM_BMBAR, L"Show bookmarks bar");
    AppendMenuW(m, MF_STRING, IDM_DOWNLOADS, L"Downloads");
    AppendMenuW(m, MF_STRING, IDM_FIND, L"Find in page");
    AppendMenuW(m, MF_STRING, IDM_TRANSLATE, L"Translate this page");
    AppendMenuW(m, MF_STRING, IDM_PREFS, L"Profile settings");
    AppendMenuW(m, MF_STRING, IDM_ABOUT, L"About");

    GetWindowRect(app->menu_btn, &r);
    SetForegroundWindow(app->hwnd);
    TrackPopupMenu(m, TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, r.left, r.top, 0, app->hwnd, NULL);
    DestroyMenu(m);
}

static void rb_on_command(App *app, int id, int notify)
{
    /* The find field reports every keystroke as EN_CHANGE, which is not a
     * click and would be dropped by the guard below. */
    if (id == RB_ID_FIND_EDIT) {
        if (notify == EN_CHANGE) rb_find_run(app);
        return;
    }
    if (notify != BN_CLICKED && notify != 0) return;

    if (id == IDC_BACK) {
        rb_wv_goback(app);
    } else if (id == IDC_FWD) {
        rb_wv_gofwd(app);
    } else if (id == IDC_RELOAD) {
        rb_do_reload_or_stop(app);
    } else if (id == IDC_HOME) {
        rb_do_navigate(app, app->home_url ? app->home_url : "https://duckduckgo.com");
    } else if (id == IDC_STAR) {
        rb_do_toggle_bookmark(app);
    } else if (id == IDC_MENU) {
        rb_menu_show(app);
    } else if (id == IDC_NEWBTN) {
        rb_do_new_tab(app);
    } else if (id == IDM_NEW_TAB) {
        rb_do_new_tab(app);
    } else if (id == IDM_BOOKMARK) {
        rb_do_toggle_bookmark(app);
    } else if (id == IDM_ABOUT) {
        rb_show_about(app);
    } else if (id == IDM_BMBAR) {
        rb_pref_set_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL,
                        rb_pref_int(app, RB_PREF_BOOKMARKS_BAR_LOCAL, 1) ? 0 : 1);
        rb_bmbar_refresh(app);
    } else if (id == IDM_DOWNLOADS) {
        rb_show_downloads(app);
    } else if (id == IDM_NOTES) {
        rb_show_notes(app);
    } else if (id == IDM_TOTP) {
        rb_show_totp(app);
    } else if (id == IDM_SHIELD) {
        rb_show_prefs(app);
    } else if (id == IDM_TRANSLATE) {
        rb_do_translate(app);
    } else if (id == IDM_FIND) {
        rb_findbar_show(app);
    } else if (id == RB_ID_FIND_PREV) {
        rb_find_step(app, 0);
    } else if (id == RB_ID_FIND_NEXT) {
        rb_find_step(app, 1);
    } else if (id == RB_ID_FIND_CLOSE) {
        rb_findbar_hide(app);
    } else if (id == IDM_PREFS) {
        rb_show_prefs(app);
    } else if (id == IDM_PROF_ADD) {
        rb_profiles_add(app, "Profile");
    } else if (id >= IDM_PROF_FIRST && id < IDM_PROF_FIRST + app->prof_menu_n) {
        const char *pid = app->prof_menu[id - IDM_PROF_FIRST];
        if (pid) rb_do_switch_profile(app, pid);
    } else if (id >= IDM_HIST_FIRST && id < IDM_HIST_FIRST + app->hist_menu_n) {
        const char *url = app->hist_menu[id - IDM_HIST_FIRST];
        if (url) rb_do_navigate(app, url);
    } else if (id >= RB_ID_BM_FIRST && id < RB_ID_BM_FIRST + s_bm_n) {
        int i = id - RB_ID_BM_FIRST;
        if (s_bm[i].folder) {
            rb_bm_folder_popup(app, i);
        } else if (s_bm[i].n > 0) {
            rb_do_navigate(app, s_bm[i].url[0]);
        }
    } else if (id >= RB_ID_TAB_FIRST) {
        int k = id - RB_ID_TAB_FIRST;
        int i = k / 2;
        int n = rb_tabs_count(app->tabs);
        if (i >= 0 && i < n) {
            const rb_tab *t = rb_tabs_at(app->tabs, i);
            if (t) {
                if ((k & 1) == 0) rb_do_activate(app, t->id);
                else rb_do_close_tab(app, t->id);
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Omnibox subclass (Enter navigates, Escape restores the current URL) */

static LRESULT CALLBACK rb_omni_subclass(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp,
                                         UINT_PTR uSubclass, DWORD_PTR dwData)
{
    App *app = (App *)dwData;
    (void)uSubclass;
    if (app) {
        if (msg == WM_KEYDOWN && wp == VK_RETURN) {
            wchar_t buf[2048];
            int len = GetWindowTextW(hwnd, buf, 2048);
            if (len > 0) {
                char *u8 = rb_wide_to_utf8(buf);
                if (u8) {
                    char *url = rb_url_decide(u8);
                    free(u8);
                    if (url) {
                        rb_do_navigate(app, url);
                        free(url);
                    }
                }
            }
            return 0;
        }
        if (msg == WM_KEYDOWN && wp == VK_ESCAPE) {
            rb_tab *t = app->active_id ? rb_tabs_get(app->tabs, app->active_id) : NULL;
            rb_update_omni(app, t && t->url ? t->url : "");
            return 0;
        }
        if (msg == WM_SETFOCUS || msg == WM_KILLFOCUS) {
            RECT r;
            GetWindowRect(hwnd, &r);
            InflateRect(&r, 2, 2);
            MapWindowPoints(NULL, app->hwnd, (POINT *)&r, 2);
            InvalidateRect(app->hwnd, &r, FALSE);
        }
    }
    return DefSubclassProc(hwnd, msg, wp, lp);
}

/* ------------------------------------------------------------------ */
/* Dark title bar via runtime-loaded dwmapi (silent fallback). */

void rb_apply_dark_titlebar(HWND hwnd)
{
    typedef HRESULT (WINAPI *PFN_DwmSetWindowAttribute)(HWND, DWORD, LPCVOID, DWORD);
    HMODULE mod = LoadLibraryW(L"dwmapi.dll");
    PFN_DwmSetWindowAttribute fn;
    if (!mod) return;
    fn = (PFN_DwmSetWindowAttribute)(void *)GetProcAddress(mod, "DwmSetWindowAttribute");
    if (fn) {
        BOOL dark = TRUE;
        fn(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof(dark));
    }
    FreeLibrary(mod);
}

/* ------------------------------------------------------------------ */
/* Window procedure */

static LRESULT CALLBACK rb_wndproc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    App *app = (App *)(LONG_PTR)GetWindowLongPtrW(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_CREATE: {
        CREATESTRUCTW *cs = (CREATESTRUCTW *)lp;
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)cs->lpCreateParams);
        return 0;
    }
    case WM_SIZE:
        if (app) rb_layout(app);
        return 0;
    case WM_ERASEBKGND:
        return 1;   /* painted in WM_PAINT to avoid flicker */
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        if (app && dc) {
            FillRect(dc, &ps.rcPaint, app->br_chrome);
            if (app->findbar_h > 0) {
                /* The find strip sits below the page, so it is painted after
                 * the chrome fill and in the toolbar shade, the way the GTK
                 * edition's .rb-findbar is styled. */
                RECT fr;
                GetClientRect(hwnd, &fr);
                fr.top = fr.bottom - app->findbar_h;
                FillRect(dc, &fr, app->br_toolbar);
            }
            if (app->omni && GetFocus() == app->omni) {
                RECT r;
                POINT tl, br;
                GetWindowRect(app->omni, &r);
                tl.x = r.left;  tl.y = r.top;
                br.x = r.right; br.y = r.bottom;
                ScreenToClient(hwnd, &tl);
                ScreenToClient(hwnd, &br);
                SetRect(&r, tl.x, tl.y, br.x, br.y);
                InflateRect(&r, 1, 1);
                FrameRect(dc, &r, app->br_accent);
            }
            rb_progress_paint(app, dc);
        }
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_TIMER:
        if (app && wp == RB_TIMER_PROGRESS) rb_progress_tick(app);
        return 0;
    case WM_CTLCOLOREDIT:
        if (app && (HWND)lp == app->omni) {
            SetTextColor((HDC)wp, rb_col(app->pal.text_primary));
            SetBkColor((HDC)wp, rb_col(app->pal.address_bar));
            return (LRESULT)app->br_omni;
        }
        if (app && (HWND)lp == app->find_edit) {
            SetTextColor((HDC)wp, rb_col(app->pal.text_primary));
            SetBkColor((HDC)wp, rb_col(app->pal.address_bar));
            return (LRESULT)app->br_omni;
        }
        break;
    case WM_CTLCOLORSTATIC:
        /* The find bar's match count.  Transparent, so the strip's own fill
         * shows through behind the text. */
        if (app && (HWND)lp == app->find_label) {
            SetTextColor((HDC)wp, rb_col(app->pal.text_secondary));
            SetBkMode((HDC)wp, TRANSPARENT);
            return (LRESULT)app->br_toolbar;
        }
        break;
    case WM_CTLCOLORBTN:
        if (app) return (LRESULT)app->br_chrome;
        break;
    case WM_COMMAND:
        if (app) rb_on_command(app, (int)LOWORD(wp), (int)HIWORD(wp));
        return 0;
    case WM_DRAWITEM:
        if (app) return rb_draw_button(app, (const DRAWITEMSTRUCT *)lp);
        break;
    case WM_CONTEXTMENU:
        /* A right click on a standard control reaches the parent as
         * WM_CONTEXTMENU with the control in wParam.  Only the bookmarks bar
         * answers it; everything else keeps the default handling. */
        if (app && rb_bm_context(app, (HWND)wp)) return 0;
        break;
    case WM_KEYDOWN:
        if (app) {
            BOOL ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
            BOOL alt = (GetKeyState(VK_MENU) & 0x8000) != 0;
            if (wp == VK_F5 || (wp == 'R' && ctrl)) {
                rb_do_reload_or_stop(app);
                return 0;
            }
            if (wp == 'T' && ctrl) {
                rb_do_new_tab(app);
                return 0;
            }
            if (wp == 'W' && ctrl && app->active_id) {
                rb_do_close_tab(app, app->active_id);
                return 0;
            }
            if (wp == 'L' && ctrl && app->omni) {
                SetFocus(app->omni);
                SendMessageW(app->omni, EM_SETSEL, 0, (LPARAM)-1);
                return 0;
            }
            /* The GTK edition binds these as GApplication accelerators; here
             * they are read straight off the message.  F3 steps forward and
             * Shift+F3 backward, the same pair. */
            if (wp == 'F' && ctrl) {
                rb_findbar_show(app);
                return 0;
            }
            if ((wp == 'G' && ctrl) || wp == VK_F3) {
                BOOL shift = (GetKeyState(VK_SHIFT) & 0x8000) != 0;
                rb_find_step(app, shift ? 0 : 1);
                return 0;
            }
            if (wp == VK_LEFT && alt) {
                rb_wv_goback(app);
                return 0;
            }
            if (wp == VK_RIGHT && alt) {
                rb_wv_gofwd(app);
                return 0;
            }
        }
        break;
    case WM_DESTROY:
        if (app) {
            rb_data_shutdown(app);
            rb_wv_shutdown(app);
            if (app->omni) RemoveWindowSubclass(app->omni, rb_omni_subclass, RB_OMNI_SUBID);
        }
        PostQuitMessage(0);
        return 0;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

/* ------------------------------------------------------------------ */
/* Chrome construction */

static HWND rb_mk_button(App *app, const wchar_t *text, int id)
{
    HWND h = CreateWindowExW(0, L"BUTTON", text,
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON | BS_OWNERDRAW,
        0, 0, 36, 36, app->hwnd, (HMENU)(INT_PTR)id, app->hinst, NULL);
    if (h) SendMessageW(h, WM_SETFONT, (WPARAM)app->fnt_ui, TRUE);
    return h;
}

int rb_chrome_create(App *app)
{
    WNDCLASSEXW wc;
    ATOM cls;

    memset(&wc, 0, sizeof wc);
    wc.cbSize = sizeof wc;
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = rb_wndproc;
    wc.hInstance = app->hinst;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)IDC_ARROW);
    wc.hIcon = (HICON)LoadImageW(app->hinst, MAKEINTRESOURCEW(IDI_ICON1),
                                 IMAGE_ICON, 0, 0, LR_DEFAULTSIZE);
    wc.hIconSm = wc.hIcon;
    wc.hbrBackground = NULL;
    wc.lpszClassName = L"RoomBrowserWnd";
    cls = RegisterClassExW(&wc);
    if (!cls) return -1;

    app->br_chrome  = NULL;   /* built by rb_theme_apply() below */
    app->br_toolbar = NULL;
    app->br_tab_idle = NULL;
    app->br_omni    = NULL;
    app->br_accent  = NULL;
    /* Built at the profile's font scale.  rb_mk_ui_font() is the same builder
     * rb_apply_font_scale() uses, so a scale set before start-up and one
     * chosen later in the settings window produce identical fonts. */
    app->fnt_ui   = rb_mk_ui_font(app, 15);
    app->fnt_omni = rb_mk_ui_font(app, 16);

    app->hwnd = CreateWindowExW(0, L"RoomBrowserWnd", L"Room Browser",
                                WS_OVERLAPPEDWINDOW,
                                CW_USEDEFAULT, CW_USEDEFAULT,
                                RB_WINDOW_W, RB_WINDOW_H,
                                NULL, NULL, app->hinst, app);
    if (!app->hwnd) return -1;

    rb_apply_dark_titlebar(app->hwnd);
    /* Builds the palette brushes from the active profile's theme.  This is
     * the only place they are created, so a theme change goes through
     * rb_theme_apply() too rather than duplicating the mapping. */
    rb_theme_apply(app);

    app->back   = rb_mk_button(app, L"\x2190", IDC_BACK);
    app->fwd    = rb_mk_button(app, L"\x2192", IDC_FWD);
    app->reload = rb_mk_button(app, L"\x21BB", IDC_RELOAD);
    app->home   = rb_mk_button(app, L"\x2302", IDC_HOME);
    app->star   = rb_mk_button(app, L"\x2606", IDC_STAR);
    app->menu_btn = rb_mk_button(app, L"\x2630", IDC_MENU);
    app->newtab = rb_mk_button(app, L"\x002B", IDC_NEWBTN);

    app->omni = CreateWindowExW(0, L"EDIT", L"",
        WS_CHILD | WS_VISIBLE | WS_TABSTOP | ES_LEFT | ES_AUTOHSCROLL,
        166, 38, 600, 32, app->hwnd, (HMENU)(INT_PTR)IDC_OMNI, app->hinst, NULL);
    if (app->omni) {
        SendMessageW(app->omni, WM_SETFONT, (WPARAM)app->fnt_omni, TRUE);
        SetWindowSubclass(app->omni, rb_omni_subclass, RB_OMNI_SUBID, (DWORD_PTR)app);
    }

    rb_tabs_rebuild(app);
    rb_bmbar_refresh(app);
    rb_findbar_create(app);
    rb_update_all(app);
    return 0;
}
