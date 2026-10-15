/*
 * test_core.c — Room Browser core unit tests (plain C, no framework).
 *
 * Build (gcc):  gcc -std=c11 -Wall -Wextra -Wpedantic -Isrc \
 *                   -o /tmp/rb_tests tests/test_core.c src/core (all .c files)
 * Build (CMake): target rb_tests (links rb_core).
 *
 * Every module is covered, including save/load roundtrips through a temp
 * file ("rb-test-tmp.txt" in the cwd, removed afterwards).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "core/rb_str.h"
#include "core/rb_json.h"
#include "core/rb_url.h"
#include "core/rb_search.h"
#include "core/rb_tabs.h"
#include "core/rb_history.h"
#include "core/rb_bookmarks.h"
#include "core/rb_settings.h"
#include "core/rb_prefs.h"
#include "core/rb_profile.h"
#include "core/rb_paths.h"
#include "core/rb_ua.h"
#include "core/rb_theme.h"
#include "core/rb_filters.h"
#include "core/rb_filterlist.h"
#include "core/rb_https.h"
#include "core/rb_downloads.h"
#include "core/rb_dns.h"
#include "core/rb_ipconflict.h"
#include "core/rb_switch.h"
#include "core/rb_devices.h"

/* The notes and 2FA suites are self-contained fragments: one translation
 * unit, one failure counter each, temp files they clean up themselves. */
#include "frag_notes.c"
#include "frag_totp.c"

#define TMP "rb-test-tmp.txt"
#define TMP_DIR "rb-test-dir"

static int g_checks = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        g_checks++;                                                          \
        if (!(cond)) {                                                       \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
            exit(1);                                                         \
        }                                                                    \
    } while (0)

#define STREQ(a, b) (strcmp((a), (b)) == 0)

/* A catalogue row's identity is the whole fingerprint, not its User-Agent:
 * the generator deliberately lets machines that run the same OS and the same
 * Chrome release share a UA, and separates them on GPU, cores and memory. */
static int same_device_identity(const rb_device *a, const rb_device *b)
{
    return STREQ(a->ua, b->ua) && STREQ(a->arch, b->arch) &&
           STREQ(a->platform, b->platform) &&
           STREQ(a->ua_platform, b->ua_platform) &&
           STREQ(a->platform_version, b->platform_version) &&
           STREQ(a->gpu_vendor, b->gpu_vendor) &&
           STREQ(a->gpu_renderer, b->gpu_renderer) &&
           a->cores == b->cores && a->memory == b->memory;
}

/* --------------------------------- rb_str -------------------------------- */

static void test_rb_str(void)
{
    rb_str s;
    rb_str t;
    size_t i;

    rb_str_init(&s);
    CHECK(rb_str_c(&s) != NULL);
    CHECK(rb_str_c(&s)[0] == '\0');
    CHECK(s.len == 0);

    rb_str_append(&s, "Hello");
    rb_str_append(&s, ", ");
    rb_str_append(&s, "World");
    CHECK(STREQ(rb_str_c(&s), "Hello, World"));
    CHECK(s.len == 12);
    CHECK(s.cap >= s.len);
    CHECK(s.data[12] == '\0');

    rb_str_append(&s, NULL); /* documented no-op */
    CHECK(s.len == 12);

    rb_str_appendf(&s, " %d-%s!", 42, "go");
    CHECK(STREQ(rb_str_c(&s), "Hello, World 42-go!"));
    CHECK(s.len == 19);

    rb_str_clear(&s);
    CHECK(s.len == 0);
    CHECK(rb_str_c(&s)[0] == '\0');

    /* growth: 200 appends of 10 bytes -> 2000 bytes */
    for (i = 0; i < 200; i++) {
        rb_str_append(&s, "0123456789");
    }
    CHECK(s.len == 2000);
    CHECK(s.data != NULL);
    CHECK(s.data[2000] == '\0');
    CHECK(STREQ(rb_str_c(&s) + 1990, "0123456789"));

    rb_str_free(&s);
    CHECK(s.data == NULL);
    CHECK(s.len == 0);
    CHECK(s.cap == 0);
    CHECK(STREQ(rb_str_c(&s), ""));
    rb_str_free(&s); /* double free must stay safe */

    rb_str_init(&t);
    rb_str_appendf(&t, "%s=%d", "x", 5); /* appendf on an empty buffer */
    CHECK(STREQ(rb_str_c(&t), "x=5"));
    rb_str_free(&t);
}

/* --------------------------------- rb_url -------------------------------- */

