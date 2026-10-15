/*
 * Room Browser (desktop) - Windows resource identifiers.
 */
#ifndef RB_WIN_RESOURCE_H
#define RB_WIN_RESOURCE_H

#ifdef __cplusplus
extern "C" {
#endif

#define IDI_ICON1 101

/*
 * Menu command ids for the privacy tools.  chrome.h owns the IDM_* block
 * (3001+); these three continue that sequence, clear of the history range
 * (3100) and the profile range (3199+), and are kept here where the
 * resource side of the build shares the spellings.
 */
#define IDM_NOTES  3009
#define IDM_TOTP   3010
#define IDM_SHIELD 3011

#ifdef __cplusplus
}
#endif

#endif /* RB_WIN_RESOURCE_H */
