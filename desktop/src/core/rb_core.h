/*
 * rb_core.h — the whole portable core in one include.
 *
 * The platform layers (GTK, Win32) used to re-declare the core ABI in their
 * own chrome.h.  That is a second copy of every signature, and it silently
 * went stale the moment a core function gained a parameter or a module was
 * added — the compiler cannot catch a mismatch between two independent
 * declarations that nothing cross-checks.  Including this header instead
 * means the platform layers compile against the real declarations, and any
 * drift is an error at build time rather than a crash at run time.
 *
 * Nothing here depends on GTK, WebKit, or Win32: the core is plain C11 plus
 * the C standard library (rb_paths.c additionally uses the platform's basic
 * filesystem API).
 */

#ifndef RB_CORE_H
#define RB_CORE_H

#include "rb_bookmarks.h"
#include "rb_devices.h"
#include "rb_dns.h"
#include "rb_downloads.h"
#include "rb_filterlist.h"
#include "rb_filters.h"
#include "rb_history.h"
#include "rb_https.h"
#include "rb_ipconflict.h"
#include "rb_json.h"
#include "rb_notes.h"
#include "rb_paths.h"
#include "rb_prefs.h"
#include "rb_profile.h"
#include "rb_search.h"
#include "rb_settings.h"
#include "rb_str.h"
#include "rb_switch.h"
#include "rb_tabs.h"
#include "rb_theme.h"
#include "rb_totp.h"
#include "rb_ua.h"
#include "rb_url.h"

#endif /* RB_CORE_H */