static void test_rb_url(void)
{
    char *u;

    /* --- rb_url_is_probably_url --- */
    CHECK(rb_url_is_probably_url("example.com") == 1);
    CHECK(rb_url_is_probably_url("sub.example.co.uk/some/path") == 1);
    CHECK(rb_url_is_probably_url("https://example.com") == 1);
    CHECK(rb_url_is_probably_url("http://example.com/path?q=1") == 1);
    CHECK(rb_url_is_probably_url("about:blank") == 1);
    CHECK(rb_url_is_probably_url("file:///tmp/x.html") == 1);
    CHECK(rb_url_is_probably_url("localhost") == 1);
    CHECK(rb_url_is_probably_url("localhost:8080") == 1);
    CHECK(rb_url_is_probably_url("192.168.1.1") == 1);
    CHECK(rb_url_is_probably_url("192.168.1.1:8080") == 1);
    CHECK(rb_url_is_probably_url("::1") == 1);
    CHECK(rb_url_is_probably_url("example.com:8080/path") == 1);
    CHECK(rb_url_is_probably_url("  example.com  ") == 1); /* trimmed */
    CHECK(rb_url_is_probably_url("hello world") == 0);
    CHECK(rb_url_is_probably_url("what is a browser") == 0);
    CHECK(rb_url_is_probably_url("example") == 0); /* no dot, no scheme */
    CHECK(rb_url_is_probably_url("café") == 0);    /* non-ASCII -> search */
    CHECK(rb_url_is_probably_url("1.5") == 0);     /* version-like */
    CHECK(rb_url_is_probably_url("12:30") == 0);   /* time-like */
    CHECK(rb_url_is_probably_url("") == 0);
    CHECK(rb_url_is_probably_url("   ") == 0);
    CHECK(rb_url_is_probably_url(NULL) == 0);

    /* --- rb_url_normalize --- */
    u = rb_url_normalize("example.com");
    CHECK(STREQ(u, "https://example.com/"));
    free(u);
    u = rb_url_normalize("http://example.com");
    CHECK(STREQ(u, "http://example.com/")); /* existing scheme kept */
    free(u);
    u = rb_url_normalize("https://example.com");
    CHECK(STREQ(u, "https://example.com/"));
    free(u);
    u = rb_url_normalize("example.com/path");
    CHECK(STREQ(u, "https://example.com/path"));
    free(u);
    u = rb_url_normalize("example.com?x=1");
    CHECK(STREQ(u, "https://example.com/?x=1"));
    free(u);
    u = rb_url_normalize("example.com#frag");
    CHECK(STREQ(u, "https://example.com/#frag"));
    free(u);
    u = rb_url_normalize("  https://Example.COM  ");
    CHECK(STREQ(u, "https://Example.COM/"));
    free(u);
    u = rb_url_normalize("about:blank");
    CHECK(STREQ(u, "about:blank"));
    free(u);
    u = rb_url_normalize("file:///tmp/x.html");
    CHECK(STREQ(u, "file:///tmp/x.html"));
    free(u);
    u = rb_url_normalize("HTTPS://example.com"); /* scheme is case-insensitive */
    CHECK(STREQ(u, "HTTPS://example.com/"));
    free(u);
    u = rb_url_normalize("");
    CHECK(STREQ(u, ""));
    free(u);
    u = rb_url_normalize(NULL);
    CHECK(STREQ(u, ""));
    free(u);

    /* --- rb_url_build_search: percent-encoding --- */
    u = rb_url_build_search("cats & dogs"); /* space and ampersand */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=cats%20%26%20dogs"));
    free(u);
    u = rb_url_build_search("café"); /* non-ASCII UTF-8 bytes */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=caf%C3%A9"));
    free(u);
    u = rb_url_build_search("100% done"); /* percent itself */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=100%25%20done"));
    free(u);
    u = rb_url_build_search("a.b-c_d~e f"); /* unreserved stay literal */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=a.b-c_d~e%20f"));
    free(u);
    u = rb_url_build_search("");
    CHECK(STREQ(u, "https://duckduckgo.com/?q="));
    free(u);
    u = rb_url_build_search(NULL);
    CHECK(STREQ(u, "https://duckduckgo.com/?q="));
    free(u);
    u = rb_url_build_search("  padded  "); /* surrounding whitespace trimmed */
    CHECK(STREQ(u, "https://duckduckgo.com/?q=padded"));
    free(u);

    /* --- rb_url_decide: exactly UrlIntelligence.classify --- */
    u = rb_url_decide("example.com");
    CHECK(STREQ(u, "https://example.com")); /* no trailing slash added */
    free(u);
    u = rb_url_decide("http://example.com");
    CHECK(STREQ(u, "http://example.com")); /* typed http is loaded as typed */
    free(u);
    u = rb_url_decide("how to boil water");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=how%20to%20boil%20water"));
    free(u);
    u = rb_url_decide("café tools");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=caf%C3%A9%20tools"));
    free(u);

    /* --- rb_url_classify: UrlIntelligence.classify, branch by branch --- */
    {
        int kind = -1;
        int up = -1;

        /* Blank input is a search for nothing at all. */
        u = rb_url_classify("   ", NULL, &kind, &up);
        CHECK(STREQ(u, ""));
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(up == 0);
        free(u);
        u = rb_url_classify(NULL, NULL, &kind, &up);
        CHECK(STREQ(u, ""));
        CHECK(kind == RB_URL_INPUT_SEARCH);
        free(u);

        /* file:// loads as-is, upgraded flag clear. */
        u = rb_url_classify("file:///tmp/x.html", NULL, &kind, &up);
        CHECK(STREQ(u, "file:///tmp/x.html"));
        CHECK(kind == RB_URL_INPUT_WEB);
        CHECK(up == 0);
        free(u);
        u = rb_url_classify("FILE:///tmp/x.html", NULL, &kind, &up);
        CHECK(STREQ(u, "FILE:///tmp/x.html"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);

        /* Whitespace anywhere means a query, whatever else it looks like. */
        u = rb_url_classify("example.com is down", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(STREQ(u, "https://duckduckgo.com/?q=example.com%20is%20down"));
        free(u);
        u = rb_url_classify("http://example.com/a b", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        free(u);

        /* Address literals and localhost go to http, never https: a TLS
         * attempt against a router or a dev server is the common case that
         * must not break. */
        u = rb_url_classify("192.168.1.1", NULL, &kind, &up);
        CHECK(STREQ(u, "http://192.168.1.1"));
        CHECK(kind == RB_URL_INPUT_WEB);
        CHECK(up == 0);
        free(u);
        u = rb_url_classify("192.168.1.1:8080", NULL, &kind, &up);
        CHECK(STREQ(u, "http://192.168.1.1:8080")); /* the port is kept */
        free(u);
        u = rb_url_classify("255.255.255.255", NULL, &kind, &up);
        CHECK(STREQ(u, "http://255.255.255.255"));
        free(u);
        /* An octet over 255 is not an address: it is a domain-like token,
         * which is what the Android regex decides too. */
        u = rb_url_classify("999.1.1.1", NULL, &kind, &up);
        CHECK(STREQ(u, "https://999.1.1.1"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);
        /* Three groups is not an address, but it is still a dotted name, so
         * the domain branch takes it — same as the Android regexes. */
        u = rb_url_classify("1.2.3", NULL, &kind, &up);
        CHECK(STREQ(u, "https://1.2.3"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);

        u = rb_url_classify("::1", NULL, &kind, &up);
        CHECK(STREQ(u, "http://[::1]")); /* the address gets its brackets */
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);
        u = rb_url_classify("[::1]", NULL, &kind, &up);
        CHECK(STREQ(u, "http://[::1]"));
        free(u);
        u = rb_url_classify("fe80::1", NULL, &kind, &up);
        CHECK(STREQ(u, "http://[fe80::1]"));
        free(u);

        u = rb_url_classify("localhost", NULL, &kind, &up);
        CHECK(STREQ(u, "http://localhost"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);
        u = rb_url_classify("LOCALHOST", NULL, &kind, &up);
        CHECK(STREQ(u, "http://LOCALHOST")); /* the case is preserved */
        free(u);
        u = rb_url_classify("localhost:3000/app", NULL, &kind, &up);
        CHECK(STREQ(u, "http://localhost:3000/app"));
        free(u);
        u = rb_url_classify("127.0.0.1:8080", NULL, &kind, &up);
        CHECK(STREQ(u, "http://127.0.0.1:8080"));
        free(u);

        /* Explicit schemes: http/https as typed, everything else searched. */
        u = rb_url_classify("https://example.com/x", NULL, &kind, &up);
        CHECK(STREQ(u, "https://example.com/x"));
        CHECK(kind == RB_URL_INPUT_WEB);
        CHECK(up == 0);
        free(u);
        u = rb_url_classify("HTTP://example.com", NULL, &kind, &up);
        CHECK(STREQ(u, "HTTP://example.com"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);
        u = rb_url_classify("about:blank", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(STREQ(u, "https://duckduckgo.com/?q=about%3Ablank"));
        free(u);
        u = rb_url_classify("javascript:alert(1)", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        free(u);
        u = rb_url_classify("data:text/html,<b>x", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        free(u);

        /* Bare domains upgrade to https and say so. */
        u = rb_url_classify("example.com", NULL, &kind, &up);
        CHECK(STREQ(u, "https://example.com"));
        CHECK(kind == RB_URL_INPUT_WEB);
        CHECK(up == 1);
        free(u);
        u = rb_url_classify("sub.example.co.uk/path?q=1", NULL, &kind, &up);
        CHECK(STREQ(u, "https://sub.example.co.uk/path?q=1"));
        CHECK(up == 1);
        free(u);
        /* "word:..." is an explicit-scheme input by the PROTOCOL pattern, and
         * that branch runs before the bare-domain one — so a dotted name with
         * a port is SEARCHED FOR, on Android too.  The port group in
         * LOOKS_LIKE_DOMAIN is unreachable from classify for the same
         * reason. */
        u = rb_url_classify("example.com:8443", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(STREQ(u, "https://duckduckgo.com/?q=example.com%3A8443"));
        free(u);
        u = rb_url_classify("example.com:80/x", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        free(u);

        /* A single word is a query, not a host. */
        u = rb_url_classify("example", NULL, &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(STREQ(u, "https://duckduckgo.com/?q=example"));
        free(u);
        /* "12:30" is hex-and-colon all the way, so the IPv6 pattern takes it
         * on Android as well; "1.5" is a two-label dotted name.  Both are
         * odd, and both are what classify() does. */
        u = rb_url_classify("12:30", NULL, &kind, &up);
        CHECK(STREQ(u, "http://[12:30]"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);
        u = rb_url_classify("1.5", NULL, &kind, &up);
        CHECK(STREQ(u, "https://1.5"));
        CHECK(kind == RB_URL_INPUT_WEB);
        free(u);

        /* The engine id reaches the search branch. */
        u = rb_url_classify("hello world", "bing", &kind, &up);
        CHECK(kind == RB_URL_INPUT_SEARCH);
        CHECK(strstr(u, "bing.com") != NULL);
        free(u);
        /* ... and NULL out-parameters are allowed. */
        u = rb_url_classify("example.com", NULL, NULL, NULL);
        CHECK(STREQ(u, "https://example.com"));
        free(u);
    }

    /* --- percent-encoding, and the translate wrapper built on it --- */
    {
        char *s;

        s = rb_url_encode_component("a b/c?d=e&f");
        CHECK(STREQ(s, "a%20b%2Fc%3Fd%3De%26f"));
        free(s);
        /* The unreserved set is exactly what stays literal. */
        s = rb_url_encode_component("AZaz09-._~");
        CHECK(STREQ(s, "AZaz09-._~"));
        free(s);
        /* The contract is a string, never NULL — "" included. */
        s = rb_url_encode_component(NULL);
        CHECK(s != NULL && STREQ(s, ""));
        free(s);
        s = rb_url_encode_component("");
        CHECK(s != NULL && STREQ(s, ""));
        free(s);
        /* The deliberate difference from rb_search_encode(): a value on its
         * way into another URL's query keeps every byte it was handed, where
         * the search encoder trims.  Both are right for their own caller. */
        s = rb_url_encode_component(" a ");
        CHECK(STREQ(s, "%20a%20"));
        free(s);
        s = rb_search_encode(" a ");
        CHECK(STREQ(s, "a"));
        free(s);

        /* The wrapper: sl is always auto, the target is verbatim, and the
         * page URL goes in as one opaque value — its own ? and & are encoded
         * so they cannot end the u parameter early. */
        s = rb_url_translate_wrapper("https://example.com/a?b=1&c=2", "id");
        CHECK(STREQ(s, "https://translate.google.com/translate?sl=auto&tl=id"
                       "&u=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1%26c%3D2"));
        free(s);
        /* A blank target is passed through, because that is what Android's
         * dialog does with a blank setting. */
        s = rb_url_translate_wrapper("https://example.com", "");
        CHECK(STREQ(s, "https://translate.google.com/translate?sl=auto&tl="
                       "&u=https%3A%2F%2Fexample.com"));
        free(s);
        s = rb_url_translate_wrapper("https://example.com", NULL);
        CHECK(s != NULL && strstr(s, "&tl=&u=") != NULL);
        free(s);

        /* Nothing to translate: no page, or one of the browser's own. */
        CHECK(rb_url_translate_wrapper(NULL, "id") == NULL);
        CHECK(rb_url_translate_wrapper("", "id") == NULL);
        CHECK(rb_url_translate_wrapper("about:blank", "id") == NULL);
        CHECK(rb_url_translate_wrapper("ABOUT:home", "id") == NULL);
        /* ...but a real page whose path merely starts with the word is not
         * one of them. */
        s = rb_url_translate_wrapper("https://about.example.com/", "id");
        CHECK(s != NULL);
        free(s);
    }
}

/* --------------------------------- rb_tabs ------------------------------- */

static void test_rb_tabs(void)
{
    rb_tabs *t = rb_tabs_new();
    long a, b, c, d;
    rb_tab *tab;
    const rb_tab *at0;

    CHECK(rb_tabs_count(t) == 0);
    CHECK(rb_tabs_closed_count(t) == 0);
    CHECK(rb_tabs_at(t, 0) == NULL);
    CHECK(rb_tabs_get(t, 1) == NULL);
    CHECK(rb_tabs_close(t, 1, 100) == 0);
    CHECK(rb_tabs_private_count(t) == 0);

    a = rb_tabs_add(t, "One", "https://one.test/", 10);
    b = rb_tabs_add(t, "Two", "https://two.test/", 11);
    c = rb_tabs_add(t, NULL, NULL, 12);
    CHECK(a == 1); /* ids strictly increasing from 1 */
    CHECK(b == 2);
    CHECK(c == 3);
    CHECK(rb_tabs_count(t) == 3);
    CHECK(rb_tabs_closed_count(t) == 0);

    at0 = rb_tabs_at(t, 0); /* insertion order while nothing is pinned */
    CHECK(at0 != NULL);
    CHECK(at0->id == a);
    CHECK(STREQ(at0->title, "One"));
    CHECK(STREQ(at0->url, "https://one.test/"));
    CHECK(at0->position == 0); /* after the highest position in use */
    CHECK(at0->created_at == 10);
    CHECK(at0->last_viewed_at == 10);
    CHECK(at0->closed_at == 0);
    CHECK(at0->is_pinned == 0);
    CHECK(at0->is_private == 0);
    CHECK(at0->group_name != NULL && STREQ(at0->group_name, ""));
    CHECK(rb_tabs_at(t, 1)->position == 1);
    CHECK(rb_tabs_at(t, 2)->position == 2);
    CHECK(rb_tabs_at(t, 2) != NULL);
    CHECK(STREQ(rb_tabs_at(t, 2)->title, "")); /* NULL args stored as "" */
    CHECK(STREQ(rb_tabs_at(t, 2)->url, ""));
    CHECK(rb_tabs_at(t, 3) == NULL);
    CHECK(rb_tabs_at(t, -1) == NULL);

    /* The mutable slot owns its strings: a caller that replaces one hands
     * over a malloc'd pointer, which the module then frees with the tab. */
    tab = rb_tabs_get(t, b);
    CHECK(tab != NULL);
    CHECK(tab->id == b);
    free(tab->title);
    tab->title = (char *)malloc(3);
    CHECK(tab->title != NULL);
    snprintf(tab->title, 3, "%s", "T2");
    CHECK(STREQ(rb_tabs_get(t, b)->title, "T2"));
    CHECK(rb_tabs_get(t, 99) == NULL);

    /* --- closing keeps the row: that is what makes reopen possible --- */
    CHECK(rb_tabs_close(t, b, 100) == 1);
    CHECK(rb_tabs_count(t) == 2);
    CHECK(rb_tabs_closed_count(t) == 1);
    CHECK(rb_tabs_get(t, b) != NULL);      /* still there... */
    CHECK(rb_tabs_get(t, b)->closed_at == 100);
    CHECK(rb_tabs_close(t, b, 200) == 0);  /* ...but not open */
    CHECK(rb_tabs_get(t, b)->closed_at == 100); /* and not restamped */
    CHECK(rb_tabs_at(t, 0)->id == a); /* order compacted */
    CHECK(rb_tabs_at(t, 1)->id == c);
    CHECK(rb_tabs_at(t, 2) == NULL); /* closed tabs leave the strip */

    d = rb_tabs_add(t, "Four", "https://four.test/", 13); /* ids never reused */
    CHECK(d == 4);
    CHECK(rb_tabs_count(t) == 3);
    CHECK(rb_tabs_at(t, 2)->id == d);
    /* The position comes from the OPEN tabs (0 and 2), so the closed tab's
     * slot 1 is not reused while a higher one is still in use. */
    CHECK(rb_tabs_at(t, 2)->position == 3);

    /* --- reopen --- */
    CHECK(rb_tabs_reopen(t, d) == 0);  /* already open */
    CHECK(rb_tabs_reopen(t, 99) == 0); /* unknown */
    CHECK(rb_tabs_reopen(t, b) == 1);
    CHECK(rb_tabs_count(t) == 4);
    CHECK(rb_tabs_closed_count(t) == 0);
    CHECK(rb_tabs_get(t, b)->closed_at == 0);
    CHECK(STREQ(rb_tabs_get(t, b)->title, "T2")); /* survived the round trip */

    /* --- pinned tabs sort to the front, and the strip reorders --- */
    CHECK(rb_tabs_pin(t, c, 1) == 1);
    CHECK(rb_tabs_private_count(t) == 0);
    CHECK(rb_tabs_at(t, 0)->id == c); /* pinned first */
    CHECK(rb_tabs_at(t, 1)->id == a); /* then by position: 0, 1, 2 */
    CHECK(rb_tabs_at(t, 2)->id == b);
    CHECK(rb_tabs_at(t, 3)->id == d);
    CHECK(rb_tabs_pin(t, c, 0) == 1);
    CHECK(rb_tabs_at(t, 0)->id == a); /* back to position order */
    CHECK(rb_tabs_pin(t, 99, 1) == 0);
    CHECK(rb_tabs_pin(t, b, 1) == 1);
    CHECK(rb_tabs_at(t, 0)->id == b); /* pinned, even with position 1 */
    CHECK(rb_tabs_pin(t, b, 0) == 1);
    CHECK(rb_tabs_at(t, 0)->id == a);

    /* --- move (TabDao.setposition: a plain assignment) --- */
    CHECK(rb_tabs_move(t, d, -1) == 1);
    CHECK(rb_tabs_at(t, 0)->id == d);
    CHECK(rb_tabs_move(t, 99, 0) == 0);

    /* --- groups --- */
    CHECK(rb_tabs_group(t, a, "work") == 1);
    CHECK(STREQ(rb_tabs_get(t, a)->group_name, "work"));
    CHECK(rb_tabs_group(t, a, NULL) == 1);
    CHECK(STREQ(rb_tabs_get(t, a)->group_name, ""));
    CHECK(rb_tabs_group(t, 99, "x") == 0);

    /* --- touch --- */
    CHECK(rb_tabs_touch(t, a, 555) == 1);
    CHECK(rb_tabs_get(t, a)->last_viewed_at == 555);
    CHECK(rb_tabs_get(t, a)->created_at == 10); /* creation is immutable */
    CHECK(rb_tabs_touch(t, 99, 555) == 0);

    /* --- private tabs --- */
    CHECK(rb_tabs_set_private(t, d, 1) == 1);
    CHECK(rb_tabs_private_count(t) == 1);
    CHECK(rb_tabs_set_private(t, d, 0) == 1);
    CHECK(rb_tabs_private_count(t) == 0);
    CHECK(rb_tabs_set_private(t, 99, 1) == 0);

    /* --- close_others --- */
    {
        rb_tabs *o = rb_tabs_new();
        long ids[5];
        int i;

        for (i = 0; i < 5; i++) {
            ids[i] = rb_tabs_add(o, "t", "https://t.test/", (long long)i);
        }
        CHECK(rb_tabs_close_others(o, ids[2], RB_TABS_KEEP_LEFT, 900) == 2);
        CHECK(rb_tabs_count(o) == 3);
        CHECK(rb_tabs_get(o, ids[0])->closed_at == 900);
        CHECK(rb_tabs_get(o, ids[1])->closed_at == 900);
        CHECK(rb_tabs_get(o, ids[3])->closed_at == 0);
        CHECK(rb_tabs_get(o, ids[4])->closed_at == 0);

        CHECK(rb_tabs_close_others(o, ids[2], RB_TABS_KEEP_RIGHT, 901) == 2);
        CHECK(rb_tabs_count(o) == 1);
        CHECK(rb_tabs_at(o, 0)->id == ids[2]);
        CHECK(rb_tabs_get(o, ids[3])->closed_at == 901);

        /* "close others" on a closed keeper closes nothing. */
        CHECK(rb_tabs_close_others(o, ids[0], RB_TABS_KEEP_OTHERS, 902) == 0);
        CHECK(rb_tabs_count(o) == 1);

        CHECK(rb_tabs_close_others(o, 99, RB_TABS_KEEP_OTHERS, 903) == 0);
        rb_tabs_free(o);
    }

    /* --- the recently-closed stack --- */
    {
        rb_tabs *r = rb_tabs_new();
        const rb_tab *recent[16];
        long ids[5];
        int i;
        int n;

        for (i = 0; i < 5; i++) {
            ids[i] = rb_tabs_add(r, "t", "https://t.test/", (long long)i);
        }
        CHECK(rb_tabs_recently_closed(r, recent, 16) == 0);

        /* Closed out of order, so "newest first" is not just reverse order. */
        CHECK(rb_tabs_close(r, ids[1], 100) == 1);
        CHECK(rb_tabs_close(r, ids[3], 200) == 1);
        CHECK(rb_tabs_close(r, ids[0], 300) == 1);
        n = rb_tabs_recently_closed(r, recent, 16);
        CHECK(n == 3);
        CHECK(recent[0]->id == ids[0]); /* 300, the most recent */
        CHECK(recent[1]->id == ids[3]);
        CHECK(recent[2]->id == ids[1]);

        /* The limit is applied, and only closed tabs are listed. */
        n = rb_tabs_recently_closed(r, recent, 2);
        CHECK(n == 2);
        CHECK(recent[0]->id == ids[0]);

        /* Reopening takes it off the stack. */
        CHECK(rb_tabs_reopen(r, ids[0]) == 1);
        n = rb_tabs_recently_closed(r, recent, 16);
        CHECK(n == 2);
        CHECK(recent[0]->id == ids[3]);

        /* Purge by age: the retention policy is 30 days. */
        rb_tabs_free(r);
        r = rb_tabs_new();
        for (i = 0; i < 3; i++) {
            ids[i] = rb_tabs_add(r, "t", "https://t.test/", 0);
        }
        CHECK(rb_tabs_close(r, ids[0], 1000) == 1);
        CHECK(rb_tabs_close(r, ids[1], 1000000000LL) == 1);
        CHECK(rb_tabs_purge_closed_before(r, 2000) == 1);
        CHECK(rb_tabs_closed_count(r) == 1);
        CHECK(rb_tabs_get(r, ids[1]) != NULL);
        CHECK(rb_tabs_forget_closed(r) == 1);
        CHECK(rb_tabs_closed_count(r) == 0);
        CHECK(rb_tabs_count(r) == 1); /* open tabs untouched */
        CHECK(rb_tabs_forget_closed(r) == 0);
        CHECK(rb_tabs_recently_closed(r, recent, 16) == 0);
        CHECK(rb_tabs_recently_closed(NULL, recent, 16) == 0);
        CHECK(rb_tabs_recently_closed(r, NULL, 16) == 0);
        CHECK(rb_tabs_recently_closed(r, recent, 0) == 0);
        rb_tabs_free(r);

        /* rb_tabs_purge_closed applies the 30-day window to `now`. */
        r = rb_tabs_new();
        ids[0] = rb_tabs_add(r, "t", "https://t.test/", 0);
        ids[1] = rb_tabs_add(r, "t", "https://t.test/", 0);
        CHECK(rb_tabs_close(r, ids[0], 1000) == 1);
        CHECK(rb_tabs_close(r, ids[1], 40LL * 86400000LL) == 1);
        CHECK(rb_tabs_purge_closed(r, 41LL * 86400000LL) == 1);
        CHECK(rb_tabs_closed_count(r) == 1);
        CHECK(rb_tabs_get(r, ids[1]) != NULL);
        rb_tabs_free(r);
    }

    /* --- session persistence --- */
    {
        rb_tabs *saved = rb_tabs_new();
        rb_tabs *loaded;
        long p1;
        long p2;
        long p3;

        p1 = rb_tabs_add(saved, "Pinned \"one\"", "https://one.test/", 10);
        p2 = rb_tabs_add(saved, "caf\xc3\xa9", "https://caf\xc3\xa9.test/", 11);
        p3 = rb_tabs_add(saved, "Private", "https://secret.test/", 12);
        CHECK(rb_tabs_pin(saved, p1, 1) == 1);
        CHECK(rb_tabs_group(saved, p2, "work") == 1);
        CHECK(rb_tabs_set_private(saved, p3, 1) == 1);
        CHECK(rb_tabs_touch(saved, p2, 777) == 1);
        CHECK(rb_tabs_close(saved, p2, 888) == 1);
        CHECK(rb_tabs_close(saved, p3, 889) == 1);

        CHECK(rb_tabs_save(saved, TMP) == 0);
        loaded = rb_tabs_new();
        CHECK(rb_tabs_load(loaded, TMP) == 0);

        /* p3 was private, and private tabs are never written. */
        CHECK(rb_tabs_count(loaded) == 1);
        CHECK(rb_tabs_at(loaded, 0)->id == p1);
        CHECK(STREQ(rb_tabs_at(loaded, 0)->title, "Pinned \"one\""));
        CHECK(rb_tabs_at(loaded, 0)->is_pinned == 1);
        CHECK(rb_tabs_at(loaded, 0)->created_at == 10);
        CHECK(rb_tabs_get(loaded, p3) == NULL);

        /* The closed tab came back closed, with its group and timestamps. */
        CHECK(rb_tabs_closed_count(loaded) == 1);
        CHECK(rb_tabs_get(loaded, p2) != NULL);
        CHECK(rb_tabs_get(loaded, p2)->closed_at == 888);
        CHECK(rb_tabs_get(loaded, p2)->last_viewed_at == 777);
        CHECK(STREQ(rb_tabs_get(loaded, p2)->group_name, "work"));
        CHECK(STREQ(rb_tabs_get(loaded, p2)->title, "caf\xc3\xa9"));
        CHECK(rb_tabs_reopen(loaded, p2) == 1);

        /* Ids from the file are never reissued: the highest restored id was
         * p2's, so the next new tab has to be past it. */
        CHECK(rb_tabs_add(loaded, "new", "https://new.test/", 20) > p2);

        rb_tabs_free(loaded);
        remove(TMP);
    }

    /* A missing file is fine and empties the table. */
    {
        rb_tabs *loaded = rb_tabs_new();
        CHECK(rb_tabs_add(loaded, "stale", "https://stale.test/", 1) != 0);
        CHECK(rb_tabs_load(loaded, "rb-test-does-not-exist.txt") == 0);
        CHECK(rb_tabs_count(loaded) == 0);
        CHECK(rb_tabs_closed_count(loaded) == 0);

        /* Garbage lines are skipped, and the open run is rebuilt from the
         * pinned/position fields rather than the file order. */
        {
            FILE *f = fopen(TMP, "wb");
            CHECK(f != NULL);
            if (f != NULL) {
                fprintf(f, "not json\n");
                fprintf(f, "{\"id\":1,\"position\":5,\"title\":\"last\","
                           "\"url\":\"https://a.test/\"}\n");
                fprintf(f, "{\"id\":2,\"position\":1,\"is_pinned\":1,"
                           "\"title\":\"first\",\"url\":\"https://b.test/\"}\n");
                fprintf(f, "{\"id\":3,\"position\":2,\"title\":\"middle\","
                           "\"url\":\"https://c.test/\",\"closed_at\":5}\n");
                fclose(f);
            }
            CHECK(rb_tabs_load(loaded, TMP) == 0);
            CHECK(rb_tabs_count(loaded) == 2);
            CHECK(rb_tabs_closed_count(loaded) == 1);
            CHECK(STREQ(rb_tabs_at(loaded, 0)->title, "first"));  /* pinned */
            CHECK(STREQ(rb_tabs_at(loaded, 1)->title, "last"));   /* pos 5 */
            CHECK(STREQ(rb_tabs_get(loaded, 3)->title, "middle"));
            CHECK(rb_tabs_get(loaded, 3)->closed_at == 5);
        }
        rb_tabs_free(loaded);
        remove(TMP);
    }

    CHECK(rb_tabs_save(NULL, TMP) == -1);
    CHECK(rb_tabs_save(t, NULL) == -1);
    CHECK(rb_tabs_load(NULL, TMP) == -1);
    CHECK(rb_tabs_load(t, NULL) == -1);

    rb_tabs_free(t);
    rb_tabs_free(NULL); /* must not crash */
}

/* ------------------------------- rb_history ------------------------------ */

static void test_rb_history(void)
{
    rb_history *h = rb_history_new();
    rb_history *h2;
    const rb_hist_entry *rec;
    int n = 0;
    long long ts0;

    CHECK(rb_history_count(h) == 0);
    CHECK(rb_history_recent(h, 5, &n) == NULL);
    CHECK(n == 0);

    rb_history_append(h, "https://a.test/", "A");
    rb_history_append(h, "https://b.test/", "B");
    CHECK(rb_history_count(h) == 2);

    /* consecutive same-URL: entry replaced, count unchanged */
    rb_history_append(h, "https://b.test/", "B revisited");
    CHECK(rb_history_count(h) == 2);
    rec = rb_history_recent(h, 10, &n);
    CHECK(rec != NULL);
    CHECK(n == 2);
    CHECK(STREQ(rec[0].url, "https://b.test/"));
    CHECK(STREQ(rec[0].title, "B revisited")); /* title was replaced */
    CHECK(STREQ(rec[1].url, "https://a.test/"));
    CHECK(rec[0].visited_at >= rec[1].visited_at);

    /* a different URL in between breaks the consecutive dedupe */
    rb_history_append(h, "https://a.test/", "A again");
    CHECK(rb_history_count(h) == 3);
    rec = rb_history_recent(h, 2, &n);
    CHECK(n == 2); /* most-recent first */
    CHECK(STREQ(rec[0].url, "https://a.test/"));
    CHECK(STREQ(rec[1].url, "https://b.test/"));
    rec = rb_history_recent(h, 1, &n);
    CHECK(n == 1);
    CHECK(STREQ(rec[0].url, "https://a.test/"));
    CHECK(rb_history_recent(h, 0, &n) == NULL);
    CHECK(n == 0);

    /* JSON-lines roundtrip including quotes, backslashes and newlines */
    rb_history_append(h, "https://quote.test/?x=\"y\"&z=\\",
                      "He said \"hi\" \\ line\nbreak\ttab");
    rec = rb_history_recent(h, 1, &n);
    CHECK(rec != NULL);
    ts0 = rec[0].visited_at;
    CHECK(rb_history_save(h, TMP) == 0);

    h2 = rb_history_new();
    CHECK(rb_history_load(h2, TMP) == 0);
    CHECK(rb_history_count(h2) == rb_history_count(h));
    rec = rb_history_recent(h2, 10, &n);
    CHECK(rec != NULL);
    CHECK(n == 4);
    CHECK(STREQ(rec[0].url, "https://quote.test/?x=\"y\"&z=\\"));
    CHECK(STREQ(rec[0].title, "He said \"hi\" \\ line\nbreak\ttab"));
    CHECK(rec[0].visited_at == ts0); /* timestamps survive the roundtrip */
    CHECK(STREQ(rec[2].url, "https://b.test/"));
    CHECK(STREQ(rec[2].title, "B revisited"));
    CHECK(STREQ(rec[3].url, "https://a.test/")); /* oldest is last */
    CHECK(STREQ(rec[3].title, "A"));
    rb_history_free(h2);
    remove(TMP);

    /* missing file is fine, existing state untouched */
    CHECK(rb_history_load(h, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_history_count(h) == 4);

    /* malformed lines are skipped, valid ones survive */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "not json at all\n");
            fprintf(f, "{\"url\":\"https://ok.test/\",\"title\":\"OK\",\"visited_at\":42}\n");
            fprintf(f, "{\"url\":\n");
            fprintf(f, "{}\n");
            fprintf(f, "{\"title\":\"no url\",\"visited_at\":7}\n");
            fprintf(f, "\n");
            fclose(f);
        }
        h2 = rb_history_new();
        CHECK(rb_history_load(h2, TMP) == 0);
        CHECK(rb_history_count(h2) == 1);
        rec = rb_history_recent(h2, 10, &n);
        CHECK(rec != NULL);
        CHECK(n == 1);
        CHECK(STREQ(rec[0].url, "https://ok.test/"));
        CHECK(STREQ(rec[0].title, "OK"));
        CHECK(rec[0].visited_at == 42);
        rb_history_free(h2);
        remove(TMP);
    }

    rb_history_free(h);
    rb_history_free(NULL); /* must not crash */
}

/* -------------------- rb_history: search and retention -------------------- */

/* Append stamps time(NULL), so every entry made in one test run shares a
 * timestamp — which is precisely why the cutoff cases below use "now" as a
 * boundary rather than trying to space entries apart in time. */
#define RB_HIST_FUTURE 4102444800LL /* 2100-01-01, seconds */

static void test_rb_history_ops(void)
{
    rb_history *h = rb_history_new();
    const rb_hist_entry *hits[RB_HISTORY_SEARCH_MAX];

    rb_history_append(h, "https://example.com/one", "First page");
    rb_history_append(h, "https://other.test/two", "Second page");
    rb_history_append(h, "https://example.com/THREE", "Third page");
    CHECK(rb_history_count(h) == 3);

    /* An empty or NULL needle matches everything: the history screen's state
     * before the user types. */
    CHECK(rb_history_search(h, "", hits, RB_HISTORY_SEARCH_MAX) == 3);
    CHECK(rb_history_search(h, NULL, hits, RB_HISTORY_SEARCH_MAX) == 3);
    CHECK(STREQ(hits[0]->url, "https://example.com/THREE")); /* newest first */

    /* Case-insensitive on url and title alike (SQLite LIKE). */
    CHECK(rb_history_search(h, "example.com", hits, 4) == 2);
    CHECK(rb_history_search(h, "EXAMPLE", hits, 4) == 2);
    CHECK(rb_history_search(h, "three", hits, 4) == 1);
    CHECK(STREQ(hits[0]->title, "Third page")); /* matched on the title */
    CHECK(rb_history_search(h, "second", hits, 4) == 1);
    CHECK(STREQ(hits[0]->url, "https://other.test/two"));
    CHECK(rb_history_search(h, "nothing here", hits, 4) == 0);

    /* The limit applies to the newest matches, and 0/NULL are no-ops. */
    CHECK(rb_history_search(h, "e", hits, 1) == 1);
    CHECK(STREQ(hits[0]->url, "https://example.com/THREE"));
    CHECK(rb_history_search(h, "e", hits, 0) == 0);
    CHECK(rb_history_search(h, "e", NULL, 5) == 0);
    CHECK(rb_history_search(NULL, "e", hits, 5) == 0);

    /* Distinct sites counts URLs, not visits. */
    CHECK(rb_history_distinct_sites(h, 0) == 3);
    CHECK(rb_history_distinct_sites(h, RB_HIST_FUTURE) == 0);
    CHECK(rb_history_distinct_sites(NULL, 0) == 0);

    /* --- removing one row --- */
    CHECK(rb_history_remove_at(h, 1) == 1); /* "Second page" */
    CHECK(rb_history_count(h) == 2);
    CHECK(rb_history_search(h, "second", hits, 4) == 0);
    CHECK(rb_history_remove_at(h, 5) == 0);  /* past the end */
    CHECK(rb_history_remove_at(h, -1) == 0);
    CHECK(rb_history_remove_at(NULL, 0) == 0);
    CHECK(rb_history_count(h) == 2); /* the failures changed nothing */

    /* --- deleteSince: a whole time window, newest first --- */
    CHECK(rb_history_delete_since(h, RB_HIST_FUTURE) == 0);
    CHECK(rb_history_count(h) == 2);
    CHECK(rb_history_delete_since(h, 0) == 2); /* everything is "since then" */
    CHECK(rb_history_count(h) == 0);
    CHECK(rb_history_delete_since(h, 0) == 0);
    CHECK(rb_history_delete_since(NULL, 0) == 0);

    /* --- clear --- */
    rb_history_append(h, "https://c.test/", "C");
    rb_history_append(h, "https://d.test/", "D");
    CHECK(rb_history_count(h) == 2);
    CHECK(rb_history_clear(h) == 2);
    CHECK(rb_history_count(h) == 0);
    CHECK(rb_history_clear(h) == 0);
    CHECK(rb_history_search(h, "", hits, 4) == 0);
    CHECK(rb_history_distinct_sites(h, 0) == 0);
    CHECK(rb_history_clear(NULL) == 0);

    /* Repeated visits to one page are one distinct site, and the search
     * window narrows by the same cutoff the count does. */
    rb_history_append(h, "https://e.test/", "E");
    rb_history_append(h, "https://e.test/", "E again");
    rb_history_append(h, "https://f.test/", "F");
    CHECK(rb_history_count(h) == 2); /* the repeat replaced, not appended */
    CHECK(rb_history_distinct_sites(h, 0) == 2);
    CHECK(rb_history_distinct_sites(h, RB_HIST_FUTURE) == 0);

    rb_history_free(h);
}

/* ------------------------------ rb_bookmarks ----------------------------- */

/* The display order is the DAO's ORDER BY folder IS NULL, folder, position,
 * created_at — so the assertions below walk the list by index and check the
 * URLs in exactly that order. */
static void test_rb_bookmarks(void)
{
    rb_bookmarks *b = rb_bookmarks_new();
    rb_bookmarks *b2;
    long id_a, id_b, id_c;
    const rb_bookmark *bm;
    const long long NOW = 1700000000000LL;

    CHECK(rb_bookmarks_count(b) == 0);
    CHECK(rb_bookmarks_max_position(b) == -1); /* Kotlin's ?: -1 */
    CHECK(rb_bookmarks_at(b, 0) == NULL);
    CHECK(rb_bookmarks_find(b, "https://a.test/") == NULL);

    id_a = rb_bookmarks_add(b, "https://a.test/", "A", NULL, NOW);
    CHECK(id_a > 0);
    /* Already bookmarked: addBookmark returns -1, and the list is untouched. */
    CHECK(rb_bookmarks_add(b, "https://a.test/", "A again", NULL, NOW) == -1);
    id_b = rb_bookmarks_add(b, "https://b.test/", "B \"quoted\"", NULL, NOW + 1);
    CHECK(id_b > id_a);
    CHECK(rb_bookmarks_add(b, NULL, "nope", NULL, NOW) == -1);
    CHECK(rb_bookmarks_add(b, "", "nope", NULL, NOW) == -1);
    CHECK(rb_bookmarks_count(b) == 2);
    CHECK(rb_bookmarks_max_position(b) == 1); /* 0 and 1, first was 0 */

    CHECK(rb_bookmarks_contains(b, "https://a.test/") == 1);
    CHECK(rb_bookmarks_contains(b, "https://zz.test/") == 0);
    CHECK(rb_bookmarks_contains(b, NULL) == 0);
    bm = rb_bookmarks_find(b, "https://a.test/");
    CHECK(bm != NULL);
    CHECK(bm->id == id_a);
    CHECK(STREQ(bm->title, "A"));
    CHECK(bm->folder == NULL);
    CHECK(bm->position == 0);
    CHECK(bm->created_at == NOW);

    /* Neither has a folder, so both are in the trailing group and position
     * decides: A (0) before B (1). */
    CHECK(STREQ(rb_bookmarks_at(b, 0)->url, "https://a.test/"));
    CHECK(STREQ(rb_bookmarks_at(b, 1)->title, "B \"quoted\""));
    CHECK(rb_bookmarks_at(b, 2) == NULL);
    CHECK(rb_bookmarks_at(b, -1) == NULL);

    /* --- folders reorder the list: a foldered row sorts before a folder-less
     * one even when it was added later (folder IS NULL ascending puts the
     * folder-less rows last). --- */
    id_c = rb_bookmarks_add(b, "https://c.test/", "C", "Reading", NOW + 2);
    CHECK(id_c > id_b);
    CHECK(rb_bookmarks_count(b) == 3);
    CHECK(STREQ(rb_bookmarks_at(b, 0)->url, "https://c.test/")); /* foldered */
    CHECK(STREQ(rb_bookmarks_at(b, 0)->folder, "Reading"));
    CHECK(STREQ(rb_bookmarks_at(b, 1)->url, "https://a.test/"));
    CHECK(STREQ(rb_bookmarks_at(b, 2)->url, "https://b.test/"));

    rb_bookmarks_add(b, "https://d.test/", "D", "Archive", NOW + 3);
    rb_bookmarks_add(b, "https://e.test/", "E", "Reading", NOW + 4);
    /* Folder name decides first: Archive, then both Reading rows by position,
     * then the two folder-less ones by position. */
    CHECK(STREQ(rb_bookmarks_at(b, 0)->folder, "Archive"));
    CHECK(STREQ(rb_bookmarks_at(b, 1)->folder, "Reading"));
    CHECK(STREQ(rb_bookmarks_at(b, 1)->url, "https://c.test/"));
    CHECK(STREQ(rb_bookmarks_at(b, 2)->url, "https://e.test/"));
    CHECK(rb_bookmarks_at(b, 3)->folder == NULL);
    CHECK(STREQ(rb_bookmarks_at(b, 3)->url, "https://a.test/"));
    CHECK(STREQ(rb_bookmarks_at(b, 4)->url, "https://b.test/"));

    /* --- updateMeta --- */
    CHECK(rb_bookmarks_update_meta(b, id_a, "A renamed", NULL) == 1);
    CHECK(STREQ(rb_bookmarks_find(b, "https://a.test/")->title, "A renamed"));
    CHECK(rb_bookmarks_update_meta(b, 9999, "nope", NULL) == 0);
    /* Moving a row into a folder moves it in the list.  Positions are
     * profile-wide (BookmarkDao.maxPosition is not per-folder), so A — the
     * first bookmark ever added, position 0 — lands ahead of D (position 3)
     * even though D was put in the folder first. */
    CHECK(rb_bookmarks_update_meta(b, id_a, "A", "Archive") == 1);
    CHECK(STREQ(rb_bookmarks_at(b, 0)->folder, "Archive"));
    CHECK(STREQ(rb_bookmarks_at(b, 0)->url, "https://a.test/"));
    CHECK(STREQ(rb_bookmarks_at(b, 1)->url, "https://d.test/"));
    /* Clearing the folder sends it back to the folder-less group, where it
     * still leads on position. */
    CHECK(rb_bookmarks_update_meta(b, id_a, "A", NULL) == 1);
    CHECK(rb_bookmarks_at(b, 3)->folder == NULL);
    CHECK(STREQ(rb_bookmarks_at(b, 3)->url, "https://a.test/"));
    /* "" is the no-folder case, not a folder named "". */
    CHECK(rb_bookmarks_update_meta(b, id_b, "B", "") == 1);
    CHECK(rb_bookmarks_find(b, "https://b.test/")->folder == NULL);

    /* --- toggle --- */
    CHECK(rb_bookmarks_toggle(b, "https://new.test/", "New", NOW) == 1);
    CHECK(rb_bookmarks_contains(b, "https://new.test/") == 1);
    CHECK(rb_bookmarks_toggle(b, "https://new.test/", "New", NOW) == 0);
    CHECK(rb_bookmarks_contains(b, "https://new.test/") == 0);
    CHECK(rb_bookmarks_count(b) == 5);
    /* A blank title is the caller's business, but a NULL one must not crash
     * and must store "". */
    CHECK(rb_bookmarks_toggle(b, "https://t.test/", NULL, NOW) == 1);
    CHECK(STREQ(rb_bookmarks_find(b, "https://t.test/")->title, ""));
    CHECK(rb_bookmarks_toggle(b, "https://t.test/", NULL, NOW) == 0);
    CHECK(rb_bookmarks_toggle(b, NULL, "x", NOW) == 0);
    CHECK(rb_bookmarks_count(b) == 5);

    /* --- persistence --- */
    CHECK(rb_bookmarks_save(b, TMP) == 0);
    b2 = rb_bookmarks_new();
    CHECK(rb_bookmarks_load(b2, TMP) == 0);
    CHECK(rb_bookmarks_count(b2) == 5);
    {
        /* The round trip preserves ids, folders, positions and the order
         * itself — save writes display order and load re-sorts, so a stable
         * order has to survive both. */
        int i;
        for (i = 0; i < 5; i++) {
            CHECK(rb_bookmarks_at(b2, i)->id == rb_bookmarks_at(b, i)->id);
            CHECK(STREQ(rb_bookmarks_at(b2, i)->url, rb_bookmarks_at(b, i)->url));
            CHECK(STREQ(rb_bookmarks_at(b2, i)->title, rb_bookmarks_at(b, i)->title));
            CHECK(rb_bookmarks_at(b2, i)->position == rb_bookmarks_at(b, i)->position);
            CHECK(rb_bookmarks_at(b2, i)->created_at == rb_bookmarks_at(b, i)->created_at);
            if (rb_bookmarks_at(b, i)->folder == NULL) {
                CHECK(rb_bookmarks_at(b2, i)->folder == NULL);
            } else {
                CHECK(STREQ(rb_bookmarks_at(b2, i)->folder,
                            rb_bookmarks_at(b, i)->folder));
            }
        }
    }
    /* A new bookmark in the reloaded store must not reuse a restored id. */
    CHECK(rb_bookmarks_add(b2, "https://fresh.test/", "F", NULL, NOW) > id_c);
    CHECK(rb_bookmarks_load(b2, TMP) == 0); /* reload stays de-duplicated */
    CHECK(rb_bookmarks_count(b2) == 6);
    rb_bookmarks_free(b2);
    remove(TMP);

    /* missing file is fine */
    CHECK(rb_bookmarks_load(b, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_bookmarks_count(b) == 5);

    /* malformed lines skipped; duplicate URLs collapse to the first */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "garbage\n");
            fprintf(f, "{\"url\":\"https://ok.test/\",\"title\":\"OK\"}\n");
            fprintf(f, "{\"url\":\"https://dup.test/\",\"title\":\"1\"}\n");
            fprintf(f, "{\"url\":\"https://dup.test/\",\"title\":\"2\"}\n");
            fprintf(f, "{\"title\":\"missing url\"}\n");
            fprintf(f, "{\"url\":\"https://nul.test/\",\"folder\":null}\n");
            fprintf(f, "{\"url\":\"https://num.test/\",\"folder\":7}\n");
            fclose(f);
        }
        b2 = rb_bookmarks_new();
        CHECK(rb_bookmarks_load(b2, TMP) == 0);
        /* ok, dup (first wins), nul, num — the last two with no folder,
         * since a number is not a folder name. */
        CHECK(rb_bookmarks_count(b2) == 4);
        CHECK(rb_bookmarks_find(b2, "https://dup.test/") != NULL);
        CHECK(STREQ(rb_bookmarks_find(b2, "https://dup.test/")->title, "1"));
        /* Rows from before ids existed get fresh ones rather than id 0. */
        CHECK(rb_bookmarks_find(b2, "https://ok.test/")->id > 0);
        CHECK(rb_bookmarks_find(b2, "https://ok.test/")->position == 0);
        CHECK(rb_bookmarks_find(b2, "https://ok.test/")->folder == NULL);
        CHECK(rb_bookmarks_find(b2, "https://nul.test/")->folder == NULL);
        CHECK(rb_bookmarks_find(b2, "https://num.test/")->folder == NULL);
        rb_bookmarks_free(b2);
        remove(TMP);
    }

    /* Regression: the private escape decoder used to read the 'u' of
     * "\uXXXX" as the first hex digit, so every Unicode escape failed to
     * parse and took the whole line down with it.  A bookmark whose title
     * arrived escaped has to survive the load. */
    {
        const char *utf8 = "caf\xc3\xa9 \xe2\x80\x94 \xf0\x9f\x98\x80";
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            /* café — 😀, the last one as a surrogate pair */
            fprintf(f,
                    "{\"url\":\"https://uni.test/\","
                    "\"title\":\"caf\\u00e9 \\u2014 \\ud83d\\ude00\","
                    "\"folder\":\"caf\\u00e9\"}\n");
            fclose(f);
        }
        b2 = rb_bookmarks_new();
        CHECK(rb_bookmarks_load(b2, TMP) == 0);
        CHECK(rb_bookmarks_count(b2) == 1);
        CHECK(STREQ(rb_bookmarks_at(b2, 0)->title, utf8));
        CHECK(STREQ(rb_bookmarks_at(b2, 0)->folder, "caf\xc3\xa9"));

        /* ... and a reload of what save() wrote is byte-identical. */
        CHECK(rb_bookmarks_save(b2, TMP) == 0);
        rb_bookmarks_free(b2);
        b2 = rb_bookmarks_new();
        CHECK(rb_bookmarks_load(b2, TMP) == 0);
        CHECK(STREQ(rb_bookmarks_at(b2, 0)->title, utf8));
        rb_bookmarks_free(b2);
        remove(TMP);
    }

    /* --- deletion --- */
    CHECK(rb_bookmarks_delete(b, id_a) == 1);
    CHECK(rb_bookmarks_delete(b, id_a) == 0);
    CHECK(rb_bookmarks_delete(b, 9999) == 0);
    CHECK(rb_bookmarks_count(b) == 4);
    CHECK(rb_bookmarks_contains(b, "https://a.test/") == 0);
    CHECK(rb_bookmarks_delete_url(b, "https://b.test/") == 1);
    CHECK(rb_bookmarks_delete_url(b, "https://b.test/") == 0);
    CHECK(rb_bookmarks_delete_url(b, NULL) == 0);
    CHECK(rb_bookmarks_count(b) == 3);
    CHECK(rb_bookmarks_by_id(b, id_c) != NULL);
    CHECK(rb_bookmarks_by_id(b, id_a) == NULL);
    CHECK(rb_bookmarks_delete(NULL, 1) == 0);

    CHECK(rb_bookmarks_clear(b) == 3);
    CHECK(rb_bookmarks_count(b) == 0);
    CHECK(rb_bookmarks_clear(b) == 0);
    CHECK(rb_bookmarks_clear(NULL) == 0);
    CHECK(rb_bookmarks_max_position(b) == -1);
    CHECK(rb_bookmarks_at(b, 0) == NULL);
    /* The id counter keeps going up after a clear, so an id held by a UI row
     * that was just deleted cannot come back to life as a different row. */
    CHECK(rb_bookmarks_add(b, "https://after.test/", "After", NULL, NOW) > id_c);
    CHECK(rb_bookmarks_max_position(b) == 0); /* maxPosition of the empty list */

    rb_bookmarks_free(b);
    rb_bookmarks_free(NULL); /* must not crash */
}

/* ------------------------------ rb_settings ------------------------------ */

static void test_rb_settings(void)
{
    rb_settings *s = rb_settings_new();
    rb_settings *s2;

    /* project-wide policy: javascript NEVER defaults to 0 */
    CHECK(rb_settings_get_int(s, "javascript", 0) == 1);
    CHECK(STREQ(rb_settings_get(s, "home", "?"), "https://duckduckgo.com"));
    CHECK(STREQ(rb_settings_get(s, "search_engine", "?"), "duckduckgo"));

    CHECK(STREQ(rb_settings_get(s, "missing", "fallback"), "fallback"));
    CHECK(rb_settings_get(s, "missing", NULL) == NULL);
    CHECK(rb_settings_get_int(s, "missing", 7) == 7);

    rb_settings_set(s, "home", "https://example.com/");
    rb_settings_set_int(s, "zoom_pct", 150);
    rb_settings_set(s, "ua", "Mozilla/5.0 (X11; =; ok)");
    CHECK(STREQ(rb_settings_get(s, "home", "?"), "https://example.com/"));
    CHECK(rb_settings_get_int(s, "zoom_pct", 100) == 150);
    CHECK(rb_settings_get_int(s, "home", 3) == 3); /* not an int */

    CHECK(rb_settings_save(s, TMP) == 0);
    s2 = rb_settings_new();
    CHECK(rb_settings_get_int(s2, "javascript", 0) == 1); /* fresh defaults */
    CHECK(rb_settings_load(s2, TMP) == 0);
    CHECK(STREQ(rb_settings_get(s2, "home", "?"), "https://example.com/"));
    CHECK(rb_settings_get_int(s2, "zoom_pct", 100) == 150);
    CHECK(STREQ(rb_settings_get(s2, "search_engine", "?"), "duckduckgo"));
    CHECK(STREQ(rb_settings_get(s2, "ua", "?"), "Mozilla/5.0 (X11; =; ok)"));
    rb_settings_set_int(s2, "javascript", 0); /* user may still opt out */
    CHECK(rb_settings_get_int(s2, "javascript", 1) == 0);
    rb_settings_free(s2);
    remove(TMP);

    /* missing file keeps the defaults */
    s2 = rb_settings_new();
    CHECK(rb_settings_load(s2, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_settings_get_int(s2, "javascript", 0) == 1);
    CHECK(STREQ(rb_settings_get(s2, "home", "?"), "https://duckduckgo.com"));
    rb_settings_free(s2);

    /* malformed lines skipped; '=' inside the value survives */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "noequals\n");
            fprintf(f, "  spaced  =  v  \n");
            fprintf(f, "=orphan\n");
            fprintf(f, "a=b=c\n");
            fprintf(f, "\n");
            fclose(f);
        }
        s2 = rb_settings_new();
        CHECK(rb_settings_load(s2, TMP) == 0);
        CHECK(STREQ(rb_settings_get(s2, "spaced", "?"), "  v  ")); /* key trimmed, value verbatim */
        CHECK(STREQ(rb_settings_get(s2, "a", "?"), "b=c"));
        CHECK(STREQ(rb_settings_get(s2, "noequals", "gone"), "gone"));
        CHECK(rb_settings_get(s2, "orphan", "gone") != NULL);
        rb_settings_free(s2);
        remove(TMP);
    }

    rb_settings_free(s);
    rb_settings_free(NULL); /* must not crash */
}

/* -------------------------------- rb_paths ------------------------------- */

static void test_rb_paths(void)
{
    char *dir = rb_paths_data_dir();
    const char *home;
    struct stat st;

    CHECK(dir != NULL);
    if (dir != NULL) {
        CHECK(stat(dir, &st) == 0);
        CHECK(S_ISDIR(st.st_mode));
        home = getenv("HOME");
        CHECK(home != NULL);
        if (home != NULL) {
            CHECK(strncmp(dir, home, strlen(home)) == 0);
            CHECK(strstr(dir, "RoomBrowser") != NULL);
        }
        rb_paths_free(dir);
    }
    rb_paths_free(NULL); /* must not crash */
}

/* --------------------------------- rb_json -------------------------------- */

static void test_rb_json(void)
{
    size_t i;
    char *s;
    long long n;
    int b;

    /* a plain string */
    i = 0;
    CHECK(rb_json_parse_string("\"hi\"", &i, &s) == 1);
    CHECK(STREQ(s, "hi"));
    CHECK(i == 4);
    free(s);

    /* the short escapes */
    i = 0;
    CHECK(rb_json_parse_string("\"a\\\"b\\\\c\\nd\\te\"", &i, &s) == 1);
    CHECK(STREQ(s, "a\"b\\c\nd\te"));
    free(s);

    /* \u becomes UTF-8: U+00E9 is C3 A9 */
    i = 0;
    CHECK(rb_json_parse_string("\"\\u00e9\"", &i, &s) == 1);
    CHECK(STREQ(s, "\xC3\xA9"));
    CHECK(i == 8);
    free(s);

    /* a lone surrogate becomes U+FFFD (EF BF BD), never invalid UTF-8 */
    i = 0;
    CHECK(rb_json_parse_string("\"\\ud800\"", &i, &s) == 1);
    CHECK(STREQ(s, "\xEF\xBF\xBD"));
    free(s);

    /* out-of-range code points too */
    i = 0;
    CHECK(rb_json_parse_string("\"\\uffff\"", &i, &s) == 1);
    CHECK(STREQ(s, "\xEF\xBF\xBF"));
    free(s);

    /* an empty string yields "" and never NULL */
    i = 0;
    CHECK(rb_json_parse_string("\"\"", &i, &s) == 1);
    CHECK(s != NULL);
    CHECK(s[0] == '\0');
    free(s);

    /* failure: not a string at all, and an unterminated one */
    i = 0;
    s = (char *)0x1;
    CHECK(rb_json_parse_string("nope", &i, &s) == 0);
    CHECK(s == NULL);
    i = 0;
    CHECK(rb_json_parse_string("\"unterminated", &i, &s) == 0);
    CHECK(s == NULL);
    i = 0;
    CHECK(rb_json_parse_string("\"bad\\qescape\"", &i, &s) == 0);
    CHECK(s == NULL);

    /* numbers: sign, offset, and the empty/garbage cases */
    i = 4; /* points at the '-' in "xxxx-42zzz" */
    CHECK(rb_json_parse_number("xxxx-42zzz", &i, &n) == 1);
    CHECK(n == -42);
    CHECK(i == 7);
    i = 0;
    CHECK(rb_json_parse_number("+7", &i, &n) == 1);
    CHECK(n == 7);
    i = 0;
    CHECK(rb_json_parse_number("", &i, &n) == 0);
    i = 0;
    CHECK(rb_json_parse_number("abc", &i, &n) == 0);

    /* booleans, both spellings */
    i = 0;
    CHECK(rb_json_parse_bool("true", &i, &b) == 1 && b == 1);
    i = 0;
    CHECK(rb_json_parse_bool("false", &i, &b) == 1 && b == 0);
    i = 0;
    CHECK(rb_json_parse_bool("1", &i, &b) == 1 && b == 1);
    i = 0;
    CHECK(rb_json_parse_bool("0", &i, &b) == 1 && b == 0);
    i = 0;
    CHECK(rb_json_parse_bool("maybe", &i, &b) == 0);

    /* find_key: hits, misses, and the "prefix of another key" trap */
    {
        const char *line = "{\"a\":1,\"name\":\"x\"}";
        size_t pos = 0;

        CHECK(rb_json_find_key(line, "name", &pos) == 1);
        i = pos;
        CHECK(rb_json_parse_string(line, &i, &s) == 1);
        CHECK(STREQ(s, "x"));
        free(s);

        pos = 0;
        CHECK(rb_json_find_key(line, "missing", &pos) == 0);
        pos = 0;
        CHECK(rb_json_find_key("{\"names\":1}", "name", &pos) == 0);
        pos = 0;
        CHECK(rb_json_find_key(NULL, "name", &pos) == 0);
    }

    /* escape() output must parse back to exactly the original bytes */
    {
        const char *raw = "he said \"hi\"\n\\done\t\x01";
        char *esc = rb_json_escape(raw);
        rb_str b;
        size_t k = 0;

        CHECK(strstr(esc, "\\u0001") != NULL); /* control char is escaped */
        rb_str_init(&b);
        rb_str_append(&b, "\"");
        rb_str_append(&b, esc);
        rb_str_append(&b, "\"");
        CHECK(rb_json_parse_string(b.data, &k, &s) == 1);
        CHECK(STREQ(s, raw));
        free(s);
        rb_str_free(&b);
        free(esc);
    }

    /* escape(NULL) is "" and strdup is NULL-safe */
    {
        char *e = rb_json_escape(NULL);
        char *d = rb_json_strdup(NULL);
        CHECK(e != NULL && e[0] == '\0');
        CHECK(d != NULL && d[0] == '\0');
        free(e);
        free(d);
    }
}

/* -------------------------------- rb_search ------------------------------- */

static void test_rb_search(void)
{
    int i;
    char *u;

    CHECK(rb_search_count() >= 6);
    CHECK(rb_search_default() != NULL);
    CHECK(STREQ(rb_search_default()->id, "duckduckgo"));

    CHECK(rb_search_at(-1) == NULL);
    CHECK(rb_search_at(rb_search_count()) == NULL);

    /* ids resolve case-insensitively */
    CHECK(rb_search_by_id("GOOGLE") != NULL);
    CHECK(STREQ(rb_search_by_id("GOOGLE")->id, "google"));
    CHECK(rb_search_by_id("definitely-not-an-engine") == NULL);

    /* an unknown or NULL id falls back to the default rather than NULL */
    CHECK(rb_search_resolve("nope") == rb_search_default());
    CHECK(rb_search_resolve(NULL) == rb_search_default());

    /* every registered engine must be usable */
    for (i = 0; i < rb_search_count(); i++) {
        const rb_search_engine *e = rb_search_at(i);

        CHECK(e != NULL);
        CHECK(e->id != NULL && e->id[0] != '\0');
        CHECK(e->label != NULL && e->label[0] != '\0');
        CHECK(e->search_template != NULL);
        CHECK(strstr(e->search_template, "{query}") != NULL);
        CHECK(rb_search_by_id(e->id) == e);
    }

    /* URL construction: the query is percent-encoded, the rest is literal */
    u = rb_search_url("duckduckgo", "hello world");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=hello%20world"));
    free(u);

    u = rb_search_url("google", "a&b=c");
    CHECK(STREQ(u, "https://www.google.com/search?q=a%26b%3Dc"));
    free(u);

    u = rb_search_url("no-such-engine", "x");
    CHECK(STREQ(u, "https://duckduckgo.com/?q=x"));
    free(u);

    /* the encoder leaves the unreserved set alone */
    u = rb_search_encode("a-b_c.d~e f");
    CHECK(STREQ(u, "a-b_c.d~e%20f"));
    free(u);
    u = rb_search_encode("  padded  ");
    CHECK(STREQ(u, "padded")); /* surrounding whitespace is trimmed */
    free(u);
    u = rb_search_encode(NULL);
    CHECK(u != NULL && u[0] == '\0');
    free(u);

    /* expand() replaces every placeholder, not just the first */
    u = rb_search_expand("x{query}y{query}z", "q");
    CHECK(STREQ(u, "xqyqz"));
    free(u);
    u = rb_search_expand("no-placeholder", "q");
    CHECK(STREQ(u, "no-placeholder"));
    free(u);

    /* suggestions exist exactly for the engines that declare an endpoint,
     * and never for an empty query */
    for (i = 0; i < rb_search_count(); i++) {
        const rb_search_engine *e = rb_search_at(i);
        char *sug = rb_search_suggest_url(e->id, "abc");

        if (e->suggest_template != NULL) {
            CHECK(sug != NULL);
            CHECK(strstr(sug, "abc") != NULL);
        } else {
            CHECK(sug == NULL);
        }
        free(sug);
        CHECK(rb_search_suggest_url(e->id, "") == NULL);
    }
}

/* ---------------------------------- rb_ua --------------------------------- */

static void test_rb_ua(void)
{
    const rb_ua_preset *p;
    char *eff;
    int i;
    int desktop_presets = 0;

    CHECK(rb_ua_count() >= 13);
    CHECK(rb_ua_at(-1) == NULL);
    CHECK(rb_ua_at(rb_ua_count()) == NULL);
    CHECK(rb_ua_by_id("no-such-preset") == NULL);

    /* ids are unique and resolvable, and desktop presets outnumber nothing */
    for (i = 0; i < rb_ua_count(); i++) {
        p = rb_ua_at(i);
        CHECK(p != NULL);
        CHECK(p->id != NULL && p->id[0] != '\0');
        CHECK(p->label != NULL && p->label[0] != '\0');
        CHECK(p->value != NULL);
        CHECK(rb_ua_by_id(p->id) == p);
        if (p->is_desktop) {
            desktop_presets++;
        }
    }
    CHECK(desktop_presets > 0);

    /* the randomized pick must be a real desktop preset: a desktop browser
     * introducing itself as a phone is exactly the fingerprint the Android
     * edition's randomization exists to avoid */
    {
        const char *rid = rb_ua_random_preset_id();
        const rb_ua_preset *rp = rb_ua_by_id(rid);

        CHECK(rid != NULL);
        CHECK(rp != NULL);
        CHECK(rp->is_desktop == 1);

        /* it must vary: 64 draws should not all be the same preset */
        {
            int same = 1;
            for (i = 0; i < 64; i++) {
                if (strcmp(rb_ua_random_preset_id(), rid) != 0) {
                    same = 0;
                    break;
                }
            }
            CHECK(same == 0);
        }
    }

    /* mode DEFAULT sends no override at all */
    CHECK(rb_ua_effective(RB_UA_MODE_DEFAULT, "chrome_windows", "x") == NULL);

    /* a preset with a real value overrides */
    eff = rb_ua_effective(RB_UA_MODE_PRESET, "chrome_windows", NULL);
    CHECK(eff != NULL);
    CHECK(strstr(eff, "Mozilla/5.0") == eff);
    free(eff);

    /* the "engine default" preset carries an empty value -> no override */
    CHECK(rb_ua_effective(RB_UA_MODE_PRESET, "webview", NULL) == NULL);

    /* an unknown preset id is not an override either */
    CHECK(rb_ua_effective(RB_UA_MODE_PRESET, "nope", NULL) == NULL);
    CHECK(rb_ua_effective(RB_UA_MODE_PRESET, NULL, NULL) == NULL);

    /* custom wins, but a blank custom string does not */
    eff = rb_ua_effective(RB_UA_MODE_CUSTOM, NULL, "MyAgent/1.0");
    CHECK(eff != NULL && STREQ(eff, "MyAgent/1.0"));
    free(eff);
    CHECK(rb_ua_effective(RB_UA_MODE_CUSTOM, NULL, "   ") == NULL);
    CHECK(rb_ua_effective(RB_UA_MODE_CUSTOM, NULL, NULL) == NULL);
}

/* --------------------------------- rb_theme ------------------------------- */

static void test_rb_theme(void)
{
    int i;
    int dark_themes = 0;

    CHECK(rb_theme_count() == 18); /* the Android edition's built-in set */
    CHECK(rb_theme_at(-1) == NULL);
    CHECK(rb_theme_at(rb_theme_count()) == NULL);
    CHECK(rb_theme_default() != NULL);
    CHECK(STREQ(rb_theme_default()->id, "obsidian"));
    CHECK(rb_theme_by_id("no-such-theme") == NULL);

    /* an unknown id resolves to the default rather than NULL */
    CHECK(rb_theme_resolve("no-such-theme") == rb_theme_default());
    CHECK(rb_theme_resolve(NULL) == rb_theme_default());

    for (i = 0; i < rb_theme_count(); i++) {
        const rb_theme *t = rb_theme_at(i);
        rb_theme_colors c;

        CHECK(t != NULL);
        CHECK(t->id != NULL && t->id[0] != '\0');
        CHECK(t->name != NULL && t->name[0] != '\0');
        CHECK(rb_theme_by_id(t->id) == t);
        /* the tunables are clamped into the ranges the studio UI offers */
        CHECK(t->corner_radius >= 4 && t->corner_radius <= 32);
        CHECK(t->transparency >= 0 && t->transparency <= 90);
        CHECK(t->blur >= 0 && t->blur <= 100);
        CHECK(t->contrast >= 50 && t->contrast <= 150);

        if (t->dark.background != t->light.background) {
            dark_themes++;
        }

        /* both palettes are fully populated and every token is addressable */
        c = rb_theme_palette(t, RB_THEME_DARK, 0);
        CHECK(((c.background >> 24) & 0xFFu) == 0xFFu); /* opaque */
        CHECK(rb_theme_color_at(&c, RB_TOKEN_BACKGROUND) == c.background);
        CHECK(rb_theme_color_at(&c, RB_TOKEN_SELECTION) == c.selection);
        CHECK(rb_theme_color_at(&c, RB_TOKEN_COUNT) == 0);
        CHECK(rb_theme_color_at(&c, -1) == 0);
    }
    CHECK(dark_themes > 0); /* the light and dark palettes really differ */

    /* LIGHT/DARK pick their own palette regardless of the system */
    {
        const rb_theme *t = rb_theme_default();
        rb_theme_colors dark = rb_theme_palette(t, RB_THEME_DARK, 0);
        rb_theme_colors light = rb_theme_palette(t, RB_THEME_LIGHT, 1);

        CHECK(dark.background == t->dark.background);
        CHECK(light.background == t->light.background);
    }

    /* AUTO follows the system flag, in both directions */
    {
        const rb_theme *t = rb_theme_default();

        CHECK(rb_theme_palette(t, RB_THEME_AUTO, 1).background ==
              t->dark.background);
        CHECK(rb_theme_palette(t, RB_THEME_AUTO, 0).background ==
              t->light.background);
    }

    /* AMOLED is true black on the background and chrome, accents untouched */
    {
        const rb_theme *t = rb_theme_default();
        rb_theme_colors a = rb_theme_palette(t, RB_THEME_AMOLED, 0);

        CHECK(a.background == 0xFF000000u);
        CHECK(a.address_bar == 0xFF000000u);
        CHECK(a.tab_bar == 0xFF000000u);
        CHECK(a.nav_bar == 0xFF000000u);
        CHECK(a.primary == t->dark.primary);
    }

    /* High contrast is the desktop "High contrast" switch.  It has to hold for
     * every built-in theme, because a user turning it on is asking for
     * legibility and cannot be told "not for this palette". */
    {
        for (i = 0; i < rb_theme_count(); i++) {
            const rb_theme *t = rb_theme_at(i);
            rb_theme_colors dark, light, hc;

            CHECK(t != NULL);
            /* a dark palette goes to true black chrome with white text... */
            dark = rb_theme_palette(t, RB_THEME_DARK, 1);
            hc = rb_theme_high_contrast(dark);
            CHECK(hc.background == 0xFF000000u);
            CHECK(hc.surface == 0xFF000000u);
            CHECK(hc.address_bar == 0xFF000000u);
            CHECK(hc.tab_bar == 0xFF000000u);
            CHECK(hc.nav_bar == 0xFF000000u);
            CHECK(hc.text_primary == 0xFFFFFFFFu);
            CHECK(hc.icon == 0xFFFFFFFFu);
            /* the accent survives: it is how a profile's chrome is told
             * apart, and the AMOLED override keeps it for the same reason */
            CHECK(hc.primary == dark.primary);
            CHECK(hc.secondary == dark.secondary);
            CHECK(hc.button == dark.button);

            /* ...and a light one to true white with black text */
            light = rb_theme_palette(t, RB_THEME_LIGHT, 0);
            hc = rb_theme_high_contrast(light);
            CHECK(hc.background == 0xFFFFFFFFu);
            CHECK(hc.tab_bar == 0xFFFFFFFFu);
            CHECK(hc.text_primary == 0xFF000000u);
            CHECK(hc.primary == light.primary);

            /* the property that matters: whatever came in, text and the
             * surface it sits on are now at opposite ends */
            CHECK(rb_theme_high_contrast(dark).text_primary !=
                  rb_theme_high_contrast(dark).background);
            CHECK(rb_theme_high_contrast(light).text_primary !=
                  rb_theme_high_contrast(light).background);
        }

        /* The direction follows the palette it is handed, not the mode it came
         * from: an AMOLED palette is already black, so it stays black. */
        {
            const rb_theme *t = rb_theme_default();
            rb_theme_colors a = rb_theme_palette(t, RB_THEME_AMOLED, 0);

            CHECK(rb_theme_high_contrast(a).background == 0xFF000000u);
            CHECK(rb_theme_high_contrast(a).text_primary == 0xFFFFFFFFu);
        }
    }

    /* hex formatting drops alpha and is exactly 7 bytes + NUL */
    {
        char hex[8];

        rb_theme_hex(0xFF6750A4u, hex);
        CHECK(STREQ(hex, "#6750A4"));
        rb_theme_hex(0xFF000000u, hex);
        CHECK(STREQ(hex, "#000000"));
    }

    /* rgb splitting */
    {
        int r = -1, g = -1, b = -1;

        rb_theme_rgb(0xFF6750A4u, &r, &g, &b);
        CHECK(r == 0x67 && g == 0x50 && b == 0xA4);
        rb_theme_rgb(0xFF6750A4u, NULL, NULL, NULL); /* must not crash */
    }

    /* token vocabulary: the names are the camelCase keys the Android
     * ThemeSpec serialises, so a theme snapshot moves between editions */
    CHECK(rb_theme_token_count() == RB_TOKEN_COUNT);
    CHECK(STREQ(rb_theme_token_name(RB_TOKEN_BACKGROUND), "background"));
    CHECK(STREQ(rb_theme_token_name(RB_TOKEN_ADDRESS_BAR), "addressBar"));
    CHECK(STREQ(rb_theme_token_name(RB_TOKEN_SELECTION), "selection"));
    CHECK(rb_theme_token_name(-1) == NULL);
    CHECK(rb_theme_token_name(RB_TOKEN_COUNT) == NULL);
    CHECK(STREQ(rb_theme_mode_name(RB_THEME_LIGHT), "Light"));
    CHECK(STREQ(rb_theme_mode_name(RB_THEME_DARK), "Dark"));
    CHECK(STREQ(rb_theme_mode_name(RB_THEME_AMOLED), "AMOLED"));
    CHECK(STREQ(rb_theme_mode_name(RB_THEME_AUTO), "Auto"));

    /* every token has a distinct, non-empty name */
    {
        int seen[RB_TOKEN_COUNT];
        int i;

        for (i = 0; i < RB_TOKEN_COUNT; i++) {
            const char *n = rb_theme_token_name(i);
            int j;

            CHECK(n != NULL && n[0] != '\0');
            for (j = 0; j < i; j++) {
                CHECK(strcmp(rb_theme_token_name(j), n) != 0);
            }
            seen[i] = 1;
        }
        CHECK(seen[0] == 1);
    }
}

/* -------------------------------- rb_filters ------------------------------ */

static void test_rb_filters(void)
{
    rb_filters *f = rb_filters_new();
    rb_filter_options opts = rb_filter_options_default();
    const char *reason = NULL;
    int loaded;

    /* the compatibility-first posture: only malicious blocking is on */
    CHECK(opts.block_ads == 0);
    CHECK(opts.block_trackers == 0);
    CHECK(opts.block_cross_site == 0);
    CHECK(opts.block_popups == 0);
    CHECK(opts.block_malicious == 1);

    loaded = rb_filters_load_builtin(f);
    CHECK(loaded > 0);
    CHECK(rb_filters_host_count(f) == loaded);

    /* the compiled-in list must match the file the Android edition ships */
    {
        int lines = 0;
        int data_lines = 0;
        int i;

        CHECK(RB_FILTERLIST_LINES[0] != NULL);
        for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
            const char *l = RB_FILTERLIST_LINES[i];
            lines++;
            if (l[0] != '\0' && l[0] != '#') {
                CHECK(strchr(l, '|') != NULL); /* "<category>|<host>" */
                data_lines++;
            }
        }
        CHECK(lines > 0);
        CHECK(data_lines == loaded);
    }

    /* the default policy blocks nothing on a clean host ... */
    CHECK(rb_filters_decide(f, "example.com", NULL, "/",
                            &opts, &reason) == RB_FILTER_NONE);
    CHECK(rb_filters_blocked_category(f, "example.com") == RB_FILTER_NONE);

    /* ... and nothing at all when the options are NULL (defaults) */
    CHECK(rb_filters_decide(f, "example.com", NULL, "/", NULL, NULL) ==
          RB_FILTER_NONE);

    /* turning the shields on must actually change the verdict */
    opts.block_ads = 1;
    opts.block_trackers = 1;
    {
        /* find one host of each category in the compiled list and check the
         * engine agrees with the list it was loaded from */
        int i;
        int saw_ad = 0;
        int saw_tracker = 0;
        int saw_malicious = 0;

        for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
            const char *l = RB_FILTERLIST_LINES[i];
            const char *bar;
            char cat[32];
            size_t clen;
            const char *host;

            if (l[0] == '\0' || l[0] == '#') {
                continue;
            }
            bar = strchr(l, '|');
            if (bar == NULL) {
                continue;
            }
            clen = (size_t)(bar - l);
            if (clen >= sizeof(cat)) {
                continue;
            }
            memcpy(cat, l, clen);
            cat[clen] = '\0';
            host = bar + 1;

            if (strcmp(cat, "ad") == 0 && !saw_ad) {
                saw_ad = 1;
                CHECK(rb_filters_decide(f, host, NULL, "/", &opts, &reason) ==
                      RB_FILTER_AD);
                CHECK(reason != NULL && reason[0] != '\0');
            } else if (strcmp(cat, "tracker") == 0 && !saw_tracker) {
                saw_tracker = 1;
                CHECK(rb_filters_decide(f, host, NULL, "/", &opts, &reason) ==
                      RB_FILTER_TRACKER);
            } else if (strcmp(cat, "malicious") == 0 && !saw_malicious) {
                rb_filter_options compat = rb_filter_options_default();

                saw_malicious = 1;
                /* malicious blocking is on even with the shields off */
                CHECK(rb_filters_blocked_category(f, host) ==
                      RB_FILTER_MALICIOUS);
                CHECK(rb_filters_decide(f, host, NULL, "/", &compat, NULL) ==
                      RB_FILTER_MALICIOUS);
            }
            if (saw_ad && saw_tracker && saw_malicious) {
                break;
            }
        }
        CHECK(saw_ad && saw_tracker && saw_malicious);
    }

    /* a subdomain of a blocked host is blocked too (suffix matching), but a
     * host that merely ENDS with the same letters is not */
    {
        int i;
        for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
            const char *l = RB_FILTERLIST_LINES[i];
            const char *bar;
            const char *host;
            char *sub;

            if (l[0] == '\0' || l[0] == '#' || strncmp(l, "ad|", 3) != 0) {
                continue;
            }
            bar = strchr(l, '|');
            host = bar + 1;
            if (strchr(host, '.') == NULL) {
                continue; /* only meaningful for dotted hosts */
            }
            sub = (char *)malloc(strlen(host) + 8);
            CHECK(sub != NULL);
            sprintf(sub, "cdn.%s", host);
            CHECK(rb_filters_decide(f, sub, NULL, "/", &opts, NULL) ==
                  RB_FILTER_AD);
            free(sub);

            /* "not<host>" is a different registrable domain */
            sub = (char *)malloc(strlen(host) + 8);
            CHECK(sub != NULL);
            sprintf(sub, "not%s", host);
            CHECK(rb_filters_decide(f, sub, NULL, "/", &opts, NULL) !=
                  RB_FILTER_AD);
            free(sub);
            break;
        }
    }

    /* Tracker blocking and cross-site blocking interact in a way that is
     * easy to get backwards, so pin it down: with trackers on but cross-site
     * off, only a SAME-SITE (first-party) tracker request is blocked — a
     * third-party one is allowed, which is what keeps embedded players and
     * third-party logins working. Turning cross-site on adds the third-party
     * case, reported with its own category so the dashboard can tell them
     * apart. */
    {
        int i;
        for (i = 0; RB_FILTERLIST_LINES[i] != NULL; i++) {
            const char *l = RB_FILTERLIST_LINES[i];
            const char *bar;
            const char *host;
            rb_filter_options o = rb_filter_options_default();

            if (l[0] == '\0' || l[0] == '#' ||
                strncmp(l, "tracker|", 8) != 0) {
                continue;
            }
            bar = strchr(l, '|');
            host = bar + 1;

            o.block_trackers = 1;
            o.block_cross_site = 0;
            CHECK(rb_filters_decide(f, host, host, "/", &o, NULL) ==
                  RB_FILTER_TRACKER);
            CHECK(rb_filters_decide(f, host, "publisher.example", "/", &o,
                                    NULL) == RB_FILTER_NONE);
            /* a first-party (main-frame) request has no page host */
            CHECK(rb_filters_decide(f, host, NULL, "/", &o, NULL) ==
                  RB_FILTER_TRACKER);

            o.block_cross_site = 1;
            CHECK(rb_filters_decide(f, host, "publisher.example", "/", &o,
                                    NULL) == RB_FILTER_CROSS_SITE_TRACKER);
            CHECK(rb_filters_decide(f, host, host, "/", &o, NULL) ==
                  RB_FILTER_TRACKER);

            /* trackers off: the cross-site switch alone blocks nothing */
            o.block_trackers = 0;
            CHECK(rb_filters_decide(f, host, "publisher.example", "/", &o,
                                    NULL) == RB_FILTER_NONE);
            break;
        }
    }

    /* text loading: blank lines and comments are skipped, junk is ignored */
    {
        rb_filters *g = rb_filters_new();
        int n = rb_filters_load_text(g,
                                     "# a comment\n"
                                     "\n"
                                     "ad|ads.example\n"
                                     "tracker|t.example\n"
                                     "malicious|bad.example\n"
                                     "bogus|ignored.example\n"
                                     "no-pipe-line\n");

        CHECK(n == 3);
        CHECK(rb_filters_host_count(g) == 3);
        CHECK(rb_filters_blocked_category(g, "bad.example") ==
              RB_FILTER_MALICIOUS);
        CHECK(rb_filters_blocked_category(g, "ignored.example") ==
              RB_FILTER_NONE);
        CHECK(rb_filters_load_text(g, NULL) == 0);
        rb_filters_free(g);
    }

    /* statistics only count what was actually blocked */
    {
        rb_filter_options o = rb_filter_options_default();
        rb_filters_reset_stats(f);
        CHECK(rb_filters_stat_total(f) == 0);
        (void)rb_filters_decide(f, "example.com", NULL, "/", &o, NULL);
        CHECK(rb_filters_stat_total(f) == 0); /* allowed: nothing counted */
        rb_filters_count_block(f, RB_FILTER_AD);
        rb_filters_count_block(f, RB_FILTER_AD);
        rb_filters_count_block(f, RB_FILTER_MALICIOUS);
        CHECK(rb_filters_stat_blocked(f, RB_FILTER_AD) == 2);
        CHECK(rb_filters_stat_blocked(f, RB_FILTER_MALICIOUS) == 1);
        CHECK(rb_filters_stat_blocked(f, RB_FILTER_TRACKER) == 0);
        CHECK(rb_filters_stat_total(f) == 3);
        rb_filters_reset_stats(f);
        CHECK(rb_filters_stat_total(f) == 0);
    }

    /* category names are stable (the privacy dashboard shows them) */
    CHECK(STREQ(rb_filter_category_name(RB_FILTER_AD), "Ads"));
    CHECK(rb_filter_category_name(RB_FILTER_NONE) != NULL);

    /* suspicious-site signals: the three heuristics, in the Kotlin order */
    {
        char *text;

        CHECK(rb_filters_suspicious_signals("https://example.com/") ==
              RB_SUSPICIOUS_NONE);
        CHECK(rb_filters_suspicious_signals(NULL) == RB_SUSPICIOUS_NONE);
        CHECK(rb_filters_suspicious_signals("") == RB_SUSPICIOUS_NONE);

        CHECK(rb_filters_suspicious_signals("http://example.com/") ==
              RB_SUSPICIOUS_INSECURE_HTTP);
        /* https is not insecure, so the scheme test is on http alone */
        CHECK((rb_filters_suspicious_signals("https://example.com/") &
               RB_SUSPICIOUS_INSECURE_HTTP) == 0);

        CHECK(rb_filters_suspicious_signals("http://127.0.0.1/x") ==
              (RB_SUSPICIOUS_INSECURE_HTTP | RB_SUSPICIOUS_IP_HOST));
        CHECK(rb_filters_suspicious_signals("https://10.0.0.1/") ==
              RB_SUSPICIOUS_IP_HOST);
        /* Kotlin's octet test is 1-3 digits and is NOT range-checked, so an
         * out-of-range address still counts. Mirroring that is the point. */
        CHECK(rb_filters_suspicious_signals("https://999.999.999.999/") ==
              RB_SUSPICIOUS_IP_HOST);
        /* four octets are required: three, or a trailing letter, is not one */
        CHECK(rb_filters_suspicious_signals("https://1.2.3/") ==
              RB_SUSPICIOUS_NONE);
        CHECK(rb_filters_suspicious_signals("https://1.2.3.4a/") ==
              RB_SUSPICIOUS_IP_HOST); /* nothing anchors the end, as in Kotlin */
        CHECK(rb_filters_suspicious_signals("https://1234.5.6.7/") ==
              RB_SUSPICIOUS_NONE);
        CHECK(rb_filters_suspicious_signals("https://1.2.3.4.example.com/") ==
              RB_SUSPICIOUS_IP_HOST);

        CHECK(rb_filters_suspicious_signals("https://xn--80ak6aa92e.com/") ==
              RB_SUSPICIOUS_PUNYCODE);
        /* uppercase is folded first, exactly as Kotlin lowercases the URL */
        CHECK(rb_filters_suspicious_signals("https://XN--80AK6AA92E.com/") ==
              RB_SUSPICIOUS_PUNYCODE);

        /* the IP test is anchored at the start exactly as Kotlin's ^ is, so
         * an address that is not the first thing after the scheme is not a
         * signal at all */
        CHECK(rb_filters_suspicious_signals("http://xn--a.1.2.3.4/") ==
              (RB_SUSPICIOUS_INSECURE_HTTP | RB_SUSPICIOUS_PUNYCODE));
        CHECK(rb_filters_suspicious_signals("http://1.2.3.4/xn--a/") ==
              (RB_SUSPICIOUS_INSECURE_HTTP | RB_SUSPICIOUS_IP_HOST |
               RB_SUSPICIOUS_PUNYCODE));

        CHECK(STREQ(rb_suspicious_signal_name(RB_SUSPICIOUS_INSECURE_HTTP),
                    "insecure http connection"));
        CHECK(rb_suspicious_signal_name(RB_SUSPICIOUS_NONE) == NULL);

        text = rb_filters_suspicious_text(RB_SUSPICIOUS_INSECURE_HTTP |
                                          RB_SUSPICIOUS_PUNYCODE);
        CHECK(text != NULL &&
              STREQ(text, "insecure http connection, "
                          "punycode domain (possible homograph)"));
        free(text);
        text = rb_filters_suspicious_text(RB_SUSPICIOUS_NONE);
        CHECK(text != NULL && STREQ(text, ""));
        free(text);
        /* the join order is the declaration order, not the order the bits
         * happen to be passed in */
        text = rb_filters_suspicious_text(RB_SUSPICIOUS_PUNYCODE |
                                          RB_SUSPICIOUS_INSECURE_HTTP |
                                          RB_SUSPICIOUS_IP_HOST);
        CHECK(text != NULL &&
              STREQ(text, "insecure http connection, "
                          "IP address used instead of a domain name, "
                          "punycode domain (possible homograph)"));
        free(text);
    }

    /* NULL-safety: every entry point survives a NULL engine */
    CHECK(rb_filters_decide(NULL, "example.com", NULL, "/", NULL, NULL) ==
          RB_FILTER_NONE);
    CHECK(rb_filters_blocked_category(NULL, "example.com") == RB_FILTER_NONE);
    CHECK(rb_filters_host_count(NULL) == 0);
    CHECK(rb_filters_stat_total(NULL) == 0);
    rb_filters_free(NULL);

    rb_filters_free(f);
}

/* --------------------------------- rb_prefs ------------------------------- */

static void test_rb_prefs(void)
{
    rb_settings *s = rb_settings_new();

    rb_prefs_profile_defaults(s);

    /* the compatibility-first posture, exactly as the Android edition ships */
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_ADS, -1) == 0);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_TRACKERS, -1) == 0);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_CROSS_SITE, -1) == 0);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_POPUPS, -1) == 0);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_MALICIOUS, -1) == 1);
    CHECK(rb_settings_get_int(s, RB_PREF_HTTPS_UPGRADE, -1) == 1);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_THIRD_PARTY_COOKIES, -1) == 0);
    CHECK(rb_settings_get_int(s, RB_PREF_BLOCK_MIXED_CONTENT, -1) == 0);

    /* JavaScript is NEVER off by default — project-wide policy */
    CHECK(rb_settings_get_int(s, RB_PREF_JAVASCRIPT, -1) == 1);

    /* appearance + search */
    CHECK(STREQ(rb_settings_get(s, RB_PREF_THEME, ""), "system"));
    CHECK(STREQ(rb_settings_get(s, RB_PREF_SEARCH_ENGINE, ""), "duckduckgo"));
    CHECK(rb_settings_get_int(s, RB_PREF_FONT_SCALE, -1) == 100);
    CHECK(STREQ(rb_settings_get(s, RB_PREF_TAB_LAYOUT, ""), "grid"));

    /* every homepage shortcut must be a usable URL */
    {
        const char *list = rb_settings_get(s, RB_PREF_HOMEPAGE_SHORTCUTS, "");
        const char *p = list;
        int n = 0;

        while (*p != '\0') {
            const char *nl = strchr(p, '\n');
            size_t len = (nl != NULL) ? (size_t)(nl - p) : strlen(p);

            CHECK(len > 8);
            CHECK(strncmp(p, "https://", 8) == 0);
            n++;
            if (nl == NULL) {
                break;
            }
            p = nl + 1;
        }
        CHECK(n == 4);
    }

    /* the search_engine default must name a real engine */
    CHECK(rb_search_by_id(rb_settings_get(s, RB_PREF_SEARCH_ENGINE, "")) !=
          NULL);

    /* the WebRTC default is the middle setting, not "disabled" */
    CHECK(STREQ(rb_settings_get(s, RB_PREF_WEBRTC_POLICY, ""),
                "restrict_local_ip"));

    /* global settings: nothing is sent anywhere unless asked */
    {
        rb_settings *g = rb_settings_new();
        rb_prefs_global_defaults(g);
        CHECK(rb_settings_get_int(g, RB_GPREF_TELEMETRY, -1) == 0);
        CHECK(rb_settings_get_int(g, RB_GPREF_DIAGNOSTICS, -1) == 0);
        CHECK(rb_settings_get_int(g, RB_GPREF_RETENTION, -1) == 30);
        CHECK(rb_settings_get_int(g, RB_GPREF_NET_PROTECT_ENABLED, -1) == 1);
        rb_settings_free(g);
    }

    /* the defaults survive a save/load round trip byte for byte */
    {
        rb_settings *back = rb_settings_new();
        int i;

        CHECK(rb_settings_save(s, TMP) == 0);
        CHECK(rb_settings_load(back, TMP) == 0);
        CHECK(rb_settings_count(back) == rb_settings_count(s));
        for (i = 0; i < rb_settings_count(s); i++) {
            const char *k = rb_settings_key_at(s, i);
            CHECK(k != NULL);
            CHECK(STREQ(rb_settings_get(back, k, "\x01"),
                        rb_settings_value_at(s, i)));
        }
        CHECK(rb_settings_key_at(s, -1) == NULL);
        CHECK(rb_settings_key_at(s, rb_settings_count(s)) == NULL);
        CHECK(rb_settings_value_at(s, -1) == NULL);
        CHECK(rb_settings_count(NULL) == 0);
        rb_settings_free(back);
    }

    rb_settings_free(s);
    rb_prefs_profile_defaults(NULL); /* must not crash */
    rb_prefs_global_defaults(NULL);

    /* the font scale the desktop editions render with */
    {
        rb_settings *f = rb_settings_new();

        /* the default is the system size, which must mean "unchanged" — a
         * profile that never touched the setting renders as it always did */
        CHECK(rb_font_scale_percent(NULL) == RB_FONT_SCALE_DEFAULT);
        rb_prefs_profile_defaults(f);
        CHECK(rb_font_scale_percent(f) == 100);

        /* an explicit in-range scale survives verbatim */
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, 125);
        CHECK(rb_font_scale_percent(f) == 125);
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, RB_FONT_SCALE_MIN);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_MIN);
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, RB_FONT_SCALE_MAX);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_MAX);

        /* beyond the range it is clamped, not honoured: a hand-edited file
         * must not be able to ask for a 10x chrome */
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, 1000);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_MAX);
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, 51);
        CHECK(rb_font_scale_percent(f) == 51);
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, 49);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_MIN);

        /* zero or less is an unset/corrupt key, not a request for the
         * smallest size, so it reads as the default */
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, 0);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_DEFAULT);
        rb_settings_set_int(f, RB_PREF_FONT_SCALE, -80);
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_DEFAULT);

        /* a value that is not a number at all reads as the default too */
        rb_settings_set(f, RB_PREF_FONT_SCALE, "huge");
        CHECK(rb_font_scale_percent(f) == RB_FONT_SCALE_DEFAULT);

        /* the WebRTC policy.  Each GUI can express a different amount of it,
         * so the parse is the one part they share. */
        CHECK(rb_webrtc_policy_of(NULL) == RB_WEBRTC_RESTRICT_LOCAL_IP);
        rb_prefs_profile_defaults(f);
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_RESTRICT_LOCAL_IP);

        rb_settings_set(f, RB_PREF_WEBRTC_POLICY, "default");
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_DEFAULT);
        rb_settings_set(f, RB_PREF_WEBRTC_POLICY, "disabled");
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_DISABLED);
        rb_settings_set(f, RB_PREF_WEBRTC_POLICY, "restrict_local_ip");
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_RESTRICT_LOCAL_IP);

        /* a privacy switch whose value cannot be understood must not fall
         * back to the permissive setting */
        rb_settings_set(f, RB_PREF_WEBRTC_POLICY, "Disabled");
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_RESTRICT_LOCAL_IP);
        rb_settings_set(f, RB_PREF_WEBRTC_POLICY, "");
        CHECK(rb_webrtc_policy_of(f) == RB_WEBRTC_RESTRICT_LOCAL_IP);
        {
            /* a store that never had the key at all — a settings file written
             * before this switch existed */
            rb_settings *bare = rb_settings_new();
            CHECK(rb_webrtc_policy_of(bare) == RB_WEBRTC_RESTRICT_LOCAL_IP);
            rb_settings_free(bare);
        }

        /* the screen claim. Both spellings of "no claim" land in the same
         * place: a profile not in manual mode, and a profile in manual mode
         * whose stored pair cannot be a screen. */
        {
            int w = -1, h = -1;
            CHECK(rb_screen_claim_of(NULL, &w, &h) == 0);
            CHECK(w == 0 && h == 0);

            rb_prefs_profile_defaults(f);
            w = -1; h = -1;
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);
            CHECK(w == 0 && h == 0);

            rb_settings_set(f, RB_PREF_SCREEN_SIZE, "manual");
            rb_settings_set_int(f, RB_PREF_SCREEN_WIDTH, 1920);
            rb_settings_set_int(f, RB_PREF_SCREEN_HEIGHT, 1080);
            CHECK(rb_screen_claim_of(f, &w, &h) == 1);
            CHECK(w == 1920 && h == 1080);

            /* Either half out of range is a corrupt entry rather than half a
             * screen, so the pair falls back to the real display together. */
            rb_settings_set_int(f, RB_PREF_SCREEN_WIDTH, RB_SCREEN_PX_MAX + 1);
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);
            CHECK(w == 0 && h == 0);
            rb_settings_set_int(f, RB_PREF_SCREEN_WIDTH, 1920);
            rb_settings_set_int(f, RB_PREF_SCREEN_HEIGHT, RB_SCREEN_PX_MIN - 1);
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);
            rb_settings_set_int(f, RB_PREF_SCREEN_HEIGHT, 1080);

            /* The bounds themselves are claims. */
            rb_settings_set_int(f, RB_PREF_SCREEN_WIDTH, RB_SCREEN_PX_MIN);
            rb_settings_set_int(f, RB_PREF_SCREEN_HEIGHT, RB_SCREEN_PX_MAX);
            CHECK(rb_screen_claim_of(f, &w, &h) == 1);
            CHECK(w == RB_SCREEN_PX_MIN && h == RB_SCREEN_PX_MAX);

            /* The numbers are inert without the mode: switching the row back
             * to the real display must not leave the old size claimed. */
            rb_settings_set(f, RB_PREF_SCREEN_SIZE, "real");
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);

            /* Mode values that cannot be read are not a licence to claim
             * something - the same rule the WebRTC policy follows. */
            rb_settings_set(f, RB_PREF_SCREEN_SIZE, "Manual");
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);
            rb_settings_set(f, RB_PREF_SCREEN_SIZE, "");
            CHECK(rb_screen_claim_of(f, &w, &h) == 0);

            /* A store that never had the keys at all - a settings file written
             * before the row existed. */
            {
                rb_settings *bare = rb_settings_new();
                CHECK(rb_screen_claim_of(bare, &w, &h) == 0);
                rb_settings_free(bare);
            }

            /* The outputs may be asked for one at a time. */
            rb_settings_set(f, RB_PREF_SCREEN_SIZE, "manual");
            CHECK(rb_screen_claim_of(f, NULL, NULL) == 1);
            CHECK(rb_screen_claim_of(f, &w, NULL) == 1 && w == RB_SCREEN_PX_MIN);
        }

        /* the spellings round-trip, which is what lets a combo box be built
         * from the enum rather than repeating the strings */
        CHECK(strcmp(rb_webrtc_policy_name(RB_WEBRTC_DEFAULT), "default") == 0);
        CHECK(strcmp(rb_webrtc_policy_name(RB_WEBRTC_DISABLED), "disabled") == 0);
        CHECK(strcmp(rb_webrtc_policy_name(RB_WEBRTC_RESTRICT_LOCAL_IP),
                     "restrict_local_ip") == 0);
        CHECK(strcmp(rb_webrtc_policy_name((rb_webrtc_policy)99),
                     "restrict_local_ip") == 0);

        rb_settings_free(f);
    }
}

/* -------------------------------- rb_profile ------------------------------ */

static void test_rb_profile(void)
{
    rb_profile_registry *r = rb_profile_registry_new();
    char idbuf[RB_PROFILE_ID_LEN + 1];
    char suffix[RB_PROFILE_SUFFIX_LEN + 1];
    int a, b, c;
    const rb_profile *p;

    CHECK(rb_profile_count(r) == 0);
    CHECK(rb_profile_default(r) == NULL);
    CHECK(rb_profile_at(r, 0) == NULL);
    CHECK(rb_profile_by_id(r, "nope") == NULL);
    CHECK(rb_profile_index_of(r, "nope") == -1);

    /* the first profile created becomes the default */
    a = rb_profile_create(r, "  Personal  ", NULL, 0, 1);
    CHECK(a == 0);
    p = rb_profile_at(r, a);
    CHECK(p != NULL);
    CHECK(STREQ(p->name, "Personal")); /* trimmed */
    CHECK(p->is_default == 1);
    CHECK(p->is_locked == 0);
    CHECK(strlen(p->id) == RB_PROFILE_ID_LEN);
    CHECK(p->created_at > 0);
    CHECK(p->last_active_at == p->created_at);
    CHECK(p->icon != NULL && p->icon[0] != '\0');
    CHECK(p->theme_json != NULL && p->theme_json[0] == '\0');
    CHECK(p->settings != NULL);

    /* a new profile is never the default, and never shares the first's UA */
    b = rb_profile_create(r, "Work", NULL, 0xFF112233u, 1);
    CHECK(b == 1);
    p = rb_profile_at(r, b);
    CHECK(p->is_default == 0);
    CHECK(p->color_argb == 0xFF112233u);
    CHECK(rb_profile_default(r) == rb_profile_at(r, a));
    {
        const rb_profile *pa = rb_profile_at(r, a);
        const rb_profile *pb = rb_profile_at(r, b);
        const char *da = rb_settings_get(pa->settings, RB_PREF_DEVICE_ID, "");
        const char *db = rb_settings_get(pb->settings, RB_PREF_DEVICE_ID, "");

        /* A fresh profile is handed a real machine, and the machine carries
         * the identity: two profiles created in a row must not present the
         * same one, or the catalogue would be pointless. */
        CHECK(rb_device_by_id(da) != NULL);
        CHECK(rb_device_by_id(db) != NULL);
        CHECK(strcmp(da, db) != 0);

        /* The device drives the User-Agent, so the UA keys stay untouched
         * rather than sitting there as a second, contradicting answer. */
        CHECK(STREQ(rb_settings_get(pa->settings, RB_PREF_UA_MODE, ""),
                    "default"));
        CHECK(STREQ(rb_settings_get(pb->settings, RB_PREF_UA_MODE, ""),
                    "default"));
        CHECK(STREQ(rb_settings_get(pa->settings, RB_PREF_UA_PRESET_ID, ""),
                    ""));
        CHECK(STREQ(rb_settings_get(pa->settings, RB_PREF_CUSTOM_USER_AGENT, ""),
                    ""));

        /* ...and the two rows are two different machines. The UA alone does
         * not decide that: the catalogue shares one between machines on the
         * same OS and Chrome, and separates them on GPU, cores and memory
         * (test_rb_devices proves every pair differs), so a page can still
         * tell the two profiles apart. */
        CHECK(!same_device_identity(rb_device_by_id(da),
                                    rb_device_by_id(db)));
    }

    /* ... unless the caller says not to (import/restore) */
    c = rb_profile_create(r, "Imported", NULL, 0, 0);
    CHECK(c == 2);
    CHECK(STREQ(rb_settings_get(rb_profile_at(r, c)->settings,
                                RB_PREF_UA_MODE, ""), "default"));
    CHECK(STREQ(rb_settings_get(rb_profile_at(r, c)->settings,
                                RB_PREF_DEVICE_ID, ""), ""));

    /* names are unique case-insensitively and validated */
    CHECK(rb_profile_create(r, "personal", NULL, 0, 0) == -1);
    CHECK(rb_profile_create(r, "PERSONAL", NULL, 0, 0) == -1);
    CHECK(rb_profile_create(r, "   ", NULL, 0, 0) == -1);
    CHECK(rb_profile_create(r, NULL, NULL, 0, 0) == -1);
    CHECK(rb_profile_create(r, "", NULL, 0, 0) == -1);
    {
        char longname[RB_PROFILE_MAX_NAME + 2];
        memset(longname, 'x', sizeof(longname) - 1);
        longname[sizeof(longname) - 1] = '\0';
        CHECK(rb_profile_create(r, longname, NULL, 0, 0) == -1);
    }
    {
        char maxname[RB_PROFILE_MAX_NAME + 1];
        memset(maxname, 'y', sizeof(maxname) - 1);
        maxname[RB_PROFILE_MAX_NAME] = '\0';
        CHECK(rb_profile_create(r, maxname, NULL, 0, 0) == 3);
        /* renaming anything onto it must be refused, case-insensitively */
        CHECK(rb_profile_rename(r, rb_profile_at(r, 0)->id, maxname) == 0);
        CHECK(rb_profile_delete(r, rb_profile_at(r, 3)->id) == 1);
    }

    /* a rename takes effect and can reuse its own name (no self-collision) */
    CHECK(rb_profile_rename(r, rb_profile_at(r, 1)->id, "Work Stuff") == 1);
    CHECK(STREQ(rb_profile_at(r, 1)->name, "Work Stuff"));
    CHECK(rb_profile_rename(r, rb_profile_at(r, 1)->id, "Work Stuff") == 1);
    CHECK(rb_profile_rename(r, rb_profile_at(r, 1)->id, "Personal") == 0);
    CHECK(rb_profile_rename(r, "no-such-id", "x") == 0);

    /* restyle touches only the look */
    {
        const char *id = rb_profile_at(r, 1)->id;
        CHECK(rb_profile_restyle(r, id, "\360\237\232\200", 0xFF00FF00u) == 1);
        p = rb_profile_at(r, 1);
        CHECK(STREQ(p->icon, "\360\237\232\200"));
        CHECK(p->color_argb == 0xFF00FF00u);
        CHECK(STREQ(p->name, "Work Stuff")); /* untouched */
        CHECK(p->id == p->id);               /* identity untouched */
        CHECK(rb_profile_restyle(r, id, NULL, 0) == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->icon, "\360\237\232\200"));
    }

    /* the theme ID is a profile snapshot, not a setting: the "theme" setting
     * is the light/dark/amoled MODE, and the picker that renders one of the
     * built-in palettes writes here instead */
    {
        const char *id = rb_profile_at(r, 1)->id;

        /* nothing has chosen one, so the snapshot is empty and the reader
         * falls back to the default theme */
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json, ""));

        CHECK(rb_profile_set_theme(r, id, "arctic") == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json, "{\"id\":\"arctic\"}"));
        /* ...and it is a theme the registry knows, which is what makes
         * rb_theme_current() resolve it rather than fall back */
        CHECK(rb_theme_by_id("arctic") != NULL);

        /* the last write wins, and the MODE setting is left alone: the two
         * are separate controls and picking a theme must not move the mode */
        CHECK(rb_profile_set_theme(r, id, "ocean") == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json, "{\"id\":\"ocean\"}"));
        CHECK(STREQ(rb_settings_get(rb_profile_at(r, 1)->settings,
                                    RB_PREF_THEME, "system"), "system"));

        /* NULL and "" both clear it, which reads back as the default */
        CHECK(rb_profile_set_theme(r, id, NULL) == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json, ""));
        CHECK(rb_profile_set_theme(r, id, "") == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json, ""));
        CHECK(rb_profile_set_theme(r, "no-such-id", "ocean") == 0);
        CHECK(rb_profile_set_theme(r, NULL, "ocean") == 0);

        /* an id carrying JSON metacharacters is escaped, so the snapshot
         * stays parseable.  The picker only offers registry ids, but the
         * function takes any string. */
        CHECK(rb_profile_set_theme(r, id, "a\"b\\c") == 1);
        CHECK(STREQ(rb_profile_at(r, 1)->theme_json,
                    "{\"id\":\"a\\\"b\\\\c\"}"));
        {
            const char *json = rb_profile_at(r, 1)->theme_json;
            size_t pos = 0;
            char *back = NULL;
            CHECK(rb_json_find_key(json, "id", &pos));
            CHECK(rb_json_parse_string(json, &pos, &back));
            CHECK(STREQ(back, "a\"b\\c"));
            free(back);
        }
        CHECK(rb_profile_set_theme(r, id, "obsidian") == 1);
    }

    /* a profile file written by the build whose theme combo put the theme ID
     * in the MODE setting is repaired once, so a choice made with that picker
     * shows up instead of sitting inert in the wrong key */
    {
        rb_profile_registry *rp = rb_profile_registry_new();
        int a2, b2, c2;

        CHECK(rp != NULL);
        a2 = rb_profile_create(rp, "Broken", NULL, 0, 0);
        b2 = rb_profile_create(rp, "Mode", NULL, 0, 0);
        c2 = rb_profile_create(rp, "Unknown", NULL, 0, 0);
        CHECK(a2 == 0 && b2 == 1 && c2 == 2);

        rb_settings_set(rb_profile_at(rp, a2)->settings, RB_PREF_THEME, "cyber");
        /* a mode is not a theme, so it must survive as it is */
        rb_settings_set(rb_profile_at(rp, b2)->settings, RB_PREF_THEME, "dark");
        /* nor is a name the registry does not know */
        rb_settings_set(rb_profile_at(rp, c2)->settings, RB_PREF_THEME,
                        "no-such-theme");

        CHECK(rb_profile_repair_theme_setting(rp) == 1);
        CHECK(STREQ(rb_profile_at(rp, a2)->theme_json, "{\"id\":\"cyber\"}"));
        CHECK(STREQ(rb_settings_get(rb_profile_at(rp, a2)->settings,
                                    RB_PREF_THEME, ""), "system"));
        CHECK(STREQ(rb_profile_at(rp, b2)->theme_json, ""));
        CHECK(STREQ(rb_settings_get(rb_profile_at(rp, b2)->settings,
                                    RB_PREF_THEME, ""), "dark"));
        CHECK(STREQ(rb_profile_at(rp, c2)->theme_json, ""));
        CHECK(STREQ(rb_settings_get(rb_profile_at(rp, c2)->settings,
                                    RB_PREF_THEME, ""), "no-such-theme"));

        /* running it again finds nothing left to do, which is what makes it
         * safe to call on every launch */
        CHECK(rb_profile_repair_theme_setting(rp) == 0);

        /* a snapshot the picker already wrote is kept, not overwritten by the
         * stale setting underneath it, but the MODE key is still cleared —
         * a theme name in it means the mode reads as AUTO no matter what */
        CHECK(rb_profile_set_theme(rp, rb_profile_at(rp, b2)->id,
                                   "emerald") == 1);
        rb_settings_set(rb_profile_at(rp, b2)->settings, RB_PREF_THEME, "rose");
        CHECK(rb_profile_repair_theme_setting(rp) == 1);
        CHECK(STREQ(rb_profile_at(rp, b2)->theme_json,
                    "{\"id\":\"emerald\"}"));
        CHECK(STREQ(rb_settings_get(rb_profile_at(rp, b2)->settings,
                                    RB_PREF_THEME, ""), "system"));

        rb_profile_registry_free(rp);
    }

    /* set_default moves the flag, never duplicates it */
    {
        int i;
        int defaults;

        CHECK(rb_profile_set_default(r, rb_profile_at(r, 1)->id) == 1);
        defaults = 0;
        for (i = 0; i < rb_profile_count(r); i++) {
            if (rb_profile_at(r, i)->is_default) {
                defaults++;
            }
        }
        CHECK(defaults == 1);
        CHECK(rb_profile_default(r) == rb_profile_at(r, 1));
        CHECK(rb_profile_set_default(r, "no-such-id") == 0);
    }

    /* lock flag */
    CHECK(rb_profile_set_locked(r, rb_profile_at(r, 0)->id, 1) == 1);
    CHECK(rb_profile_at(r, 0)->is_locked == 1);
    CHECK(rb_profile_set_locked(r, rb_profile_at(r, 0)->id, 0) == 1);
    CHECK(rb_profile_at(r, 0)->is_locked == 0);

    /* touch only moves last_active_at forward */
    {
        const rb_profile *before = rb_profile_at(r, 0);
        long long created = before->created_at;
        long long was = before->last_active_at;

        CHECK(rb_profile_touch(r, before->id) == 1);
        CHECK(rb_profile_at(r, 0)->created_at == created);
        CHECK(rb_profile_at(r, 0)->last_active_at >= was);
        CHECK(rb_profile_touch(r, "no-such-id") == 0);
    }

    /* duplicate: unique name, new identity, inherited settings, not default */
    {
        const char *src_id = rb_profile_at(r, 1)->id;
        char src_engine[64];
        char src_device[128];
        int dup;
        const rb_profile *d;
        int i;

        snprintf(src_engine, sizeof(src_engine), "%s",
                 rb_settings_get(rb_profile_at(r, 1)->settings,
                                 RB_PREF_SEARCH_ENGINE, ""));
        snprintf(src_device, sizeof(src_device), "%s",
                 rb_settings_get(rb_profile_at(r, 1)->settings,
                                 RB_PREF_DEVICE_ID, ""));
        CHECK(src_device[0] != '\0'); /* the source does present a machine */

        dup = rb_profile_duplicate(r, src_id, NULL);
        CHECK(dup >= 0);
        d = rb_profile_at(r, dup);
        CHECK(STREQ(d->name, "Work Stuff Copy"));
        CHECK(strcmp(d->id, src_id) != 0); /* a copy is a new identity */
        CHECK(d->is_default == 0);
        CHECK(d->is_locked == 0);
        CHECK(STREQ(rb_settings_get(d->settings, RB_PREF_SEARCH_ENGINE, ""),
                    src_engine));
        /* It inherits the configuration but not the machine: a copy that went
         * on presenting the source's device would defeat the catalogue. */
        CHECK(strcmp(rb_settings_get(d->settings, RB_PREF_DEVICE_ID, ""),
                     src_device) != 0);
        CHECK(rb_device_by_id(rb_settings_get(d->settings,
                                              RB_PREF_DEVICE_ID, NULL)) != NULL);

        /* the second copy gets a numbered name */
        {
            int dup2 = rb_profile_duplicate(r, src_id, NULL);
            CHECK(dup2 >= 0);
            CHECK(STREQ(rb_profile_at(r, dup2)->name, "Work Stuff Copy 2"));
        }
        /* so does an explicit suffix */
        {
            int dup3 = rb_profile_duplicate(r, src_id, " (backup)");
            CHECK(dup3 >= 0);
            CHECK(STREQ(rb_profile_at(r, dup3)->name, "Work Stuff (backup)"));
        }

        /* a copy of a long-named profile stays within the length limit and
         * keeps the unique part of the name */
        {
            char longname[RB_PROFILE_MAX_NAME + 1];
            int idx;
            int dup4;

            memset(longname, 'z', sizeof(longname) - 1);
            longname[RB_PROFILE_MAX_NAME] = '\0';
            idx = rb_profile_create(r, longname, NULL, 0, 0);
            CHECK(idx >= 0);
            dup4 = rb_profile_duplicate(r, rb_profile_at(r, idx)->id, NULL);
            CHECK(dup4 >= 0);
            CHECK(strlen(rb_profile_at(r, dup4)->name) <=
                  (size_t)RB_PROFILE_MAX_NAME);
            CHECK(strstr(rb_profile_at(r, dup4)->name, "Copy") != NULL);
            /* and that name is itself acceptable to rename() */
            CHECK(rb_profile_rename(r, rb_profile_at(r, dup4)->id,
                                    rb_profile_at(r, dup4)->name) == 1);
            CHECK(rb_profile_delete(r, rb_profile_at(r, dup4)->id) == 1);
            CHECK(rb_profile_delete(r, rb_profile_at(r, idx)->id) == 1);
        }

        /* clean up the three duplicates (indices shift, so delete by id) */
        for (i = 0; i < 3; i++) {
            int k;
            const char *victim = NULL;
            for (k = 0; k < rb_profile_count(r); k++) {
                if (strstr(rb_profile_at(r, k)->name, "Work Stuff") != NULL &&
                    strcmp(rb_profile_at(r, k)->id, src_id) != 0) {
                    victim = rb_profile_at(r, k)->id;
                    break;
                }
            }
            CHECK(victim != NULL);
            if (victim != NULL) {
                CHECK(rb_profile_delete(r, victim) == 1);
            }
        }
        CHECK(rb_profile_duplicate(r, "no-such-id", NULL) == -1);
    }

    /* deleting the default hands the flag to a surviving profile */
    {
        char def_id[RB_PROFILE_ID_LEN + 1];
        int remaining = rb_profile_count(r);
        int i;
        int defaults = 0;

        /* Copy the id out: the registry's own pointers are invalidated by
         * the very deletion we are about to perform. */
        snprintf(def_id, sizeof(def_id), "%s", rb_profile_default(r)->id);

        CHECK(rb_profile_delete(r, def_id) == 1);
        CHECK(rb_profile_count(r) == remaining - 1);
        for (i = 0; i < rb_profile_count(r); i++) {
            if (rb_profile_at(r, i)->is_default) {
                defaults++;
            }
        }
        CHECK(defaults == 1);
        CHECK(rb_profile_delete(r, def_id) == 0); /* already gone */
        CHECK(rb_profile_delete(r, "no-such-id") == 0);
    }

    /* deleting the last profile leaves an empty registry, not a broken one */
    while (rb_profile_count(r) > 0) {
        CHECK(rb_profile_delete(r, rb_profile_at(r, 0)->id) == 1);
    }
    CHECK(rb_profile_count(r) == 0);
    CHECK(rb_profile_default(r) == NULL);

    /* ---- persistence round trip ---- */
    {
        rb_profile_registry *back;
        int i;
        int n;

        CHECK(rb_profile_create(r, "Alpha", "\360\237\224\245", 0xFF010203u, 1) == 0);
        CHECK(rb_profile_create(r, "Beta", NULL, 0, 0) == 1);
        CHECK(rb_profile_create(r, "Gamma\"quote\\slash", NULL, 0, 1) == 2);
        CHECK(rb_profile_set_default(r, rb_profile_at(r, 1)->id) == 1);
        CHECK(rb_profile_set_locked(r, rb_profile_at(r, 2)->id, 1) == 1);
        /* one chosen theme, so the compare loop below has a non-empty
         * snapshot to carry rather than two empty strings */
        CHECK(rb_profile_set_theme(r, rb_profile_at(r, 1)->id, "sakura") == 1);
        {
            rb_settings *custom = rb_settings_new();
            rb_settings_set(custom, "some_future_key", "a value with = and \"q\"");
            CHECK(rb_profile_set_settings(r, rb_profile_at(r, 0)->id,
                                          custom) == 1);
        }

        CHECK(rb_profile_registry_save(r, TMP) == 0);

        back = rb_profile_registry_new();
        CHECK(rb_profile_registry_load(back, TMP) == 0);
        CHECK(rb_profile_count(back) == rb_profile_count(r));

        n = rb_profile_count(r);
        for (i = 0; i < n; i++) {
            const rb_profile *src = rb_profile_at(r, i);
            const rb_profile *dst = rb_profile_by_id(back, src->id);

            CHECK(dst != NULL);
            if (dst == NULL) {
                continue;
            }
            CHECK(STREQ(dst->name, src->name));
            CHECK(STREQ(dst->icon, src->icon));
            CHECK(dst->color_argb == src->color_argb);
            CHECK(dst->is_locked == src->is_locked);
            CHECK(dst->is_default == src->is_default);
            CHECK(dst->created_at == src->created_at);
            CHECK(dst->last_active_at == src->last_active_at);
            CHECK(STREQ(dst->theme_json, src->theme_json));

            /* A stored settings map is overlaid on the defaults, not read
             * verbatim: every key the source had must come back with the
             * same value. */
            {
                int k;
                int nk = rb_settings_count(src->settings);

                for (k = 0; k < nk; k++) {
                    const char *key = rb_settings_key_at(src->settings, k);
                    CHECK(STREQ(rb_settings_get(dst->settings, key, "\x01"),
                                rb_settings_value_at(src->settings, k)));
                }
            }
            /* ... and every default key the source lacked must be filled in,
             * so a profile file written by an older build picks up a newly
             * added setting's default instead of silently missing it. */
            {
                rb_settings *d = rb_settings_new();
                int k;
                int nd;

                rb_prefs_profile_defaults(d);
                nd = rb_settings_count(d);
                for (k = 0; k < nd; k++) {
                    const char *key = rb_settings_key_at(d, k);
                    CHECK(rb_settings_get(dst->settings, key, "\x01") != NULL);
                }
                rb_settings_free(d);
            }
            CHECK(rb_settings_count(dst->settings) >=
                  rb_settings_count(src->settings));
            CHECK(STREQ(rb_settings_get(dst->settings, "some_future_key", ""),
                        rb_settings_get(src->settings, "some_future_key", "")));
            CHECK(STREQ(rb_settings_get(dst->settings, RB_PREF_SEARCH_ENGINE,
                                        ""),
                        rb_settings_get(src->settings, RB_PREF_SEARCH_ENGINE,
                                        "")));
        }
        /* the reloaded registry still has exactly one default */
        {
            int defaults = 0;
            for (i = 0; i < rb_profile_count(back); i++) {
                if (rb_profile_at(back, i)->is_default) {
                    defaults++;
                }
            }
            CHECK(defaults == 1);
        }
        rb_profile_registry_free(back);
    }

    /* a missing file leaves an empty registry, not an error or garbage */
    {
        rb_profile_registry *empty = rb_profile_registry_new();
        CHECK(rb_profile_registry_load(empty, "rb-test-does-not-exist.jsonl") ==
              0);
        CHECK(rb_profile_count(empty) == 0);
        rb_profile_registry_free(empty);
    }

    /* malformed lines are skipped and the good ones survive; a hand-edited
     * file with no default gets one back */
    {
        FILE *f = fopen(TMP, "wb");
        rb_profile_registry *back;

        /* The two rows below share an id, so it has to be a real one: an
         * uninitialised idbuf happened to read as printable stack garbage
         * often enough to pass, and blew up on the runs where it did not. */
        rb_profile_new_id(idbuf);
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "not json at all\n");
            fprintf(f, "{\"id\":\"abc\",}\n");
            fprintf(f, "{\"name\":\"no id here\"}\n");
            fprintf(f, "{\"id\":\"%s\",\"name\":\"Solo\"}\n", idbuf);
            fprintf(f, "{\"id\":\"%s\",\"name\":\"Dup\"}\n", idbuf);
            fclose(f);
        }
        back = rb_profile_registry_new();
        CHECK(rb_profile_registry_load(back, TMP) == 0);
        CHECK(rb_profile_count(back) == 1); /* the duplicate id is dropped */
        CHECK(STREQ(rb_profile_at(back, 0)->name, "Solo"));
        CHECK(rb_profile_at(back, 0)->is_default == 1); /* repaired */
        rb_profile_registry_free(back);
    }

    /* ---- identity helpers ---- */
    idbuf[0] = '\0';
    rb_profile_new_id(idbuf);
    CHECK(strlen(idbuf) == RB_PROFILE_ID_LEN);
    CHECK(idbuf[8] == '-' && idbuf[13] == '-' && idbuf[18] == '-' &&
          idbuf[23] == '-');
    CHECK(idbuf[14] == '4'); /* version nibble */
    {
        char other[RB_PROFILE_ID_LEN + 1];
        int k;
        int seen_dash;
        int all_lower_hex = 1;

        rb_profile_new_id(other);
        CHECK(strcmp(other, idbuf) != 0); /* must not repeat */

        for (k = 0; idbuf[k] != '\0'; k++) {
            char ch = idbuf[k];
            if (ch == '-') {
                continue;
            }
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                all_lower_hex = 0;
            }
        }
        CHECK(all_lower_hex);

        /* safeSuffix: dashes gone, lowercase, <= 32 chars */
        rb_profile_safe_suffix(idbuf, suffix);
        CHECK(strlen(suffix) == RB_PROFILE_SUFFIX_LEN);
        seen_dash = 0;
        for (k = 0; suffix[k] != '\0'; k++) {
            if (suffix[k] == '-') {
                seen_dash = 1;
            }
        }
        CHECK(seen_dash == 0);
    }
    suffix[0] = '\0';
    rb_profile_safe_suffix("ABCDEF-0123", suffix);
    CHECK(STREQ(suffix, "abcdef0123"));
    rb_profile_safe_suffix(NULL, suffix);
    CHECK(suffix[0] == '\0');
    rb_profile_safe_suffix("x", NULL); /* must not crash */

    rb_profile_registry_free(r);
    rb_profile_registry_free(NULL);
    CHECK(rb_profile_count(NULL) == 0);
    CHECK(rb_profile_at(NULL, 0) == NULL);
    CHECK(rb_profile_by_id(NULL, "x") == NULL);
    CHECK(rb_profile_default(NULL) == NULL);
    CHECK(rb_profile_create(NULL, "x", NULL, 0, 0) == -1);
    CHECK(rb_profile_index_of(NULL, "x") == -1);
}

/* --------------------------- rb_profile directories ----------------------- */

static void test_rb_profile_dirs(void)
{
    const char *id = "0123456789abcdef0123456789abcdef";
    char *d;
    char *sub;

    (void)rb_paths_remove_tree(TMP_DIR);

    d = rb_profile_dir(TMP_DIR, id);
    CHECK(d != NULL);
    CHECK(strstr(d, "profiles") != NULL);
    CHECK(strstr(d, id) != NULL);
    free(d);

    d = rb_profile_root(TMP_DIR);
    CHECK(d != NULL);
    CHECK(strstr(d, "profiles") != NULL);
    free(d);

    /* the storage path is derived from the id, never from a display name */
    CHECK(rb_profile_dir(TMP_DIR, NULL) == NULL);
    CHECK(rb_profile_dir(NULL, id) == NULL);
    CHECK(rb_profile_dir(TMP_DIR, "") == NULL);
    CHECK(rb_profile_subdir(TMP_DIR, id, RB_PROFILE_DIR_COUNT) == NULL);

    /* every component has the expected name */
    {
        int which;
        const char *names[RB_PROFILE_DIR_COUNT] = {
            "metadata", "browser_data", "cache", "downloads", "settings"
        };

        for (which = 0; which < RB_PROFILE_DIR_COUNT; which++) {
            sub = rb_profile_subdir(TMP_DIR, id, which);
            CHECK(sub != NULL);
            CHECK(strstr(sub, names[which]) != NULL);
            CHECK(!rb_paths_is_dir(sub)); /* not created yet */
            free(sub);
        }
    }

    CHECK(rb_profile_ensure_dirs(TMP_DIR, id) == 0);
    {
        int which;
        for (which = 0; which < RB_PROFILE_DIR_COUNT; which++) {
            sub = rb_profile_subdir(TMP_DIR, id, which);
            CHECK(rb_paths_is_dir(sub));
            free(sub);
        }
    }
    /* idempotent */
    CHECK(rb_profile_ensure_dirs(TMP_DIR, id) == 0);

    /* a file inside the tree goes away with it */
    {
        char *file;
        FILE *f;

        sub = rb_profile_subdir(TMP_DIR, id, RB_PROFILE_DIR_SETTINGS);
        file = rb_paths_join(sub, "settings.txt");
        f = fopen(file, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "home=https://example.com\n");
            fclose(f);
        }
        CHECK(rb_paths_is_file(file));
        free(file);
        free(sub);

        CHECK(rb_profile_remove_data(TMP_DIR, id) == 1);
        d = rb_profile_dir(TMP_DIR, id);
        CHECK(!rb_paths_is_dir(d));
        free(d);

        /* removing something already gone is still success */
        CHECK(rb_profile_remove_data(TMP_DIR, id) == 1);
    }

    (void)rb_paths_remove_tree(TMP_DIR);
}

/* ---------------------------- rb_paths tree ops --------------------------- */

static void test_rb_paths_tree(void)
{
    char *src = rb_paths_join(TMP_DIR, "src");
    char *dst = rb_paths_join(TMP_DIR, "dst");
    char *nested;
    char *file;
    FILE *f;

    (void)rb_paths_remove_tree(TMP_DIR);

    /* remove_tree on a path that does not exist reports success */
    CHECK(rb_paths_remove_tree(TMP_DIR) == 1);
    CHECK(rb_paths_remove_tree(NULL) == 0);
    CHECK(rb_paths_remove_tree("") == 0);

    CHECK(rb_paths_mkdirs(src) == 0);
    nested = rb_paths_join(src, "a/b");
    CHECK(rb_paths_mkdirs(nested) == 0);

    file = rb_paths_join(nested, "keep.txt");
    f = fopen(file, "wb");
    CHECK(f != NULL);
    if (f != NULL) {
        fprintf(f, "hello tree");
        fclose(f);
    }

    /* copy_tree reproduces the shape and the bytes */
    CHECK(rb_paths_copy_tree(src, dst) == 0);
    {
        char *copied = rb_paths_join(dst, "a/b/keep.txt");
        char buf[64];
        size_t got;

        CHECK(rb_paths_is_dir(dst));
        CHECK(rb_paths_is_file(copied));
        f = fopen(copied, "rb");
        CHECK(f != NULL);
        got = (f != NULL) ? fread(buf, 1, sizeof(buf) - 1, f) : 0;
        if (f != NULL) {
            fclose(f);
        }
        buf[got] = '\0';
        CHECK(STREQ(buf, "hello tree"));
        free(copied);
    }

    /* copying a missing source is an error, not a silent empty copy */
    CHECK(rb_paths_copy_tree("rb-test-nope", dst) == -1);
    CHECK(rb_paths_copy_tree(NULL, dst) == -1);
    CHECK(rb_paths_copy_tree(src, NULL) == -1);

    /* remove_tree takes the whole thing, not just the top directory */
    CHECK(rb_paths_remove_tree(src) == 1);
    CHECK(!rb_paths_is_dir(src));
    CHECK(!rb_paths_is_file(file));

    /* a plain file can be removed too */
    CHECK(rb_paths_remove_tree(file) == 1);
    CHECK(!rb_paths_is_file(file));

    CHECK(rb_paths_remove_tree(TMP_DIR) == 1);
    CHECK(!rb_paths_is_dir(TMP_DIR));

    free(src);
    free(dst);
    free(nested);
    free(file);

    /* dirname */
    {
        char *d = rb_paths_dirname("/a/b/c.txt");
        CHECK(d != NULL && STREQ(d, "/a/b"));
        free(d);
        d = rb_paths_dirname("plain.txt");
        CHECK(d != NULL && STREQ(d, "."));
        free(d);
        d = rb_paths_dirname("/x");
        CHECK(d != NULL && STREQ(d, "/"));
        free(d);
        d = rb_paths_dirname(NULL);
        CHECK(d != NULL && STREQ(d, "."));
        free(d);
        d = rb_paths_dirname("");
        CHECK(d != NULL && STREQ(d, "."));
        free(d);
    }

    /* join / join3 */
    {
        char *j = rb_paths_join("a", "b");
        char *k = rb_paths_join3("a", "b", "c");
        CHECK(j != NULL && STREQ(j, "a/b"));
        CHECK(k != NULL && STREQ(k, "a/b/c"));
        free(j);
        free(k);
        free(rb_paths_join(NULL, "b"));
        free(rb_paths_join("a", NULL));
        CHECK(rb_paths_join("", "b") == NULL);
        CHECK(rb_paths_join("a", "") == NULL);
        CHECK(rb_paths_join3(NULL, "b", "c") == NULL);
    }

    /* type probes tolerate NULL and the empty string */
    CHECK(rb_paths_is_dir(NULL) == 0);
    CHECK(rb_paths_is_file(NULL) == 0);
    CHECK(rb_paths_is_dir("") == 0);
    CHECK(rb_paths_is_file("") == 0);
    CHECK(rb_paths_mkdirs(NULL) == -1);
    CHECK(rb_paths_mkdirs("") == -1);
}

/* ---------------------------- rb_https fallback --------------------------- */

static void test_rb_https(void)
{
    rb_https_pending *p = rb_https_pending_new();
    char *u;

    /* Only the transport-level failures are worth an http retry.  A page
     * that loaded but is broken fails the same way over http, and retrying
     * would just fail twice. */
    CHECK(rb_https_is_recoverable(RB_HTTPS_ERR_UNKNOWN) == 1);
    CHECK(rb_https_is_recoverable(RB_HTTPS_ERR_CONNECT) == 1);
    CHECK(rb_https_is_recoverable(RB_HTTPS_ERR_TIMEOUT) == 1);
    CHECK(rb_https_is_recoverable(RB_HTTPS_ERR_SSL_HANDSHAKE) == 1);
    CHECK(rb_https_is_recoverable(0) == 0);
    CHECK(rb_https_is_recoverable(-2) == 0);   /* cancelled */
    CHECK(rb_https_is_recoverable(-3) == 0);   /* file not found */
    CHECK(rb_https_is_recoverable(-4) == 0);   /* timed out (page) */
    CHECK(rb_https_is_recoverable(-5) == 0);   /* unsupported */
    CHECK(rb_https_is_recoverable(-7) == 0);   /* too many redirects */
    CHECK(rb_https_is_recoverable(-9) == 0);   /* cannot show mime type */
    CHECK(rb_https_is_recoverable(-10) == 0);  /* cannot show content */
    CHECK(rb_https_is_recoverable(-12) == 0);  /* frame load interrupted */
    CHECK(rb_https_is_recoverable(-13) == 0);  /* plugin will handle */
    CHECK(rb_https_is_recoverable(9999) == 0);

    CHECK(rb_https_pending_count(p) == 0);

    /* An empty registry answers NULL, and NULL is the common case: it means
     * "this failure had nothing to do with an upgrade of ours". */
    CHECK(rb_https_consume(p, "https://example.com/") == NULL);
    CHECK(rb_https_retry_url(p, "https://example.com/", RB_HTTPS_ERR_CONNECT) == NULL);

    rb_https_register(p, "https://example.com/", "http://example.com/");
    CHECK(rb_https_pending_count(p) == 1);

    /* A different URL is not ours, even though an upgrade is pending. */
    CHECK(rb_https_consume(p, "https://other.example/") == NULL);
    CHECK(rb_https_pending_count(p) == 1);

    /* ... and consuming ours takes it exactly once: the retry that follows
     * must not be able to upgrade-and-fail in a loop. */
    u = rb_https_consume(p, "https://example.com/");
    CHECK(u != NULL && STREQ(u, "http://example.com/"));
    free(u);
    CHECK(rb_https_pending_count(p) == 0);
    CHECK(rb_https_consume(p, "https://example.com/") == NULL);

    /* Re-registering the same upgraded URL (a reload) replaces rather than
     * grows, so a long-lived tab cannot leak the table. */
    rb_https_register(p, "https://example.com/", "http://example.com/");
    rb_https_register(p, "https://example.com/", "http://example.com/?retry=1");
    CHECK(rb_https_pending_count(p) == 1);
    u = rb_https_consume(p, "https://example.com/");
    CHECK(u != NULL && STREQ(u, "http://example.com/?retry=1"));
    free(u);

    /* Several upgrades coexist and are matched independently. */
    rb_https_register(p, "https://a.example/", "http://a.example/");
    rb_https_register(p, "https://b.example/", "http://b.example/");
    rb_https_register(p, "https://c.example/", "http://c.example/");
    CHECK(rb_https_pending_count(p) == 3);
    u = rb_https_consume(p, "https://b.example/");
    CHECK(u != NULL && STREQ(u, "http://b.example/"));
    free(u);
    CHECK(rb_https_pending_count(p) == 2);
    u = rb_https_consume(p, "https://a.example/");
    CHECK(u != NULL && STREQ(u, "http://a.example/"));
    free(u);
    u = rb_https_consume(p, "https://c.example/");
    CHECK(u != NULL && STREQ(u, "http://c.example/"));
    free(u);
    CHECK(rb_https_pending_count(p) == 0);

    rb_https_clear(p);
    CHECK(rb_https_pending_count(p) == 0);

    /* Retry decision: recoverable + ours -> the original http URL. */
    rb_https_register(p, "https://only-http.example/", "http://only-http.example/");
    u = rb_https_retry_url(p, "https://only-http.example/", RB_HTTPS_ERR_SSL_HANDSHAKE);
    CHECK(u != NULL && STREQ(u, "http://only-http.example/"));
    free(u);
    CHECK(rb_https_pending_count(p) == 0);

    /* A non-recoverable failure drops the entry instead of retrying, AND
     * retires the upgrade: a later, unrelated failure of the same URL must
     * not resurrect the http redirect the user already moved past. */
    rb_https_register(p, "https://broken.example/", "http://broken.example/");
    CHECK(rb_https_retry_url(p, "https://broken.example/", -7) == NULL);
    CHECK(rb_https_pending_count(p) == 0);
    CHECK(rb_https_consume(p, "https://broken.example/") == NULL);

    /* Argument hygiene: blanks and NULLs are ignored rather than stored, so
     * a registry cannot accumulate entries that can never be matched. */
    rb_https_clear(p);
    rb_https_register(p, NULL, "http://x.example/");
    rb_https_register(p, "https://x.example/", NULL);
    rb_https_register(p, "", "http://x.example/");
    rb_https_register(p, "https://x.example/", "");
    rb_https_register(p, "   ", "http://x.example/");
    CHECK(rb_https_pending_count(p) == 0);

    /* Surrounding whitespace is trimmed on both sides of the comparison, so
     * a URL that arrived with a trailing newline still matches. */
    rb_https_register(p, "  https://ws.example/ \n", " http://ws.example/ ");
    CHECK(rb_https_pending_count(p) == 1);
    u = rb_https_consume(p, "https://ws.example/");
    CHECK(u != NULL && STREQ(u, "http://ws.example/"));
    free(u);
    CHECK(rb_https_pending_count(p) == 0);

    /* NULL registry: every entry point is a no-op rather than a crash, which
     * is what a platform layer with no upgrade support needs. */
    CHECK(rb_https_pending_count(NULL) == 0);
    CHECK(rb_https_consume(NULL, "https://example.com/") == NULL);
    CHECK(rb_https_retry_url(NULL, "https://example.com/", RB_HTTPS_ERR_CONNECT) == NULL);
    rb_https_register(NULL, "https://a/", "http://a/");
    rb_https_clear(NULL);
    rb_https_pending_free(NULL);

    rb_https_pending_free(p);
}

/* ----------------------------- rb_downloads ----------------------------- */

/* Checks a name and frees it. */
static void check_name(const char *got, const char *want, int line)
{
    g_checks++;
    if (got == NULL || !STREQ(got, want)) {
        fprintf(stderr, "FAIL %s:%d: name was \"%s\", want \"%s\"\n", __FILE__,
                line, (got != NULL) ? got : "(null)", want);
        exit(1);
    }
}

#define CHECK_NAME(expr, want) check_name((expr), (want), __LINE__)

static void test_rb_download_names(void)
{
    const long long now = 1700000000000LL;

    /* The Content-Disposition filename wins over everything else. */
    CHECK_NAME(rb_download_guess_name("https://h/x", "attachment; filename=\"report.pdf\"",
                                      "application/octet-stream", now),
               "report.pdf");
    /* ... including the RFC 5987 form, with percent-decoding. */
    CHECK_NAME(rb_download_guess_name("https://h/x",
                                      "attachment; filename*=UTF-8''caf%C3%A9%20r.pdf",
                                      "application/octet-stream", now),
               "caf\xc3\xa9 r.pdf");
    /* The parameter name is matched case-insensitively, and a trailing
     * parameter does not leak into the name. */
    CHECK_NAME(rb_download_guess_name("https://h/x",
                                      "attachment; FILENAME=\"X.PDF\"; size=12",
                                      "application/octet-stream", now),
               "X.PDF");

    /* Without a usable disposition, the last path segment is used — but only
     * when it looks like a name. */
    CHECK_NAME(rb_download_guess_name("https://h/a/b/file.zip?token=1", NULL,
                                      "application/zip", now),
               "file.zip");
    CHECK_NAME(rb_download_guess_name("https://h/a/b/My%20Report.txt", NULL,
                                      "text/plain", now),
               "My Report.txt");
    /* "1234" has no dot, so it is not treated as a name. */
    CHECK_NAME(rb_download_guess_name("https://h/download/1234", NULL,
                                      "application/pdf", now),
               "download-1700000000000.pdf");
    /* An unknown MIME type contributes no extension. */
    CHECK_NAME(rb_download_guess_name("https://h/download/1234", NULL,
                                      "application/x-made-up", now),
               "download-1700000000000");
    /* A directory URL has no last segment at all. */
    CHECK_NAME(rb_download_guess_name("https://h/a/b/", "", "", now),
               "download-1700000000000");
    /* Nothing usable anywhere: still a name, never NULL or empty. */
    CHECK_NAME(rb_download_guess_name(NULL, NULL, NULL, now),
               "download-1700000000000");
    /* A MIME type with parameters is matched on its bare type. */
    CHECK_NAME(rb_download_guess_name("https://h/download/1234", NULL,
                                      "image/jpeg; charset=binary", now),
               "download-1700000000000.jpg");
    /* An empty filename parameter falls through to the URL. */
    CHECK_NAME(rb_download_guess_name("https://h/real.bin",
                                      "attachment; filename=\"\"", "", now),
               "real.bin");

    /* Sanitizing: every path-hostile byte becomes '_', and a run of dots
     * collapses so ".." can never survive as a traversal. */
    CHECK_NAME(rb_download_sanitize_name("..\\..\\etc/passwd"),
               "._._etc_passwd");
    CHECK_NAME(rb_download_sanitize_name("a...b.txt"), "a.b.txt");
    /* : * ? " | are five illegal bytes, each becoming one '_' */
    CHECK_NAME(rb_download_sanitize_name("re<po>rt:*?\"|.pdf"),
               "re_po_rt_____.pdf");
    CHECK_NAME(rb_download_sanitize_name("...."), ".");
    CHECK_NAME(rb_download_sanitize_name(""), "download");
    CHECK_NAME(rb_download_sanitize_name("   "), "download");
    CHECK_NAME(rb_download_sanitize_name(NULL), "download");
    CHECK_NAME(rb_download_sanitize_name("plain.txt"), "plain.txt");

    /* Truncation stops at RB_DOWNLOAD_MAX_NAME bytes... */
    {
        char big[600];
        char *out;

        memset(big, 'a', sizeof(big) - 1);
        big[sizeof(big) - 1] = '\0';
        out = rb_download_sanitize_name(big);
        CHECK(strlen(out) == RB_DOWNLOAD_MAX_NAME);
        free(out);
    }
    /* ... and on a character boundary, so the result is still valid UTF-8.
     * 119 filler bytes plus a 2-byte character straddles the limit. */
    {
        char big[256];
        char *out;
        size_t i;

        for (i = 0; i < 119; i++) {
            big[i] = 'a';
        }
        big[119] = '\xc3';
        big[120] = '\xa9';
        big[121] = '\0';
        out = rb_download_sanitize_name(big);
        CHECK(strlen(out) == 119); /* the whole character is dropped */
        CHECK((unsigned char)out[118] == 'a');
        free(out);
    }
}

static void test_rb_downloads(void)
{
    rb_downloads *d = rb_downloads_new();
    rb_downloads *d2;
    long long ids[8];
    const rb_download *dl;
    long long first;

    CHECK(rb_downloads_count(d) == 0);
    CHECK(rb_downloads_by_id(d, 1) == NULL);
    CHECK(rb_downloads_at(d, 0) == NULL);

    first = rb_downloads_enqueue(d, "p1", "https://h/report.pdf", "report.pdf",
                                 "application/pdf", 100);
    CHECK(first != 0);
    CHECK(rb_downloads_count(d) == 1);
    dl = rb_downloads_by_id(d, first);
    CHECK(dl != NULL);
    CHECK(STREQ(dl->file_name, "report.pdf"));
    CHECK(STREQ(dl->mime_type, "application/pdf"));
    CHECK(STREQ(dl->destination, ""));
    CHECK(STREQ(dl->error, ""));
    CHECK(dl->status == RB_DL_QUEUED);
    CHECK(dl->total_bytes == -1);
    CHECK(dl->downloaded_bytes == 0);
    CHECK(dl->completed_at == 0);
    CHECK(dl->created_at == 100);
    CHECK(rb_download_is_active(dl) == 1);
    CHECK(rb_download_can_pause(dl) == 1);
    CHECK(rb_download_can_resume(dl) == 0);

    /* Duplicate names get a counter, and the extension travels with it. */
    CHECK(rb_downloads_enqueue(d, "p1", "https://h/r2", "report.pdf",
                               "application/pdf", 101) != 0);
    CHECK(rb_downloads_enqueue(d, "p1", "https://h/r3", "report.pdf",
                               "application/pdf", 102) != 0);
    CHECK(STREQ(rb_downloads_at(d, 0)->file_name, "report (2).pdf"));
    CHECK(STREQ(rb_downloads_at(d, 1)->file_name, "report (1).pdf"));
    CHECK(STREQ(rb_downloads_at(d, 2)->file_name, "report.pdf"));

    /* Names are scoped per profile, so two profiles may both hold
     * "report.pdf" without either being renamed. */
    CHECK(rb_downloads_enqueue(d, "p2", "https://h/other", "report.pdf",
                               "application/pdf", 103) != 0);
    CHECK(STREQ(rb_downloads_at(d, 0)->file_name, "report.pdf"));
    CHECK(STREQ(rb_downloads_at(d, 0)->profile_id, "p2"));
    CHECK(rb_downloads_count(d) == 4);

    /* Newest first, like the DAO's created_at DESC. */
    CHECK(rb_downloads_at(d, 0)->created_at == 103);
    CHECK(rb_downloads_at(d, 3)->created_at == 100);
    CHECK(rb_downloads_at(d, -1) == NULL);
    CHECK(rb_downloads_at(d, 4) == NULL);

    /* Blank inputs are refused rather than stored as an unusable row. */
    CHECK(rb_downloads_enqueue(d, "", "https://h/x", "x.bin", "", 104) == 0);
    CHECK(rb_downloads_enqueue(d, "p1", "", "x.bin", "", 104) == 0);
    CHECK(rb_downloads_enqueue(d, NULL, "https://h/x", "x.bin", "", 104) == 0);
    CHECK(rb_downloads_enqueue(d, "p1", "https://h/x", "x.bin", "", 104) != 0);
    CHECK(STREQ(rb_downloads_at(d, 0)->mime_type, "application/octet-stream"));
    /* A blank suggested name is derived from the URL instead. */
    CHECK(rb_downloads_enqueue(d, "p1", "https://h/derived.txt", "", "", 105) != 0);
    CHECK(STREQ(rb_downloads_at(d, 0)->file_name, "derived.txt"));
    CHECK(rb_downloads_count(d) == 6);

    /* --- progress and the state machine --- */
    CHECK(rb_download_progress_percent(0, 100) == 0);
    CHECK(rb_download_progress_percent(50, 100) == 50);
    CHECK(rb_download_progress_percent(100, 100) == 100);
    CHECK(rb_download_progress_percent(1, 3) == 33);
    CHECK(rb_download_progress_percent(50, -1) == 0); /* unknown length */
    CHECK(rb_download_progress_percent(50, 0) == 0);
    CHECK(rb_download_progress_percent(500, 100) == 100); /* clamped */
    CHECK(rb_download_progress_percent(-5, 100) == 0);

    CHECK(rb_downloads_set_progress(d, first, 512, 4096) == 1);
    dl = rb_downloads_by_id(d, first);
    CHECK(dl->downloaded_bytes == 512);
    CHECK(dl->total_bytes == 4096);
    CHECK(dl->status == RB_DL_QUEUED); /* progress does not change status */
    CHECK(rb_downloads_set_progress(d, 99999, 1, 1) == 0);

    CHECK(rb_downloads_complete(d, first, "/home/u/Downloads/report.pdf", 4096,
                                200) == 1);
    dl = rb_downloads_by_id(d, first);
    CHECK(dl->status == RB_DL_COMPLETED);
    CHECK(STREQ(dl->destination, "/home/u/Downloads/report.pdf"));
    CHECK(dl->downloaded_bytes == 4096);
    CHECK(dl->total_bytes == 4096);
    CHECK(dl->completed_at == 200);
    CHECK(rb_download_is_active(dl) == 0);
    CHECK(rb_download_can_pause(dl) == 0);
    CHECK(rb_download_can_resume(dl) == 0);

    CHECK(rb_downloads_set_status(d, first, RB_DL_FAILED, "HTTP 404") == 1);
    dl = rb_downloads_by_id(d, first);
    CHECK(dl->status == RB_DL_FAILED);
    CHECK(STREQ(dl->error, "HTTP 404"));
    CHECK(rb_download_can_resume(dl) == 1);

    /* A retry clears the error; the status names survive a round trip. */
    CHECK(rb_downloads_set_status(d, first, RB_DL_QUEUED, NULL) == 1);
    dl = rb_downloads_by_id(d, first);
    CHECK(STREQ(dl->error, ""));
    CHECK(dl->status == RB_DL_QUEUED);
    CHECK(rb_downloads_set_status(d, 99999, RB_DL_FAILED, "x") == 0);

    CHECK(STREQ(rb_download_status_name(RB_DL_QUEUED), "QUEUED"));
    CHECK(STREQ(rb_download_status_name(RB_DL_RUNNING), "RUNNING"));
    CHECK(STREQ(rb_download_status_name(RB_DL_PAUSED), "PAUSED"));
    CHECK(STREQ(rb_download_status_name(RB_DL_COMPLETED), "COMPLETED"));
    CHECK(STREQ(rb_download_status_name(RB_DL_FAILED), "FAILED"));
    CHECK(STREQ(rb_download_status_name(RB_DL_CANCELLED), "CANCELLED"));
    CHECK(rb_download_status_parse("PAUSED", RB_DL_QUEUED) == RB_DL_PAUSED);
    CHECK(rb_download_status_parse("CANCELLED", RB_DL_QUEUED) == RB_DL_CANCELLED);
    CHECK(rb_download_status_parse("garbage", RB_DL_QUEUED) == RB_DL_QUEUED);
    CHECK(rb_download_status_parse(NULL, RB_DL_FAILED) == RB_DL_FAILED);
    CHECK(rb_download_status_parse("", RB_DL_FAILED) == RB_DL_FAILED);

    /* Predicates tolerate NULL. */
    CHECK(rb_download_is_active(NULL) == 0);
    CHECK(rb_download_can_pause(NULL) == 0);
    CHECK(rb_download_can_resume(NULL) == 0);

    /* --- queue scheduling --- */
    rb_downloads_free(d);
    d = rb_downloads_new();
    {
        long long enq[5];
        int i;
        int started;

        for (i = 0; i < 5; i++) {
            enq[i] = rb_downloads_enqueue(d, "p1", "https://h/f", "f.bin", "",
                                          (long long)(100 + i));
            CHECK(enq[i] != 0);
        }
        CHECK(rb_downloads_running_count(d) == 0);
        started = rb_downloads_pump(d, ids, 8);
        CHECK(started == RB_DOWNLOAD_MAX_PARALLEL);
        CHECK(rb_downloads_running_count(d) == RB_DOWNLOAD_MAX_PARALLEL);
        /* The OLDEST queued downloads start first. */
        CHECK(ids[0] == enq[0]);
        CHECK(ids[1] == enq[1]);

        /* A second pump has no free slot and must not hand the same
         * download out twice. */
        CHECK(rb_downloads_pump(d, ids, 8) == 0);
        CHECK(rb_downloads_running_count(d) == RB_DOWNLOAD_MAX_PARALLEL);

        /* Finishing one frees exactly one slot. */
        CHECK(rb_downloads_complete(d, enq[0], "/tmp/f.bin", 10, 200) == 1);
        CHECK(rb_downloads_running_count(d) == 1);
        CHECK(rb_downloads_pump(d, ids, 8) == 1);
        CHECK(ids[0] == enq[2]);
        CHECK(rb_downloads_running_count(d) == RB_DOWNLOAD_MAX_PARALLEL);

        /* max_ids caps the batch even when slots are free. */
        CHECK(rb_downloads_complete(d, enq[1], "/tmp/f.bin", 10, 201) == 1);
        CHECK(rb_downloads_complete(d, enq[2], "/tmp/f.bin", 10, 201) == 1);
        CHECK(rb_downloads_running_count(d) == 0);
        CHECK(rb_downloads_pump(d, ids, 1) == 1);
        CHECK(ids[0] == enq[3]);
        CHECK(rb_downloads_running_count(d) == 1);

        /* A PAUSED download is not queued and does not get picked up, so the
         * queue drains even with downloads still unaccounted for. */
        CHECK(rb_downloads_set_status(d, enq[3], RB_DL_PAUSED, NULL) == 1);
        CHECK(rb_downloads_set_status(d, enq[4], RB_DL_PAUSED, NULL) == 1);
        CHECK(rb_downloads_running_count(d) == 0);
        CHECK(rb_downloads_pump(d, ids, 8) == 0);

        /* Argument hygiene. */
        CHECK(rb_downloads_pump(NULL, ids, 8) == 0);
        CHECK(rb_downloads_pump(d, NULL, 8) == 0);
        CHECK(rb_downloads_pump(d, ids, 0) == 0);
        CHECK(rb_downloads_running_count(NULL) == 0);
    }

    /* --- remove / clear_profile --- */
    CHECK(rb_downloads_clear_profile(d, "p2") == 0);
    CHECK(rb_downloads_remove(d, 99999) == 0);
    {
        int before = rb_downloads_count(d);
        CHECK(rb_downloads_remove(d, 1) == 1);
        CHECK(rb_downloads_count(d) == before - 1);
        CHECK(rb_downloads_by_id(d, 1) == NULL);
        CHECK(rb_downloads_remove(d, 1) == 0);
    }
    CHECK(rb_downloads_clear_profile(d, "") == 0);
    CHECK(rb_downloads_clear_profile(d, NULL) == 0);
    /* The two calls must not share a CHECK: the operands of == may be
     * evaluated in either order, and clear_profile mutates what count reads. */
    {
        int remaining = rb_downloads_count(d);
        CHECK(rb_downloads_clear_profile(d, "p1") == remaining);
    }
    CHECK(rb_downloads_count(d) == 0);

    /* --- persistence --- */
    {
        long long a;
        long long b;
        long long c;

        /* The profile id keeps its quote (it is an opaque key and is never
         * sanitized); the suggested name's quote is sanitized to '_' on the
         * way in, so the name on disk is already filesystem-safe. */
        a = rb_downloads_enqueue(d, "p \"one\"", "https://h/caf%C3%A9.bin",
                                 "caf\xc3\xa9 \"quoted\".bin", "image/png", 500);
        b = rb_downloads_enqueue(d, "p1", "https://h/b.bin", "b.bin", "", 501);
        c = rb_downloads_enqueue(d, "p1", "https://h/c.bin", "c.bin", "", 502);
        CHECK(rb_downloads_set_progress(d, b, 7, 70) == 1);
        CHECK(rb_downloads_complete(d, c, "/tmp/c.bin", 70, 503) == 1);
        CHECK(rb_downloads_set_status(d, b, RB_DL_CANCELLED, NULL) == 1);

        CHECK(rb_downloads_save(d, TMP) == 0);
        d2 = rb_downloads_new();
        /* A load REPLACES the store rather than merging into it. */
        CHECK(rb_downloads_enqueue(d2, "stale", "https://h/stale", "s.bin", "",
                                   1) != 0);
        CHECK(rb_downloads_load(d2, TMP) == 0);
        CHECK(rb_downloads_count(d2) == 3);
        CHECK(rb_downloads_by_id(d2, a) != NULL);
        CHECK(rb_downloads_by_id(d2, b) != NULL);
        CHECK(rb_downloads_by_id(d2, c) != NULL);

        dl = rb_downloads_by_id(d2, a);
        CHECK(STREQ(dl->profile_id, "p \"one\""));
        CHECK(STREQ(dl->file_name, "caf\xc3\xa9 _quoted_.bin"));
        /* The URL is stored verbatim: percent-encoding is not re-decoded on
         * the way through the store. */
        CHECK(STREQ(dl->url, "https://h/caf%C3%A9.bin"));
        CHECK(dl->created_at == 500);
        CHECK(dl->status == RB_DL_QUEUED);

        dl = rb_downloads_by_id(d2, b);
        CHECK(dl->status == RB_DL_CANCELLED);
        CHECK(dl->downloaded_bytes == 7);
        CHECK(dl->total_bytes == 70);
        CHECK(STREQ(dl->mime_type, "application/octet-stream"));

        dl = rb_downloads_by_id(d2, c);
        CHECK(dl->status == RB_DL_COMPLETED);
        CHECK(STREQ(dl->destination, "/tmp/c.bin"));
        CHECK(dl->completed_at == 503);

        /* Order survives: newest first, exactly as written. */
        CHECK(rb_downloads_at(d2, 0)->id == c);
        CHECK(rb_downloads_at(d2, 2)->id == a);

        /* Ids from the file are not reissued to new downloads. */
        CHECK(rb_downloads_enqueue(d2, "p1", "https://h/new.bin", "new.bin", "",
                                   600) > c);

        rb_downloads_free(d2);
        remove(TMP);
    }

    /* A missing file is fine and leaves the store empty. */
    d2 = rb_downloads_new();
    CHECK(rb_downloads_load(d2, "rb-test-does-not-exist.txt") == 0);
    CHECK(rb_downloads_count(d2) == 0);

    /* Malformed and unusable lines are skipped, not fatal. */
    {
        FILE *f = fopen(TMP, "wb");
        CHECK(f != NULL);
        if (f != NULL) {
            fprintf(f, "not json at all\n");
            fprintf(f, "{\"url\":\"https://h/no-owner\"}\n");
            fprintf(f, "{\"profile_id\":\"p1\"}\n");
            fprintf(f, "{\"profile_id\":\"p1\",\"url\":\"https://h/ok\","
                       "\"file_name\":\"ok.bin\",\"status\":\"NONSENSE\","
                       "\"total_bytes\":\"99\"}\n");
            fclose(f);
        }
        CHECK(rb_downloads_load(d2, TMP) == 0);
        CHECK(rb_downloads_count(d2) == 1);
        dl = rb_downloads_at(d2, 0);
        CHECK(STREQ(dl->url, "https://h/ok"));
        CHECK(STREQ(dl->file_name, "ok.bin"));
        /* An unknown status falls back to QUEUED instead of losing the row,
         * and a number written as a string is still read. */
        CHECK(dl->status == RB_DL_QUEUED);
        CHECK(dl->total_bytes == 99);
        CHECK(dl->id > 0);
        rb_downloads_free(d2);
        remove(TMP);
    }

    CHECK(rb_downloads_save(NULL, TMP) == -1);
    CHECK(rb_downloads_save(d, NULL) == -1);
    CHECK(rb_downloads_load(NULL, TMP) == -1);
    CHECK(rb_downloads_load(d, NULL) == -1);

    rb_downloads_free(d);
    rb_downloads_free(NULL); /* must not crash */
}

/* A byte count must read the same on both editions.  Every expectation below is
 * also asserted by android/core/domain's DownloadFormatTest: the two formatters
 * are separate implementations in separate languages, and the only thing
 * keeping them honest is that they answer the same questions the same way. */
static void check_bytes(long long value, const char *want, int line)
{
    char buf[32];

    rb_download_format_bytes(value, buf, sizeof buf);
    g_checks++;
    if (!STREQ(buf, want)) {
        fprintf(stderr, "FAIL %s:%d: %lld formatted as \"%s\", want \"%s\"\n",
                __FILE__, line, value, buf, want);
        exit(1);
    }
}

#define CHECK_BYTES(value, want) check_bytes((value), (want), __LINE__)

static void test_rb_download_format(void)
{
    char buf[32];

    /* Below a kilobyte the count stays exact. */
    CHECK_BYTES(0, "0 B");
    CHECK_BYTES(1, "1 B");
    CHECK_BYTES(1023, "1023 B");

    /* One of each unit, and the two-gigabyte case the old screen printed as
     * "2097152 KB" — it divided by 1024 once and called the result KB. */
    CHECK_BYTES(1024LL, "1.0 KB");
    CHECK_BYTES(1024LL * 1024, "1.0 MB");
    CHECK_BYTES(1024LL * 1024 * 1024, "1.0 GB");
    CHECK_BYTES(1024LL * 1024 * 1024 * 1024, "1.0 TB");
    CHECK_BYTES(2LL * 1024 * 1024 * 1024, "2.0 GB");

    /* Above a hundred the tenth is noise on a figure the user is glancing at. */
    CHECK_BYTES(150LL * 1024 * 1024, "150 MB");
    CHECK_BYTES(999LL * 1024 * 1024, "999 MB");

    /* Both thresholds are decided on the ROUNDED figure, because that is the
     * one that gets printed.  104_805_417 bytes is 99.9502 MB: under 100, but
     * it prints as 100, so it must take the no-tenth branch rather than
     * printing "100.0 MB".  1_048_300 bytes is 1023.7 KB, which rounds to
     * "1024 KB" — a figure the megabyte exists for. */
    CHECK_BYTES(104805417LL, "100 MB");
    CHECK_BYTES(1048300LL, "1.0 MB");

    /* The boundary between the two branches, and the reason the branch is
     * decided on the whole figure rather than the tenth: 104_280_884 bytes is
     * 99.4499 MB, whose whole figure is 99 but whose tenth figure is 995.  A
     * formatter that tested the tenth would print "100 MB" here. */
    CHECK_BYTES(104280884LL, "99.5 MB");
    CHECK_BYTES(104333312LL, "100 MB"); /* exactly 99.5 MB */

    /* Negative is the unknown-length sentinel, never a negative size. */
    CHECK_BYTES(-1, "?");
    CHECK_BYTES(-1024, "?");

    /* A buffer too small truncates but stays NUL-terminated, and writes nothing
     * past its cap. */
    memset(buf, 'x', sizeof buf);
    rb_download_format_bytes(150LL * 1024 * 1024, buf, 5);
    CHECK(STREQ(buf, "150 "));
    CHECK(buf[5] == 'x');

    memset(buf, 'x', sizeof buf);
    rb_download_format_bytes(150LL * 1024 * 1024, buf, 1);
    CHECK(buf[0] == '\0');
    CHECK(buf[1] == 'x');

    /* Degenerate arguments must not crash. */
    rb_download_format_bytes(1024, NULL, sizeof buf);
    rb_download_format_bytes(1024, buf, 0);
}

static void test_rb_downloads_scoped(void)
{
    rb_downloads *d = rb_downloads_new();
    long long a;
    long long b;
    long long c;

    a = rb_downloads_enqueue(d, "p1", "https://h/a.bin", "a.bin", "", 100);
    b = rb_downloads_enqueue(d, "p2", "https://h/b.bin", "b.bin", "", 200);
    c = rb_downloads_enqueue(d, "p1", "https://h/c.bin", "c.bin", "", 300);
    CHECK(a != 0 && b != 0 && c != 0);

    /* A blank or absent profile is the "every profile" case, not a filter that
     * matches nothing — that is what makes the two editions agree when they
     * have no active profile to hand. */
    CHECK(rb_downloads_count(d) == 3);
    CHECK(rb_downloads_count_for(d, NULL) == 3);
    CHECK(rb_downloads_count_for(d, "") == 3);

    CHECK(rb_downloads_count_for(d, "p1") == 2);
    CHECK(rb_downloads_count_for(d, "p2") == 1);
    CHECK(rb_downloads_count_for(d, "p3") == 0);

    /* Newest first within the profile, and another profile's row is not part of
     * the numbering: p1's list is c then a, never c then b. */
    CHECK(rb_downloads_at_for(d, "p1", 0)->id == c);
    CHECK(rb_downloads_at_for(d, "p1", 1)->id == a);
    CHECK(rb_downloads_at_for(d, "p1", 2) == NULL);
    CHECK(rb_downloads_at_for(d, "p2", 0)->id == b);
    CHECK(rb_downloads_at_for(d, "p2", 1) == NULL);
    CHECK(rb_downloads_at_for(d, "p3", 0) == NULL);
    CHECK(rb_downloads_at_for(d, NULL, 0)->id == c);
    CHECK(rb_downloads_at_for(d, NULL, 2)->id == a);

    /* Degenerate arguments answer NULL rather than reading off the front of the
     * array. */
    CHECK(rb_downloads_at_for(d, "p1", -1) == NULL);
    CHECK(rb_downloads_at_for(NULL, "p1", 0) == NULL);
    CHECK(rb_downloads_count_for(NULL, "p1") == 0);

    rb_downloads_free(d);
}

static void test_rb_downloads_reconcile(void)
{
    rb_downloads *d = rb_downloads_new();
    const rb_download *dl;
    long long queued;
    long long running;
    long long paused;
    long long done;
    long long failed;
    long long cancelled;
    long long other;

    queued = rb_downloads_enqueue(d, "p1", "https://h/q", "q.bin", "", 10);
    running = rb_downloads_enqueue(d, "p1", "https://h/r", "r.bin", "", 20);
    paused = rb_downloads_enqueue(d, "p1", "https://h/p", "p.bin", "", 30);
    done = rb_downloads_enqueue(d, "p1", "https://h/d", "d.bin", "", 40);
    failed = rb_downloads_enqueue(d, "p1", "https://h/f", "f.bin", "", 50);
    cancelled = rb_downloads_enqueue(d, "p1", "https://h/c", "c.bin", "", 60);
    other = rb_downloads_enqueue(d, "p2", "https://h/o", "o.bin", "", 70);

    CHECK(rb_downloads_set_status(d, running, RB_DL_RUNNING, NULL) == 1);
    CHECK(rb_downloads_set_status(d, paused, RB_DL_PAUSED, NULL) == 1);
    CHECK(rb_downloads_complete(d, done, "/tmp/d.bin", 42, 45) == 1);
    CHECK(rb_downloads_set_status(d, failed, RB_DL_FAILED, "connection reset")
          == 1);
    CHECK(rb_downloads_set_status(d, cancelled, RB_DL_CANCELLED, NULL) == 1);

    /* Only the profile being left behind: p2's queued row still has a transfer
     * coming to it, and must survive. */
    CHECK(rb_downloads_reconcile(d, "p1", "Restarted") == 2);
    dl = rb_downloads_by_id(d, queued);
    CHECK(dl->status == RB_DL_FAILED);
    CHECK(STREQ(dl->error, "Restarted"));
    dl = rb_downloads_by_id(d, running);
    CHECK(dl->status == RB_DL_FAILED);
    CHECK(STREQ(dl->error, "Restarted"));
    CHECK(rb_downloads_by_id(d, other)->status == RB_DL_QUEUED);

    /* Everything else is left exactly as it was.  A PAUSED row in particular:
     * its partial file is still on disk and the user asked for the stop, so
     * calling it failed would be a claim about a transfer nobody interrupted.
     * And a FAILED row keeps its own reason rather than gaining this one. */
    CHECK(rb_downloads_by_id(d, paused)->status == RB_DL_PAUSED);
    CHECK(rb_downloads_by_id(d, done)->status == RB_DL_COMPLETED);
    CHECK(STREQ(rb_downloads_by_id(d, done)->destination, "/tmp/d.bin"));
    dl = rb_downloads_by_id(d, failed);
    CHECK(dl->status == RB_DL_FAILED);
    CHECK(STREQ(dl->error, "connection reset"));
    CHECK(rb_downloads_by_id(d, cancelled)->status == RB_DL_CANCELLED);

    /* Nothing left to demote, so a startup that reconciles twice does not
     * rewrite the store on the second pass. */
    CHECK(rb_downloads_reconcile(d, "p1", "Restarted") == 0);

    /* A blank profile is every profile, and a blank reason still records
     * something a user can read rather than an empty error line. */
    CHECK(rb_downloads_reconcile(d, NULL, NULL) == 1);
    dl = rb_downloads_by_id(d, other);
    CHECK(dl->status == RB_DL_FAILED);
    CHECK(STREQ(dl->error, "Interrupted"));
    CHECK(rb_downloads_reconcile(d, "", "x") == 0);
    CHECK(rb_downloads_reconcile(NULL, "p1", "x") == 0);

    rb_downloads_free(d);
}

/* -------------------------------- rb_dns --------------------------------- */

static void test_rb_dns(void)
{
    /* mode names are the on-disk contract, in declaration order */
    CHECK(STREQ(rb_dns_mode_name(RB_DNS_SYSTEM), "system"));
    CHECK(STREQ(rb_dns_mode_name(RB_DNS_AUTO), "auto"));
    CHECK(STREQ(rb_dns_mode_name(RB_DNS_DOH), "doh"));
    CHECK(STREQ(rb_dns_mode_name(RB_DNS_DOT), "dot"));
    CHECK(STREQ(rb_dns_mode_name((rb_dns_mode)99), "system"));
    CHECK(STREQ(rb_dns_mode_name((rb_dns_mode)-1), "system"));
    CHECK(rb_dns_mode_parse("system") == RB_DNS_SYSTEM);
    CHECK(rb_dns_mode_parse("auto") == RB_DNS_AUTO);
    CHECK(rb_dns_mode_parse("doh") == RB_DNS_DOH);
    CHECK(rb_dns_mode_parse("dot") == RB_DNS_DOT);
    CHECK(rb_dns_mode_parse("DOH") == RB_DNS_DOH);
    CHECK(rb_dns_mode_parse("DoT") == RB_DNS_DOT);
    CHECK(rb_dns_mode_parse("  dot") == RB_DNS_DOT);
    CHECK(rb_dns_mode_parse("dnsoverhttps") == RB_DNS_SYSTEM); /* no prefix match */
    CHECK(rb_dns_mode_parse("") == RB_DNS_SYSTEM);
    CHECK(rb_dns_mode_parse(NULL) == RB_DNS_SYSTEM);
    /* ProfileSettings' default is SYSTEM, so an unknown value must not be
     * silently promoted to a resolver the user never chose. */
    CHECK(rb_dns_mode_parse("garbage") == RB_DNS_SYSTEM);

    CHECK(STREQ(rb_dns_status_name(RB_DNS_STATUS_SYSTEM), "system"));
    CHECK(STREQ(rb_dns_status_name(RB_DNS_STATUS_PROTECTED_DOH), "protected_doh"));
    CHECK(STREQ(rb_dns_status_name(RB_DNS_STATUS_PROTECTED_DOT), "protected_dot"));
    CHECK(STREQ(rb_dns_status_name(RB_DNS_STATUS_MISCONFIGURED), "misconfigured"));
    CHECK(STREQ(rb_dns_status_name((rb_dns_status)42), "system"));

    /* --- validateDohUrl --- */
    CHECK(rb_dns_valid_doh_url("https://dns.google/dns-query") == 1);
    CHECK(rb_dns_valid_doh_url("https://dns.google") == 1);
    CHECK(rb_dns_valid_doh_url("HTTPS://DNS.GOOGLE/dns-query") == 1);
    CHECK(rb_dns_valid_doh_url("https://cloudflare-dns.com/dns-query?ct=application%2Fdns-json") == 1);
    CHECK(rb_dns_valid_doh_url("https://dns.google:443/dns-query") == 1);
    CHECK(rb_dns_valid_doh_url("https://user@dns.google/dns-query") == 1);
    CHECK(rb_dns_valid_doh_url("https://[2606:4700:4700::1111]/dns-query") == 1);
    CHECK(rb_dns_valid_doh_url("https://1.1.1.1/dns-query") == 1);
    /* String.trim() runs first, so an editor's newline is not fatal */
    CHECK(rb_dns_valid_doh_url("  https://dns.google/dns-query\n") == 1);

    CHECK(rb_dns_valid_doh_url(NULL) == 0);
    CHECK(rb_dns_valid_doh_url("") == 0);
    CHECK(rb_dns_valid_doh_url("   ") == 0);
    CHECK(rb_dns_valid_doh_url("https://") == 0);          /* no host */
    CHECK(rb_dns_valid_doh_url("https:///dns-query") == 0); /* no authority */
    CHECK(rb_dns_valid_doh_url("https://:443/x") == 0);
    CHECK(rb_dns_valid_doh_url("https://user@/x") == 0);   /* empty host */
    CHECK(rb_dns_valid_doh_url("http://dns.google/dns-query") == 0);
    CHECK(rb_dns_valid_doh_url("dns.google") == 0);
    CHECK(rb_dns_valid_doh_url("ftp://dns.google") == 0);
    /* Java's URI refuses these outright */
    CHECK(rb_dns_valid_doh_url("https://dns google/dns-query") == 0);
    CHECK(rb_dns_valid_doh_url("https://dns.google\t/x") == 0);

    /* --- validateDotHostname --- */
    CHECK(rb_dns_valid_dot_hostname("dns.google") == 1);
    CHECK(rb_dns_valid_dot_hostname("dns") == 1); /* a bare label is a host */
    CHECK(rb_dns_valid_dot_hostname("one.one.one.one") == 1);
    CHECK(rb_dns_valid_dot_hostname("dns.google:853") == 1);
    CHECK(rb_dns_valid_dot_hostname("my_dns-1.example") == 1);
    CHECK(rb_dns_valid_dot_hostname("  dns.google  ") == 1);
    CHECK(rb_dns_valid_dot_hostname("dns.google\n") == 1);

    CHECK(rb_dns_valid_dot_hostname(NULL) == 0);
    CHECK(rb_dns_valid_dot_hostname("") == 0);
    CHECK(rb_dns_valid_dot_hostname("   ") == 0);
    CHECK(rb_dns_valid_dot_hostname(":853") == 0);       /* no host part */
    CHECK(rb_dns_valid_dot_hostname("dns..google") == 0); /* empty label */
    CHECK(rb_dns_valid_dot_hostname("dns.google.") == 0);
    CHECK(rb_dns_valid_dot_hostname("dns/google") == 0);
    CHECK(rb_dns_valid_dot_hostname("dns google") == 0);
    CHECK(rb_dns_valid_dot_hostname("dns.google/x") == 0);
    CHECK(rb_dns_valid_dot_hostname("dns!.google") == 0);
    {
        char big[300];
        char label[80];

        memset(label, 'a', 64);
        label[64] = '\0';
        snprintf(big, sizeof(big), "%s.example", label);
        CHECK(rb_dns_valid_dot_hostname(big) == 0); /* label over 63 */

        memset(label, 'a', 63);
        label[63] = '\0';
        snprintf(big, sizeof(big), "%s.example", label);
        CHECK(rb_dns_valid_dot_hostname(big) == 1); /* exactly 63 is fine */

        memset(big, 'a', 254);
        big[254] = '\0';
        CHECK(rb_dns_valid_dot_hostname(big) == 0); /* over 253 */
    }

    /* --- effective(): profile SYSTEM is final --- */
    {
        rb_dns_effective e = rb_dns_resolve("system", "https://dns.google/q",
                                            "dns.google", "doh",
                                            "https://global.example/q",
                                            "global.example");
        CHECK(e.mode == RB_DNS_SYSTEM);
        CHECK(e.status == RB_DNS_STATUS_SYSTEM);
        CHECK(e.doh_url == NULL);
        CHECK(e.dot_hostname == NULL);
        rb_dns_effective_free(&e);

        /* NULL/absent reads as the same thing — the desktop spelling of
         * Kotlin's null profile setting */
        e = rb_dns_resolve(NULL, NULL, NULL, "doh", "https://g/q", "g");
        CHECK(e.mode == RB_DNS_SYSTEM);
        CHECK(e.status == RB_DNS_STATUS_SYSTEM);
        rb_dns_effective_free(&e);
    }

    /* --- effective(): the profile's own mode wins --- */
    {
        rb_dns_effective e = rb_dns_resolve("doh", "https://dns.google/q", NULL,
                                            "dot", "https://g/q", "g");
        CHECK(e.mode == RB_DNS_DOH);
        CHECK(e.status == RB_DNS_STATUS_PROTECTED_DOH);
        CHECK(STREQ(e.doh_url, "https://dns.google/q"));
        CHECK(e.dot_hostname == NULL);
        rb_dns_effective_free(&e);

        e = rb_dns_resolve("dot", NULL, "one.one.one.one", "doh", "https://g/q",
                           "g");
        CHECK(e.mode == RB_DNS_DOT);
        CHECK(e.status == RB_DNS_STATUS_PROTECTED_DOT);
        CHECK(STREQ(e.dot_hostname, "one.one.one.one"));
        CHECK(e.doh_url == NULL);
        rb_dns_effective_free(&e);
    }

    /* --- effective(): AUTO rides the global mode --- */
    {
        rb_dns_effective e = rb_dns_resolve("auto", NULL, NULL, "doh",
                                            "https://global.example/q",
                                            "global.example");
        CHECK(e.mode == RB_DNS_DOH);
        CHECK(e.status == RB_DNS_STATUS_PROTECTED_DOH);
        CHECK(STREQ(e.doh_url, "https://global.example/q"));
        rb_dns_effective_free(&e);

        e = rb_dns_resolve("auto", NULL, NULL, "dot", "https://g/q", "g.example");
        CHECK(e.mode == RB_DNS_DOT);
        CHECK(e.status == RB_DNS_STATUS_PROTECTED_DOT);
        CHECK(STREQ(e.dot_hostname, "g.example"));
        rb_dns_effective_free(&e);

        /* AUTO against a global AUTO (or system) is the system resolver */
        e = rb_dns_resolve("auto", NULL, NULL, "auto", NULL, NULL);
        CHECK(e.mode == RB_DNS_AUTO);
        CHECK(e.status == RB_DNS_STATUS_SYSTEM);
        CHECK(e.doh_url == NULL);
        CHECK(e.dot_hostname == NULL);
        rb_dns_effective_free(&e);

        e = rb_dns_resolve("auto", NULL, NULL, "system", NULL, NULL);
        CHECK(e.mode == RB_DNS_SYSTEM);
        CHECK(e.status == RB_DNS_STATUS_SYSTEM);
        rb_dns_effective_free(&e);
    }

    /* --- effective(): an AUTO profile must NOT use its own DoH URL --- */
    {
        rb_dns_effective e = rb_dns_resolve("auto", "https://profile.example/q",
                                            "profile.example", "doh",
                                            "https://global.example/q", "g");
        CHECK(e.mode == RB_DNS_DOH);
        CHECK(STREQ(e.doh_url, "https://global.example/q")); /* not the profile's */
        rb_dns_effective_free(&e);
    }

    /* --- effective(): DoT selected, DoH value present, is still misconfigured
     *     for want of a hostname --- */
    {
        rb_dns_effective e = rb_dns_resolve("dot", "https://dns.google/q", "",
                                            "system", NULL, NULL);
        CHECK(e.mode == RB_DNS_DOT);
        CHECK(e.status == RB_DNS_STATUS_MISCONFIGURED);
        CHECK(e.dot_hostname == NULL);
        rb_dns_effective_free(&e);
    }

    /* --- effective(): a bad value is MISCONFIGURED, and kept for the UI --- */
    {
        rb_dns_effective e = rb_dns_resolve("doh", "http://dns.google/q", NULL,
                                            "system", NULL, NULL);
        CHECK(e.mode == RB_DNS_DOH);
        CHECK(e.status == RB_DNS_STATUS_MISCONFIGURED);
        CHECK(STREQ(e.doh_url, "http://dns.google/q"));
        rb_dns_effective_free(&e);

        e = rb_dns_resolve("doh", "   ", NULL, "system", NULL, NULL);
        CHECK(e.status == RB_DNS_STATUS_MISCONFIGURED);
        CHECK(e.doh_url == NULL); /* all-whitespace counts as unset */
        rb_dns_effective_free(&e);

        e = rb_dns_resolve("dot", NULL, "dns..google", "system", NULL, NULL);
        CHECK(e.mode == RB_DNS_DOT);
        CHECK(e.status == RB_DNS_STATUS_MISCONFIGURED);
        CHECK(STREQ(e.dot_hostname, "dns..google"));
        rb_dns_effective_free(&e);
    }

    /* --- effective(): a DoH profile with nothing set anywhere falls back to
     *     the global URL rather than reporting system --- */
    {
        rb_dns_effective e = rb_dns_resolve("doh", NULL, NULL, "system",
                                            "https://global.example/q", NULL);
        CHECK(e.mode == RB_DNS_DOH);
        CHECK(e.status == RB_DNS_STATUS_PROTECTED_DOH);
        CHECK(STREQ(e.doh_url, "https://global.example/q"));
        rb_dns_effective_free(&e);
    }

    rb_dns_effective_free(NULL); /* must not crash */
}

/* ---------------------------- rb_ipconflict ------------------------------ */

#define RB_IP_DAY_MS 86400000LL
#define RB_IP_NOW 1700000000000LL

static void test_rb_ipconflict(void)
{
    /* --- rb_ip_is_valid --- */
    CHECK(rb_ip_is_valid("1.2.3.4") == 1);
    CHECK(rb_ip_is_valid("0.0.0.0") == 1);
    CHECK(rb_ip_is_valid("255.255.255.255") == 1);
    CHECK(rb_ip_is_valid(" 1.2.3.4 ") == 1); /* trimmed before checking */
    CHECK(rb_ip_is_valid("::1") == 1);
    CHECK(rb_ip_is_valid("fe80::1") == 1);
    CHECK(rb_ip_is_valid("2001:0db8:85a3:0000:0000:8a2e:0370:7334") == 1);

    CHECK(rb_ip_is_valid(NULL) == 0);
    CHECK(rb_ip_is_valid("") == 0);
    CHECK(rb_ip_is_valid("   ") == 0);
    CHECK(rb_ip_is_valid("256.1.1.1") == 0);
    CHECK(rb_ip_is_valid("1.2.3") == 0);
    CHECK(rb_ip_is_valid("1.2.3.4.5") == 0);
    CHECK(rb_ip_is_valid("1.2.3.") == 0);
    CHECK(rb_ip_is_valid("a.b.c.d") == 0);
    CHECK(rb_ip_is_valid("1.2.3.4 ") == 1);
    CHECK(rb_ip_is_valid("1 .2.3.4") == 0);
    /* the loose IPv6 shape: at least two colons, groups of at most 4 hex
     * digits — so a lone colon is not a literal */
    CHECK(rb_ip_is_valid("1:2") == 0);
    CHECK(rb_ip_is_valid("12345::1") == 0); /* five hex digits */
    /* the tail takes at most TWO dot groups, so even a well-formed embedded
     * IPv4 is not a literal here — the Kotlin regex says the same */
    CHECK(rb_ip_is_valid("::ffff:1.2.3") == 1);
    CHECK(rb_ip_is_valid("::ffff:1.2.3.4") == 0);
    CHECK(rb_ip_is_valid("::ffff:1.2.3.4.5") == 0);

    /* --- record: upsert, preserving first_seen_at --- */
    {
        rb_ip_history *h = rb_ip_history_new();
        const rb_ip_assoc *row;

        CHECK(rb_ip_history_count(h) == 0);
        CHECK(rb_ip_history_record(h, "p1", "1.2.3.4", RB_IP_NOW) == 1);
        CHECK(rb_ip_history_count(h) == 1);
        row = rb_ip_history_find(h, "p1", "1.2.3.4");
        CHECK(row != NULL);
        CHECK(row->first_seen_at == RB_IP_NOW);
        CHECK(row->last_seen_at == RB_IP_NOW);

        CHECK(rb_ip_history_record(h, "p1", "1.2.3.4",
                                   RB_IP_NOW + 5 * RB_IP_DAY_MS) == 0);
        CHECK(rb_ip_history_count(h) == 1);
        row = rb_ip_history_find(h, "p1", "1.2.3.4");
        CHECK(row->first_seen_at == RB_IP_NOW); /* preserved */
        CHECK(row->last_seen_at == RB_IP_NOW + 5 * RB_IP_DAY_MS);

        /* a different profile at the same address is a different row */
        CHECK(rb_ip_history_record(h, "p2", "1.2.3.4", RB_IP_NOW) == 1);
        CHECK(rb_ip_history_count(h) == 2);
        /* and so is the same profile elsewhere */
        CHECK(rb_ip_history_record(h, "p1", "5.6.7.8", RB_IP_NOW) == 1);
        CHECK(rb_ip_history_count(h) == 3);

        /* padded input lands on the same row, not a new one */
        CHECK(rb_ip_history_record(h, "p1", "  1.2.3.4\n",
                                   RB_IP_NOW + 5 * RB_IP_DAY_MS) == 0);
        CHECK(rb_ip_history_count(h) == 3);

        /* blank profile id, blank ip, and an unparseable address are refused */
        CHECK(rb_ip_history_record(h, "", "1.2.3.4", RB_IP_NOW) == 0);
        CHECK(rb_ip_history_record(h, NULL, "1.2.3.4", RB_IP_NOW) == 0);
        CHECK(rb_ip_history_record(h, "p1", "", RB_IP_NOW) == 0);
        CHECK(rb_ip_history_record(h, "p1", NULL, RB_IP_NOW) == 0);
        CHECK(rb_ip_history_record(h, "p1", "not-an-ip", RB_IP_NOW) == 0);
        CHECK(rb_ip_history_record(h, "p1", "999.1.1.1", RB_IP_NOW) == 0);
        CHECK(rb_ip_history_count(h) == 3);

        CHECK(rb_ip_history_find(h, "p9", "1.2.3.4") == NULL);
        CHECK(rb_ip_history_find(h, "p1", "9.9.9.9") == NULL);
        CHECK(rb_ip_history_find(h, NULL, "1.2.3.4") == NULL);
        CHECK(rb_ip_history_at(h, -1) == NULL);
        CHECK(rb_ip_history_at(h, 3) == NULL);
        CHECK(rb_ip_history_at(h, 0) != NULL);
        CHECK(rb_ip_history_count(NULL) == 0);
        CHECK(rb_ip_history_at(NULL, 0) == NULL);
        CHECK(rb_ip_history_find(NULL, "p1", "1.2.3.4") == NULL);
        CHECK(rb_ip_history_record(NULL, "p1", "1.2.3.4", RB_IP_NOW) == 0);

        /* --- retention --- */
        CHECK(rb_ip_retention_cutoff(RB_IPCONFLICT_RETENTION_FOREVER,
                                     RB_IP_NOW) == 0);
        CHECK(rb_ip_retention_cutoff(30, RB_IP_NOW) ==
              RB_IP_NOW - 30 * RB_IP_DAY_MS);
        CHECK(rb_ip_retention_cutoff(0, RB_IP_NOW) == RB_IP_NOW);
        /* a negative window is clamped, not a cutoff in the future */
        CHECK(rb_ip_retention_cutoff(-5, RB_IP_NOW) == RB_IP_NOW);

        /* FOREVER purges nothing, however old the row is */
        CHECK(rb_ip_history_purge(h, RB_IPCONFLICT_RETENTION_FOREVER,
                                  RB_IP_NOW + 10000 * RB_IP_DAY_MS) == 0);
        CHECK(rb_ip_history_count(h) == 3);

        /* 30 days: only p1@1.2.3.4 was seen again, the other two are stale */
        CHECK(rb_ip_history_purge(h, 30, RB_IP_NOW + 5 * RB_IP_DAY_MS +
                                             30 * RB_IP_DAY_MS) == 2);
        CHECK(rb_ip_history_count(h) == 1);
        CHECK(rb_ip_history_find(h, "p1", "1.2.3.4") != NULL);
        CHECK(rb_ip_history_purge(NULL, 30, RB_IP_NOW) == 0);

        rb_ip_history_free(h);
        rb_ip_history_free(NULL); /* must not crash */
    }

    /* --- the JSONL round trip --- */
    {
        rb_ip_history *h = rb_ip_history_new();
        rb_ip_history *back = rb_ip_history_new();

        CHECK(rb_ip_history_record(h, "prof-a", "1.2.3.4", RB_IP_NOW) == 1);
        CHECK(rb_ip_history_record(h, "prof-b", "2001:db8::1",
                                   RB_IP_NOW + RB_IP_DAY_MS) == 1);
        CHECK(rb_ip_history_save(h, TMP) == 0);
        CHECK(rb_ip_history_load(back, TMP) == 0);
        CHECK(rb_ip_history_count(back) == 2);
        CHECK(STREQ(rb_ip_history_at(back, 0)->profile_id, "prof-a"));
        CHECK(STREQ(rb_ip_history_at(back, 0)->ip, "1.2.3.4"));
        CHECK(rb_ip_history_at(back, 0)->first_seen_at == RB_IP_NOW);
        CHECK(rb_ip_history_at(back, 0)->last_seen_at == RB_IP_NOW);
        CHECK(STREQ(rb_ip_history_at(back, 1)->profile_id, "prof-b"));
        CHECK(STREQ(rb_ip_history_at(back, 1)->ip, "2001:db8::1"));
        CHECK(rb_ip_history_at(back, 1)->first_seen_at ==
              RB_IP_NOW + RB_IP_DAY_MS);

        /* a missing file loads as empty, and a junk line is skipped */
        {
            FILE *f = fopen(TMP, "wb");

            CHECK(f != NULL);
            fputs("not json at all\n", f);
            fputs("{\"profile_id\":\"\",\"ip\":\"1.1.1.1\"}\n", f);
            fputs("{\"profile_id\":\"prof-c\",\"ip\":\"9.9.9.9\"}\n", f);
            fclose(f);
        }
        {
            rb_ip_history *junk = rb_ip_history_new();

            CHECK(rb_ip_history_load(junk, TMP) == 0);
            CHECK(rb_ip_history_count(junk) == 1);
            CHECK(STREQ(rb_ip_history_at(junk, 0)->profile_id, "prof-c"));
            CHECK(rb_ip_history_at(junk, 0)->first_seen_at == 0);
            rb_ip_history_free(junk);
        }

        remove(TMP);
        CHECK(rb_ip_history_load(back, TMP) == 0);
        CHECK(rb_ip_history_count(back) == 2); /* untouched by the re-load */

        CHECK(rb_ip_history_save(NULL, TMP) == -1);
        CHECK(rb_ip_history_save(h, NULL) == -1);
        CHECK(rb_ip_history_load(NULL, TMP) == -1);
        CHECK(rb_ip_history_load(h, NULL) == -1);

        rb_ip_history_free(back);
        rb_ip_history_free(h);
    }

    /* --- check(): the order of the guards --- */
    {
        rb_settings *g = rb_settings_new();
        rb_ip_history *h = rb_ip_history_new();
        rb_ip_check c;

        rb_prefs_global_defaults(g);
        /* a row from ANOTHER profile at the address we are about to use */
        CHECK(rb_ip_history_record(h, "other", "1.2.3.4", RB_IP_NOW) == 1);

        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW + RB_IP_DAY_MS);
        CHECK(c.has_conflict == 1);
        CHECK(c.should_warn == 1);
        CHECK(c.requires_confirmation == 0); /* informational by default */
        CHECK(STREQ(c.current_profile_id, "me"));
        CHECK(STREQ(c.current_ip, "1.2.3.4"));
        CHECK(STREQ(c.previous_profile_id, "other"));
        CHECK(c.previous_last_seen_at == RB_IP_NOW);
        rb_ip_check_free(&c);

        /* the global switch is checked before anything else */
        rb_settings_set_int(g, RB_GPREF_NET_PROTECT_ENABLED, 0);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* then the per-profile switch */
        rb_settings_set_int(g, RB_GPREF_NET_PROTECT_ENABLED, 1);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 0, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* then the warning behaviour */
        rb_settings_set(g, RB_GPREF_WARNING_BEHAVIOR, "dont_warn");
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* then address validity — an unparseable address cannot conflict */
        rb_settings_set(g, RB_GPREF_WARNING_BEHAVIOR,
                              "ask_every_time");
        c = rb_ip_check_run(h, "me", "nonsense", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        c = rb_ip_check_run(h, "me", NULL, g, 1, NULL, NULL, NULL, RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* then the per-address suppression */
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, "5.5.5.5\n1.2.3.4", NULL,
                            NULL, RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* and the per-profile suppression */
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, "other", NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* the same profile at the same address is never a conflict */
        c = rb_ip_check_run(h, "other", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* a different address is not a conflict either */
        c = rb_ip_check_run(h, "me", "9.9.9.9", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        /* --- severity --- */
        rb_settings_set(g, RB_GPREF_CONFLICT_SEVERITY,
                              "require_confirmation");
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 1);
        CHECK(c.requires_confirmation == 1);
        rb_ip_check_free(&c);

        /* --- once_per_network: warned once, then quiet --- */
        rb_settings_set(g, RB_GPREF_WARNING_BEHAVIOR,
                              "once_per_network");
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 1);
        rb_ip_check_free(&c);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, "1.2.3.4",
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);
        /* a *different* address is still unwarned, so it still warns */
        rb_settings_set(g, RB_GPREF_WARNING_BEHAVIOR,
                              "ask_every_time");
        rb_settings_set(g, RB_GPREF_CONFLICT_SEVERITY,
                              "informational");

        /* --- retention: a row outside the window is not a conflict --- */
        rb_settings_set_int(g, RB_GPREF_RETENTION, 30);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW + 31 * RB_IP_DAY_MS);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW + 29 * RB_IP_DAY_MS);
        CHECK(c.has_conflict == 1);
        rb_ip_check_free(&c);
        /* FOREVER keeps it however long ago it was */
        rb_settings_set_int(g, RB_GPREF_RETENTION,
                                  RB_IPCONFLICT_RETENTION_FOREVER);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW + 100000 * RB_IP_DAY_MS);
        CHECK(c.has_conflict == 1);
        rb_ip_check_free(&c);

        /* the latest other-profile sighting is the one reported */
        CHECK(rb_ip_history_record(h, "other2", "1.2.3.4",
                                   RB_IP_NOW + 2 * RB_IP_DAY_MS) == 1);
        c = rb_ip_check_run(h, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW + 3 * RB_IP_DAY_MS);
        CHECK(c.has_conflict == 1);
        CHECK(STREQ(c.previous_profile_id, "other2"));
        CHECK(c.previous_last_seen_at == RB_IP_NOW + 2 * RB_IP_DAY_MS);
        rb_ip_check_free(&c);

        /* NULL settings / NULL profile id yield "no conflict", not a crash */
        c = rb_ip_check_run(h, "me", "1.2.3.4", NULL, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);
        c = rb_ip_check_run(h, NULL, "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);
        c = rb_ip_check_run(NULL, "me", "1.2.3.4", g, 1, NULL, NULL, NULL,
                            RB_IP_NOW);
        CHECK(c.has_conflict == 0);
        rb_ip_check_free(&c);

        rb_ip_check_free(NULL); /* must not crash */
        rb_ip_history_free(h);
        rb_settings_free(g);
    }
}

/* ------------------------------- rb_switch ------------------------------- */

static void test_rb_switch(void)
{
    /* the step names are the Kotlin enum toString()s — the error string
     * embeds one, so the exact spelling is the contract */
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_STOP_NAVIGATION),
                "STOP_NAVIGATION"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_SAVE_TAB_STATE), "SAVE_TAB_STATE"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_DESTROY_BROWSER_CONTEXT),
                "DESTROY_BROWSER_CONTEXT"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_FLUSH_PROFILE_STATE),
                "FLUSH_PROFILE_STATE"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_RELEASE_PROFILE_RESOURCES),
                "RELEASE_PROFILE_RESOURCES"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_LOAD_NEW_PROFILE_CONTEXT),
                "LOAD_NEW_PROFILE_CONTEXT"));
    CHECK(STREQ(rb_switch_step_name(RB_SWITCH_RESTORE_NEW_PROFILE_TABS),
                "RESTORE_NEW_PROFILE_TABS"));
    CHECK(STREQ(rb_switch_step_name((rb_switch_step)99), "STOP_NAVIGATION"));
    CHECK(STREQ(rb_switch_step_name((rb_switch_step)-1), "STOP_NAVIGATION"));
    CHECK(RB_SWITCH_STEP_COUNT == 7);

    CHECK(STREQ(rb_switch_state_name(RB_SWITCH_IDLE), "IDLE"));
    CHECK(STREQ(rb_switch_state_name(RB_SWITCH_SWITCHING), "SWITCHING"));
    CHECK(STREQ(rb_switch_state_name(RB_SWITCH_COMPLETE), "COMPLETE"));
    CHECK(STREQ(rb_switch_state_name(RB_SWITCH_FAILED), "FAILED"));
    CHECK(STREQ(rb_switch_state_name((rb_switch_state)77), "IDLE"));

    /* a fresh machine is idle, step-less, empty and blank */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_step_of(m) == -1);
        CHECK(rb_switch_completed_count(m) == 0);
        CHECK(rb_switch_from(m) == NULL);
        CHECK(rb_switch_to(m) == NULL);
        CHECK(rb_switch_error(m) == NULL);
        CHECK(rb_switch_involves(m, "a") == 0);
        CHECK(rb_switch_completed_at(m, 0) == -1);
        /* events that need a switch in flight are refused, not crashes */
        CHECK(rb_switch_step_done(m) == -1);
        CHECK(rb_switch_fail(m, RB_SWITCH_STOP_NAVIGATION, "x") == -1);
        rb_switch_free(m);
    }

    /* the full happy path follows the spec order */
    {
        rb_switch_machine *m = rb_switch_new();
        int i;

        CHECK(rb_switch_begin(m, "profile-a", "profile-b") == 0);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_SWITCHING);
        CHECK(rb_switch_step_of(m) == RB_SWITCH_STOP_NAVIGATION);
        CHECK(STREQ(rb_switch_from(m), "profile-a"));
        CHECK(STREQ(rb_switch_to(m), "profile-b"));

        for (i = 0; i < RB_SWITCH_STEP_COUNT; i++) {
            /* still switching right up to the last step */
            CHECK(rb_switch_state_of(m) == RB_SWITCH_SWITCHING);
            CHECK(rb_switch_step_of(m) == i);
            CHECK(rb_switch_completed_count(m) == i);
            CHECK(rb_switch_step_done(m) == 0);
        }
        CHECK(rb_switch_state_of(m) == RB_SWITCH_COMPLETE);
        CHECK(rb_switch_step_of(m) == -1);
        CHECK(rb_switch_completed_count(m) == RB_SWITCH_STEP_COUNT);
        for (i = 0; i < RB_SWITCH_STEP_COUNT; i++) {
            CHECK(rb_switch_completed_at(m, i) == i); /* in order */
        }
        CHECK(rb_switch_completed_at(m, RB_SWITCH_STEP_COUNT) == -1);
        CHECK(rb_switch_completed_at(m, -1) == -1);

        /* a completed switch accepts no further steps */
        CHECK(rb_switch_step_done(m) == -1);
        CHECK(rb_switch_completed_count(m) == RB_SWITCH_STEP_COUNT);
        /* and no new one may start on top of it */
        CHECK(rb_switch_begin(m, "profile-a", "profile-b") == RB_SWITCH_ERR_BUSY);

        /* involves() is only true while switching */
        CHECK(rb_switch_involves(m, "profile-a") == 0);
        CHECK(rb_switch_involves(m, "profile-b") == 0);
        CHECK(rb_switch_involves(m, NULL) == 0);

        rb_switch_free(m);
    }

    /* involves() tracks both ends while a switch is in flight */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_begin(m, "profile-a", "profile-b") == 0);
        CHECK(rb_switch_involves(m, "profile-a") == 1);
        CHECK(rb_switch_involves(m, "profile-b") == 1);
        CHECK(rb_switch_involves(m, "profile-c") == 0);
        CHECK(rb_switch_involves(m, "") == 0);
        CHECK(rb_switch_involves(m, NULL) == 0);

        /* a switch may not be begun on top of another */
        CHECK(rb_switch_begin(m, "profile-b", "profile-c") == RB_SWITCH_ERR_BUSY);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_SWITCHING);
        CHECK(STREQ(rb_switch_to(m), "profile-b")); /* unchanged by the refusal */

        rb_switch_free(m);
    }

    /* switching a profile onto itself is refused, and leaves the machine
     * untouched */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_begin(m, "profile-a", "profile-a") == RB_SWITCH_ERR_SAME);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_from(m) == NULL);
        CHECK(rb_switch_step_of(m) == -1);

        /* a blank or NULL end is refused too */
        CHECK(rb_switch_begin(m, "", "profile-b") == RB_SWITCH_ERR_BLANK);
        CHECK(rb_switch_begin(m, "profile-a", "") == RB_SWITCH_ERR_BLANK);
        CHECK(rb_switch_begin(m, NULL, "profile-b") == RB_SWITCH_ERR_BLANK);
        CHECK(rb_switch_begin(m, "profile-a", NULL) == RB_SWITCH_ERR_BLANK);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_from(m) == NULL);

        rb_switch_free(m);
    }

    /* an error fails the switch and records "step <NAME>: <reason>" */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_begin(m, "profile-a", "profile-b") == 0);
        CHECK(rb_switch_step_done(m) == 0); /* STOP_NAVIGATION */
        CHECK(rb_switch_step_done(m) == 0); /* SAVE_TAB_STATE */
        CHECK(rb_switch_step_of(m) == RB_SWITCH_DESTROY_BROWSER_CONTEXT);

        CHECK(rb_switch_fail(m, RB_SWITCH_DESTROY_BROWSER_CONTEXT,
                             "webview destroy failed") == 0);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_FAILED);
        CHECK(rb_switch_error(m) != NULL);
        CHECK(strstr(rb_switch_error(m), "webview destroy failed") != NULL);
        CHECK(strstr(rb_switch_error(m), "DESTROY_BROWSER_CONTEXT") != NULL);
        CHECK(strncmp(rb_switch_error(m), "step ", 5) == 0);

        /* a failed switch takes no more steps and no new begin */
        CHECK(rb_switch_step_done(m) == -1);
        CHECK(rb_switch_fail(m, RB_SWITCH_STOP_NAVIGATION, "again") == -1);
        CHECK(rb_switch_begin(m, "profile-a", "profile-b") == RB_SWITCH_ERR_BUSY);

        /* abort returns to IDLE and KEEPS the error text for the UI */
        rb_switch_abort(m);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_step_of(m) == -1);
        CHECK(rb_switch_error(m) != NULL);
        CHECK(strstr(rb_switch_error(m), "webview destroy failed") != NULL);
        /* Kotlin's Abort leaves from/to set as well */
        CHECK(STREQ(rb_switch_from(m), "profile-a"));
        CHECK(STREQ(rb_switch_to(m), "profile-b"));

        /* and the machine is usable again */
        CHECK(rb_switch_begin(m, "profile-a", "profile-c") == 0);
        CHECK(rb_switch_involves(m, "profile-c") == 1);

        rb_switch_free(m);
    }

    /* a NULL reason reads as Kotlin's "unknown error" default */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_begin(m, "a", "b") == 0);
        CHECK(rb_switch_fail(m, RB_SWITCH_FLUSH_PROFILE_STATE, NULL) == 0);
        CHECK(STREQ(rb_switch_error(m),
                    "step FLUSH_PROFILE_STATE: unknown error"));
        rb_switch_free(m);
    }

    /* abort from IDLE is allowed and is a no-op */
    {
        rb_switch_machine *m = rb_switch_new();

        rb_switch_abort(m);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_begin(m, "a", "b") == 0);
        rb_switch_abort(m);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_step_of(m) == -1);
        /* the half-finished steps are still on the log, as in Kotlin */
        CHECK(rb_switch_completed_count(m) == 0);
        rb_switch_free(m);
    }

    /* abort mid-switch keeps the completed log, and a restarted switch does
     * not skip a step because of it */
    {
        rb_switch_machine *m = rb_switch_new();
        int i;

        CHECK(rb_switch_begin(m, "a", "b") == 0);
        CHECK(rb_switch_step_done(m) == 0); /* STOP_NAVIGATION */
        CHECK(rb_switch_step_done(m) == 0); /* SAVE_TAB_STATE */
        rb_switch_abort(m);
        CHECK(rb_switch_completed_count(m) == 2);

        CHECK(rb_switch_begin(m, "b", "a") == 0);
        /* the log carries the two stale entries, but the step in flight is
         * still the first one */
        CHECK(rb_switch_step_of(m) == RB_SWITCH_STOP_NAVIGATION);
        for (i = 0; i < RB_SWITCH_STEP_COUNT; i++) {
            CHECK(rb_switch_step_of(m) == i);
            CHECK(rb_switch_step_done(m) == 0);
        }
        CHECK(rb_switch_state_of(m) == RB_SWITCH_COMPLETE);
        /* the stale prefix is preserved, exactly as Kotlin's list is */
        CHECK(rb_switch_completed_count(m) == 2 + RB_SWITCH_STEP_COUNT);
        CHECK(rb_switch_completed_at(m, 2) == RB_SWITCH_STOP_NAVIGATION);
        rb_switch_free(m);
    }

    /* reset clears everything, including the error the abort kept */
    {
        rb_switch_machine *m = rb_switch_new();

        CHECK(rb_switch_begin(m, "a", "b") == 0);
        CHECK(rb_switch_step_done(m) == 0);
        rb_switch_reset(m);
        CHECK(rb_switch_state_of(m) == RB_SWITCH_IDLE);
        CHECK(rb_switch_step_of(m) == -1);
        CHECK(rb_switch_completed_count(m) == 0);
        CHECK(rb_switch_from(m) == NULL);
        CHECK(rb_switch_to(m) == NULL);
        CHECK(rb_switch_error(m) == NULL);

        CHECK(rb_switch_begin(m, "a", "b") == 0);
        CHECK(rb_switch_fail(m, RB_SWITCH_STOP_NAVIGATION, "boom") == 0);
        rb_switch_abort(m);
        CHECK(rb_switch_error(m) != NULL);
        rb_switch_reset(m);
        CHECK(rb_switch_error(m) == NULL);
        rb_switch_free(m);
    }

    /* every read tolerates NULL, and no event crashes on it */
    CHECK(rb_switch_state_of(NULL) == RB_SWITCH_IDLE);
    CHECK(rb_switch_step_of(NULL) == -1);
    CHECK(rb_switch_completed_count(NULL) == 0);
    CHECK(rb_switch_completed_at(NULL, 0) == -1);
    CHECK(rb_switch_from(NULL) == NULL);
    CHECK(rb_switch_to(NULL) == NULL);
    CHECK(rb_switch_error(NULL) == NULL);
    CHECK(rb_switch_involves(NULL, "a") == 0);
    CHECK(rb_switch_begin(NULL, "a", "b") == RB_SWITCH_ERR_BLANK);
    CHECK(rb_switch_step_done(NULL) == -1);
    CHECK(rb_switch_fail(NULL, RB_SWITCH_STOP_NAVIGATION, "x") == -1);
    rb_switch_abort(NULL);
    rb_switch_reset(NULL);
    rb_switch_free(NULL); /* must not crash */
}

/* ------------------------------- rb_devices ------------------------------ */

/* Counts non-overlapping occurrences of `needle` in `hay`. */
static int count_of(const char *hay, const char *needle)
{
    int n = 0;
    size_t len = strlen(needle);
    const char *p = hay;

    while ((p = strstr(p, needle)) != NULL) {
        n++;
        p += len;
    }
    return n;
}

static void test_rb_devices(void)
{
    int n = rb_device_count();
    int i, j;
    const rb_device *d;

    /* Large enough that profiles keep distinct identities: the same floor the
     * Android catalogue is held to, so neither edition can quietly shrink.
     * The list is generated, so this fails only if the generator is run with
     * a trimmed table. */
    CHECK(n >= 1000);
    /* NULL and "" are both "no device", so a profile that has never been
     * assigned one needs no special case anywhere. */
    CHECK(rb_device_by_id(NULL) == NULL);
    CHECK(rb_device_by_id("") == NULL);
    CHECK(rb_device_by_id("no-such-device") == NULL);
    CHECK(rb_device_at(-1) == NULL);
    CHECK(rb_device_at(n) == NULL);

    for (i = 0; i < n; i++) {
        d = rb_device_at(i);
        CHECK(d != NULL);
        CHECK(d->id != NULL && d->id[0] != '\0');
        CHECK(d->brand != NULL && d->brand[0] != '\0');
        CHECK(d->model != NULL && d->model[0] != '\0');
        CHECK(d->ua != NULL && d->ua[0] != '\0');
        CHECK(d->gpu_vendor != NULL && d->gpu_vendor[0] != '\0');
        CHECK(d->gpu_renderer != NULL && d->gpu_renderer[0] != '\0');
        CHECK(d->year >= 2022 && d->year <= 2025);

        /* The lookup by id finds the entry it names. */
        CHECK(rb_device_by_id(d->id) == d);

        /* A desktop browser that announced itself as a phone would be handed
         * mobile layouts, which is the one thing this must never do. */
        CHECK(strstr(d->ua, "Mobile") == NULL);
        CHECK(strstr(d->ua, "Android") == NULL);
        CHECK(strstr(d->ua, "Mozilla/5.0 (") == d->ua);
        CHECK(strstr(d->ua, "Chrome/") != NULL);

        /* Chrome reduced the desktop UA string in 2022: the build number is
         * not in it any more. A full version here is a string no real Chrome
         * sends. */
        {
            const char *c = strstr(d->ua, "Chrome/");
            const char *dot = strchr(c + 7, '.');
            CHECK(dot != NULL);
            CHECK(STREQ(dot, ".0.0.0 Safari/537.36"));
        }

        /* The platform strings follow the operating system, and the client
         * hint platform is the same fact in Chrome's vocabulary. */
        if (STREQ(d->os, "windows")) {
            CHECK(STREQ(d->platform, "Win32"));
            CHECK(STREQ(d->ua_platform, "Windows"));
            CHECK(strstr(d->ua, "Windows NT 10.0; Win64; x64") != NULL);
        } else if (STREQ(d->os, "macos")) {
            CHECK(STREQ(d->platform, "MacIntel"));
            CHECK(STREQ(d->ua_platform, "macOS"));
            CHECK(strstr(d->ua, "Macintosh; Intel Mac OS X 10_15_7") != NULL);
        } else if (STREQ(d->os, "linux")) {
            CHECK(STREQ(d->platform, "Linux x86_64"));
            CHECK(STREQ(d->ua_platform, "Linux"));
            CHECK(strstr(d->ua, "X11; Linux x86_64") != NULL);
        } else {
            CHECK(0); /* unknown os */
        }

        /* ARM only where it is real: Apple Silicon. */
        if (STREQ(d->arch, "arm")) {
            CHECK(STREQ(d->os, "macos"));
        } else {
            CHECK(STREQ(d->arch, "x86"));
        }

        /* Chromium caps navigator.deviceMemory at 8, so a bigger machine
         * reports 8 rather than its real size. */
        CHECK(d->memory >= 1 && d->memory <= 8);
        CHECK(d->cores >= 2 && d->cores <= 64);
        CHECK(d->platform_version != NULL && d->platform_version[0] != '\0');
        CHECK(STREQ(d->form, "laptop") || STREQ(d->form, "desktop"));
    }

    /* Every device is a distinct identity: no two share an id, and no two
     * share a whole fingerprint. Two entries that agreed on all of it would
     * be one machine under two names, and two profiles given them would be
     * indistinguishable - which is the point of having a catalogue. */
    for (i = 0; i < n; i++) {
        const rb_device *a = rb_device_at(i);
        for (j = i + 1; j < n; j++) {
            const rb_device *b = rb_device_at(j);
            CHECK(!STREQ(a->id, b->id));
            CHECK(!same_device_identity(a, b));
        }
    }
}

static void test_rb_device_behaviour(void)
{
    const rb_device *d = rb_device_at(0);
    const rb_device *picked;
    const char *taken[3];
    char *ua;
    char *js;
    int n = rb_device_count();
    int i;

    /* The UA helper resolves through the same "no device" rule. */
    CHECK(rb_device_ua_for(NULL) == NULL);
    CHECK(rb_device_ua_for("") == NULL);
    CHECK(rb_device_ua_for("no-such-device") == NULL);
    ua = rb_device_ua_for(d->id);
    CHECK(ua != NULL);
    CHECK(STREQ(ua, d->ua));
    free(ua);

    /* A random pick never returns a device that is already spoken for, so
     * two profiles created in a row do not land on the same machine. */
    for (i = 0; i < n; i++) {
        const rb_device *a = rb_device_at(i);
        const rb_device *b = rb_device_at((i + 1) % n);
        taken[0] = a->id;
        taken[1] = b->id;
        taken[2] = NULL;
        picked = rb_device_random(taken, 2);
        CHECK(picked != NULL);
        CHECK(!STREQ(picked->id, a->id));
        CHECK(!STREQ(picked->id, b->id));
    }

    /* An exhausted pool still returns a machine: one shared device beats no
     * assignment at all. */
    {
        const char **all = (const char **)malloc((size_t)n * sizeof(char *));
        CHECK(all != NULL);
        for (i = 0; i < n; i++) all[i] = rb_device_at(i)->id;
        picked = rb_device_random(all, n);
        CHECK(picked != NULL);
        free(all);
    }
    /* No exclusions at all is the first-profile case. */
    CHECK(rb_device_random(NULL, 0) != NULL);

    /* A NULL device has no script; a real one produces a complete one. */
    CHECK(rb_device_shim_js(NULL) == NULL);

    for (i = 0; i < n; i++) {
        d = rb_device_at(i);
        js = rb_device_shim_js(d);
        CHECK(js != NULL);
        /* A leftover placeholder is a syntax error in the page, not a silent
         * no-op, so this is the check that matters most. */
        CHECK(strstr(js, "__") == NULL);
        CHECK(strstr(js, d->ua) != NULL);
        CHECK(strstr(js, d->gpu_renderer) != NULL);
        CHECK(strstr(js, d->platform) != NULL);
        CHECK(strstr(js, d->ua_platform) != NULL);
        CHECK(strstr(js, "var MEMORY = ") != NULL);
        CHECK(strstr(js, "var CORES = ") != NULL);
        /* A desktop never claims to be a phone, in the UA or in the hints. */
        CHECK(strstr(js, "mobile: false") != NULL);
        CHECK(strstr(js, "formFactor = 'Desktop'") != NULL);
        CHECK(strstr(js, "out.bitness = '64'") != NULL);
        /* Nothing geometric: the page really is laid out on this screen. */
        CHECK(strstr(js, "innerWidth") == NULL);
        CHECK(strstr(js, "innerHeight") == NULL);
        CHECK(strstr(js, "devicePixelRatio") == NULL);
        CHECK(strstr(js, "availWidth") == NULL);
        /* Balanced and self-contained enough to be pasted into a page. */
        CHECK(count_of(js, "function") >= 3);
        CHECK(js[strlen(js) - 1] == '\n');
        free(js);
    }
}

static void test_rb_device_shim_escaping(void)
{
    /* A device whose fields carry JavaScript metacharacters. The catalogue's
     * own values are plain ASCII, but the escaping must hold regardless of
     * where a value came from - a value that closed its own string literal
     * would run whatever followed it in the page. */
    rb_device hostile;
    char *js;
    const rb_device *clean = rb_device_at(0);

    memset(&hostile, 0, sizeof hostile);
    hostile.id = "hostile";
    hostile.brand = "X'); alert('pwned'); //";
    hostile.model = "M\\'";
    hostile.year = 2024;
    hostile.os = "windows";
    hostile.form = "laptop";
    hostile.arch = "x86";
    hostile.chrome = "131.0.6778.135";
    hostile.gpu_vendor = "V\\";
    hostile.gpu_renderer = "R\nsecond line";
    hostile.cores = 8;
    hostile.memory = 8;
    hostile.platform = "Win32";
    hostile.ua_platform = "Windows";
    hostile.platform_version = "15.0.0";
    hostile.ua = "UA'; alert('pwned'); //";

    js = rb_device_shim_js(&hostile);
    CHECK(js != NULL);
    CHECK(strstr(js, "\\'") != NULL);          /* the quote is escaped */
    CHECK(strstr(js, "alert('pwned')") == NULL); /* so it never closes */
    CHECK(strstr(js, "\\n") != NULL);          /* nor does a newline */
    free(js);

    /* A device with no hostile characters produces a script with exactly the
     * same number of lines: nothing in a field leaked a newline. */
    js = rb_device_shim_js(clean);
    CHECK(js != NULL);
    {
        rb_device same = *clean;
        char *plain = rb_device_shim_js(&same);
        CHECK(plain != NULL);
        CHECK(count_of(plain, "\n") == count_of(js, "\n"));
        free(plain);
    }
    free(js);
}

/* The screen half of the shim, and the composition that decides which halves
 * a profile installs. Mirrors the Android edition's DeviceShimTest. */
static void test_rb_screen_shim(void)
{
    char *js;
    rb_settings *f;
    const rb_device *d = rb_device_at(0);

    /* Outside the range a stored size can mean there is no claim, so there is
     * nothing to install - not a script with the numbers clamped into it. */
    CHECK(rb_screen_shim_js(RB_SCREEN_PX_MIN - 1, 1000) == NULL);
    CHECK(rb_screen_shim_js(1000, RB_SCREEN_PX_MAX + 1) == NULL);
    CHECK(rb_screen_shim_js(0, 0) == NULL);
    CHECK(rb_screen_shim_js(-1920, 1080) == NULL);
    /* The bounds themselves are claims: they are the widest and narrowest a
     * profile is allowed to state, not off-by-one guards. */
    js = rb_screen_shim_js(RB_SCREEN_PX_MIN, RB_SCREEN_PX_MIN);
    CHECK(js != NULL);
    free(js);
    js = rb_screen_shim_js(RB_SCREEN_PX_MAX, RB_SCREEN_PX_MAX);
    CHECK(js != NULL);
    free(js);

    /* What a claim replaces, and the two things it must not. */
    js = rb_screen_shim_js(1920, 1080);
    CHECK(js != NULL);
    CHECK(strstr(js, "var SCREEN_W = 1920;") != NULL);
    CHECK(strstr(js, "var SCREEN_H = 1080;") != NULL);
    CHECK(strstr(js, "'width'") != NULL);
    CHECK(strstr(js, "'height'") != NULL);
    CHECK(strstr(js, "'availWidth'") != NULL);
    CHECK(strstr(js, "'availHeight'") != NULL);
    /* The trade, asserted rather than described: the layout viewport and the
     * pixel ratio are the display's own, because they are what the page is
     * really laid out and rendered at. See SECURITY.md. */
    CHECK(strstr(js, "innerWidth") == NULL);
    CHECK(strstr(js, "innerHeight") == NULL);
    CHECK(strstr(js, "devicePixelRatio") == NULL);
    /* Every placeholder was substituted; one left behind would be a syntax
     * error in the page, not a wrong answer in it. */
    CHECK(strstr(js, "__") == NULL);
    /* The available rectangle is measured from the real display rather than
     * assumed equal to the whole screen: a desktop has a taskbar, and a claim
     * of no inset at all would be its own tell. */
    CHECK(strstr(js, "screen.width - screen.availWidth") != NULL);
    CHECK(strstr(js, "screen.height - screen.availHeight") != NULL);
    /* A landscape claim says so, and does not also answer 90 degrees: the
     * angle is the device's rotation, and a desktop display is never rotated. */
    CHECK(strstr(js, "'landscape-primary'") != NULL);
    CHECK(strstr(js, "'angle', 0") != NULL);
    free(js);

    js = rb_screen_shim_js(1080, 1920);
    CHECK(js != NULL);
    CHECK(strstr(js, "'portrait-primary'") != NULL);
    CHECK(strstr(js, "'landscape-primary'") == NULL);
    free(js);

    /* Square is portrait, which is the Android edition's rule too - the claim
     * is landscape only when the width is STRICTLY greater, so the two
     * editions cannot disagree about a shape. */
    js = rb_screen_shim_js(600, 600);
    CHECK(js != NULL);
    CHECK(strstr(js, "'portrait-primary'") != NULL);
    free(js);

    /* The composition. A profile that claims nothing installs nothing: an
     * empty script on every page of every profile is not the same as no
     * script, and this is the default state. */
    f = rb_settings_new();
    rb_prefs_profile_defaults(f);
    CHECK(rb_shim_js(NULL, f) == NULL);
    CHECK(rb_shim_js(NULL, NULL) == NULL);

    /* A device on its own still shims the machine. */
    js = rb_shim_js(d, f);
    CHECK(js != NULL);
    CHECK(strstr(js, "userAgentData") != NULL);
    CHECK(strstr(js, "Screen.prototype") == NULL); /* no screen half */
    free(js);

    /* A screen claimed with no key at all - the store never had one - is no
     * claim either. */
    {
        rb_settings *bare = rb_settings_new();
        CHECK(rb_shim_js(NULL, bare) == NULL);
        rb_settings_free(bare);
    }

    /* A screen on its own, with no device: a profile on a UA preset can state
     * a size, and the script must not carry an identity it never asked for. */
    rb_settings_set(f, RB_PREF_SCREEN_SIZE, "manual");
    rb_settings_set_int(f, RB_PREF_SCREEN_WIDTH, 1280);
    rb_settings_set_int(f, RB_PREF_SCREEN_HEIGHT, 720);
    js = rb_shim_js(NULL, f);
    CHECK(js != NULL);
    CHECK(strstr(js, "var SCREEN_W = 1280;") != NULL);
    CHECK(strstr(js, "userAgentData") == NULL);
    CHECK(strstr(js, "WebGLRenderingContext") == NULL);
    free(js);

    /* Both halves, device first: one document-start script, so the two cannot
     * disagree about which order they ran in. */
    js = rb_shim_js(d, f);
    CHECK(js != NULL);
    {
        const char *machine = strstr(js, "userAgentData");
        const char *screen = strstr(js, "var SCREEN_W = 1280;");
        CHECK(machine != NULL);
        CHECK(screen != NULL);
        if (machine != NULL && screen != NULL) {
            CHECK(machine < screen);
        }
    }
    free(js);

    /* The mode decides, and the numbers are inert without it: a profile that
     * was switched back to the real display must not keep claiming the size
     * it used to. */
    rb_settings_set(f, RB_PREF_SCREEN_SIZE, "real");
    CHECK(rb_shim_js(NULL, f) == NULL);
    js = rb_shim_js(d, f);
    CHECK(js != NULL);
    CHECK(strstr(js, "Screen.prototype") == NULL);
    free(js);

    rb_settings_free(f);
}

int main(void)
{    test_rb_str();
    test_rb_json();
    test_rb_url();
    test_rb_search();
    test_rb_ua();
    test_rb_theme();
    test_rb_filters();
    test_rb_https();
    test_rb_download_names();
    test_rb_downloads();
    test_rb_download_format();
    test_rb_downloads_scoped();
    test_rb_downloads_reconcile();
    test_rb_tabs();
    test_rb_history();
    test_rb_history_ops();
    test_rb_bookmarks();
    test_rb_settings();
    test_rb_prefs();
    test_rb_profile();
    test_rb_profile_dirs();
    test_rb_paths();
    test_rb_paths_tree();
    test_rb_dns();
    test_rb_ipconflict();
    test_rb_switch();
    test_rb_devices();
    test_rb_device_behaviour();
    test_rb_device_shim_escaping();
    test_rb_screen_shim();
    /* A nonzero return means the fragment already printed its FAIL lines. */
    if (rb_test_notes_all() != 0) {
        return 1;
    }
    if (rb_test_totp_all() != 0) {
        return 1;
    }
    remove(TMP);
    (void)rb_paths_remove_tree(TMP_DIR);
    printf("core checks: %d\n", g_checks);
    printf("ALL CORE TESTS PASSED\n");
    return 0;
}
