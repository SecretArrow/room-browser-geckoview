#!/usr/bin/env node
'use strict';

// The static proof of the out-of-the-box web-compatibility contract: what a
// fresh install sends and allows is decided in a handful of source lines, and
// this reads those lines and asserts what they say. Google sign-in's "This
// browser or app may not be secure" refusal reads exactly the two identity
// leaks those lines close -- the WebView markers in the user agent (stripped
// by UserAgents.webViewNeutralUserAgent since the video-site fix) and the
// X-Requested-With header the stock engine stamps on every request (suppressed
// by the empty origin allow-list in WebViewEngineSession). Around them: the
// compatibility defaults themselves (JavaScript on, third-party cookies and
// DOM storage on, no blocking shield on by default), the one-place popup
// gate, and the render-process-death report that keeps a renderer crash from
// killing the whole app.
//
// WHY STATIC: these are DEFAULTS, not behaviour under test conditions. A
// runtime proof would need a fresh profile and a WebView provider new enough
// to support the header opt-out before it could say anything the sources do
// not already say. The repo's own precedent is SettingsReachabilityTest,
// which reads Profile.kt as text the same way; this is that idea as plain
// Node so CI needs no Gradle to run it. Comments are stripped before any
// match, so a comment that merely MENTIONS an identifier can never satisfy a
// check, and every regex that must match is required to match, so a broken
// pattern fails loudly instead of passing silently.
//
// EDITION-AWARE: the GeckoView sibling carries this file byte-identical. The
// engine tree present decides which half of the parity is asserted -- the
// header opt-out, the popup gate and the render-gone recovery here; the
// ACCEPT_ALL cookie behaviour, the sign-in-critical pref chain (WebAuthn
// hidden, enterprise roots trusted, compat mixed content, every user-branch
// pref re-written at bind/configure so a stale value from an earlier install
// cannot outlive its default) and the DEFAULT-stays-engine-UA rule there --
// while the shared assertions (profile defaults, the UA sanitizer) run in
// both editions.
//
// One PASS line per check; exit non-zero the moment anything failed.

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const ANDROID = path.resolve(HERE, '..');
const REPO = path.resolve(ANDROID, '..');

const PROFILE_KT = path.join(
    ANDROID,
    'core/domain/src/main/kotlin/com/roombrowser/domain/model/Profile.kt');
const USER_AGENTS_KT = path.join(
    ANDROID,
    'core/domain/src/main/kotlin/com/roombrowser/domain/model/UserAgents.kt');
const WEBVIEW_SESSION_KT = path.join(
    ANDROID,
    'engine/src/main/kotlin/com/roombrowser/engine/webview/WebViewEngineSession.kt');
const GECKO_HOST_KT = path.join(
    ANDROID,
    'engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineHost.kt');
const GECKO_SESSION_KT = path.join(
    ANDROID,
    'engine/src/main/kotlin/com/roombrowser/engine/gecko/GeckoEngineSession.kt');
const VERSIONS_TOML = path.join(ANDROID, 'gradle', 'libs.versions.toml');
const CI_YML = path.join(REPO, '.github', 'workflows', 'ci.yml');

const IS_WEBVIEW = fs.existsSync(WEBVIEW_SESSION_KT);
const IS_GECKO = fs.existsSync(GECKO_HOST_KT);

const fixtures = [];

function fixture(name, fn) {
    fixtures.push({ name: name, fn: fn });
}

function check(ok, label, detail) {
    if (!ok) {
        throw new Error(label + (detail !== undefined ? ' -- ' + detail : ''));
    }
}

function read(p) {
    const text = fs.readFileSync(p, 'utf8');
    check(text.length > 0, 'read a non-empty ' + path.relative(REPO, p));
    return text;
}

// Comments out before matching: a comment that MENTIONS an identifier can
// never satisfy the assertion for it. Kotlin /* */ and // here; TOML/YAML #
// separately. String literals stay, because the sanitizer's replace()
// arguments ARE the assertion.
function stripKotlinComments(text) {
    return text
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/\/\/[^\n]*/g, ' ');
}

function stripHashComments(text) {
    return text.replace(/#[^\n]*/g, ' ');
}

function matches(text, regex, label) {
    check(regex.test(text), label, 'no match for ' + regex);
}

// ---------- shared: both editions ----------

fixture('edition', function () {
    check(
        IS_WEBVIEW !== IS_GECKO,
        'exactly one engine edition is present',
        'webview=' + IS_WEBVIEW + ' gecko=' + IS_GECKO
    );
});

fixture('profile-compat-defaults', function () {
    const profile = stripKotlinComments(read(PROFILE_KT));
    matches(
        profile,
        /val\s+javascriptEnabled:\s*Boolean\s*=\s*true/,
        'JavaScript is enabled by default');
    ['blockAds', 'blockTrackers', 'blockCrossSiteTrackers', 'blockPopups', 'blockMalicious']
        .forEach(function (name) {
            matches(
                profile,
                new RegExp('val\\s+' + name + ':\\s*Boolean\\s*=\\s*false'),
                name + ' is off by default (compatibility-first shields)');
        });
    matches(
        profile,
        /val\s+httpsUpgrade:\s*Boolean\s*=\s*true/,
        'httpsUpgrade stays on (its http fallback is what makes it safe)');
    matches(
        profile,
        /val\s+blockThirdPartyCookies:\s*Boolean\s*=\s*false/,
        'third-party cookies are allowed by default (logins and embeds need them)');
    matches(
        profile,
        /val\s+blockMixedContent:\s*Boolean\s*=\s*false/,
        'mixed content runs in compatibility mode by default');
    matches(
        profile,
        /val\s+uaMode:\s*UaMode\s*=\s*UaMode\.DEFAULT/,
        'the default UA mode is DEFAULT (the engine UA, neutralized)');
    matches(
        profile,
        /val\s+desktopModeDefault:\s*Boolean\s*=\s*false/,
        'desktop mode is off by default');
});

fixture('ua-sanitizer', function () {
    const uas = stripKotlinComments(read(USER_AGENTS_KT));
    matches(
        uas,
        /fun\s+webViewNeutralUserAgent\(/,
        'the neutralizer exists');
    matches(
        uas,
        /\.replace\(";\s*wv\)",\s*"\)"\)/,
        'the `; wv)` platform token is stripped');
    matches(
        uas,
        /\.replace\("Version\/4\.0\s*",\s*""\)/,
        'the `Version/4.0` product token is stripped');
    matches(
        uas,
        /UaMode\.DEFAULT\s*->\s*null/,
        'DEFAULT mode resolves to the engine UA (null override)');
});

fixture('ci-runs-this-proof', function () {
    const ci = stripHashComments(read(CI_YML));
    matches(
        ci,
        /node\s+tools\/test_webview_defaults\.js/,
        'the quality job runs this proof on every push');
});

// ---------- WebView edition ----------

if (IS_WEBVIEW) {
    fixture('webview-requested-with-optout', function () {
        const session = stripKotlinComments(read(WEBVIEW_SESSION_KT));
        matches(
            session,
            /^import\s+androidx\.webkit\.WebSettingsCompat$/m,
            'WebSettingsCompat is imported');
        matches(
            session,
            /WebViewFeature\.REQUESTED_WITH_HEADER_ALLOW_LIST/,
            'the allow-list feature is named');
        const guard = session.indexOf(
            'WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)');
        check(
            guard >= 0,
            'the header opt-out is guarded by its feature check');
        const call = session.indexOf(
            'WebSettingsCompat.setRequestedWithHeaderOriginAllowList(');
        check(
            call > guard,
            'the header opt-out call sits inside its guard');
        matches(
            session,
            /emptySet<String>\(\)/,
            'the allow-list is EMPTY (no origin may receive the header)');
    });

    fixture('webview-window-policy', function () {
        const session = stripKotlinComments(read(WEBVIEW_SESSION_KT));
        matches(
            session,
            /javaScriptCanOpenWindowsAutomatically\s*=\s*true/,
            'window.open reaches the app popup gate, not an engine-level swallow');
    });

    fixture('webview-storage-cookies-windows', function () {
        const session = stripKotlinComments(read(WEBVIEW_SESSION_KT));
        matches(
            session,
            /domStorageEnabled\s*=\s*true/,
            'DOM storage is on');
        matches(
            session,
            /setSupportMultipleWindows\(true\)/,
            'multi-window support is on (target=_blank and popups arrive as decisions)');
        matches(
            session,
            /setAcceptThirdPartyCookies\(webView,\s*!settings\.blockThirdPartyCookies\)/,
            'third-party cookies follow the profile setting (allowed by default)');
        matches(
            session,
            /MIXED_CONTENT_COMPATIBILITY_MODE/,
            'mixed content compatibility mode is the default branch');
    });

    fixture('webview-render-process-recovery', function () {
        const session = stripKotlinComments(read(WEBVIEW_SESSION_KT));
        matches(
            session,
            /override\s+fun\s+onRenderProcessGone\(/,
            'the render-process-death callback is implemented');
        matches(
            session,
            /detail:\s*RenderProcessGoneDetail/,
            'the callback takes the platform detail');
        matches(
            session,
            /detail\.didCrash\(\)/,
            'the crash flag reaches the report');
        matches(
            session,
            /renderer process gone/,
            'the report says what happened');
    });

    fixture('webkit-header-api-version', function () {
        const toml = stripHashComments(read(VERSIONS_TOML));
        const m = toml.match(/^webkit\s*=\s*"(\d+)\.(\d+)(?:\.(\d+))?"/m);
        check(
            m !== null,
            'the webkit version is pinned in libs.versions.toml');
        const parts = [
            parseInt(m[1], 10),
            parseInt(m[2], 10),
            parseInt(m[3] === undefined ? '0' : m[3], 10)
        ];
        check(
            parts[0] > 1 || (parts[0] === 1 && parts[1] >= 8),
            'androidx.webkit stays in the generation the header opt-out needs',
            'found ' + parts.join('.'));
    });
}

// ---------- GeckoView edition ----------

if (IS_GECKO) {
    fixture('gecko-cookie-parity', function () {
        const host = stripKotlinComments(read(GECKO_HOST_KT));
        matches(
            host,
            /CookieBehavior\.ACCEPT_ALL/,
            'cookies are accepted (third-party included) unless the profile blocks them');
        matches(
            host,
            /setCookieBehaviorPrivateMode\(/,
            'the private-mode jar follows the same behaviour');
    });

    // The sign-in chain: every pref a modern login flow depends on, asserted
    // at the SOURCE LINES that decide it. Two of them are user-branch writes
    // that PERSIST into the Gecko profile, so the assertion is not just "the
    // pref exists" but "it is re-written on every bind" -- a value a previous
    // release wrote must not outlive the default this code depends on.
    fixture('gecko-signin-prefs', function () {
        const host = stripKotlinComments(read(GECKO_HOST_KT));
        // WebAuthn stays hidden: an assertion that can never be serviced
        // hangs a passkey sign-in on a promise nothing settles, so the API is
        // removed from the pages rather than left to fail. NEVER re-enable.
        matches(
            host,
            /WEBAUTHN_PREF\s*=\s*"security\.webauth\.webauthn"/,
            'the WebAuthn master switch is named');
        matches(
            host,
            /setGeckoPref\(\s*WEBAUTHN_PREF,\s*false,/,
            'WebAuthn is written OFF, on the user branch');
        matches(
            host,
            /hideWebAuthn\(\)\s+trustEnterpriseRoots\(\)/,
            'WebAuthn-hide and enterprise roots are re-asserted together on every bind');
        // Enterprise roots: the user-installed CAs are this edition's whole
        // equivalent of the sibling's networkSecurityConfig (GeckoView does
        // not read that XML), and the only non-bypass way to say "I trust
        // this network".
        matches(
            host,
            /ENTERPRISE_ROOTS_PREF\s*=\s*"security\.enterprise_roots\.enabled"/,
            'the enterprise-roots pref is named');
        matches(
            host,
            /setGeckoPref\(\s*ENTERPRISE_ROOTS_PREF,\s*true,/,
            'user-installed CAs are trusted, on the user branch');
        // Mixed content in compatibility mode: the pref is written from the
        // profile's own setting on every configure (session creation
        // included), so a stale persisted value cannot survive the first
        // session. The shared profile fixture pins the default (false).
        matches(
            host,
            /MIXED_CONTENT_PREF\s*=\s*"security\.mixed_content\.block_display_content"/,
            'the mixed-content pref is the passive/display one (active is engine-blocked)');
        matches(
            host,
            /setGeckoPref\(\s*MIXED_CONTENT_PREF,/,
            'the mixed-content pref is written through the pref controller');
        matches(
            host,
            /applyMixedContentBlocking\(profile\.settings\.blockMixedContent\)/,
            'the mixed-content write follows the profile setting, re-asserted per configure');
        // The configure-time half of the chain: the runtime-wide settings are
        // re-applied on every configure, never assumed from a prior install.
        matches(
            host,
            /applyEngineWideSettings\(profile\)/,
            'cookie + mixed-content defaults are re-asserted on every configure');
    });

    // DEFAULT mode stays the engine's own UA; the override fires only when a
    // profile chose an identity. This is the Gecko half of the sibling's UA
    // neutralization -- structural here (the engine UA is Firefox mobile, no
    // WebView markers, no X-Requested-With) rather than stripped.
    fixture('gecko-ua-default-is-engine', function () {
        const session = stripKotlinComments(read(GECKO_SESSION_KT));
        matches(
            session,
            /USER_AGENT_MODE_MOBILE/,
            'a non-desktop session runs in the engine\'s mobile UA mode');
        matches(
            session,
            /UserAgents\.effectiveUserAgent\(profile\.settings\)\?\.let\s*\{\s*builder\.userAgentOverride\(it\)\s*\}/,
            'the override is set only when a profile actually names an identity');
        matches(
            session,
            /setUserAgentOverride\(UserAgents\.effectiveUserAgent\(profile\.settings\)\)/,
            'a configure back to DEFAULT passes the null override, which resets to the engine UA');
    });
}

// ---------- runner ----------

async function main() {
    const edition = IS_WEBVIEW ? 'webview' : 'gecko';
    process.stderr.write(
        'room-webview-defaults: ' + fixtures.length + ' checks, edition ' + edition +
        ', repo ' + REPO + '\n'
    );
    let failed = 0;
    for (const item of fixtures) {
        try {
            await item.fn();
            process.stdout.write('PASS ' + item.name + '\n');
        } catch (err) {
            failed += 1;
            process.stdout.write('FAIL ' + item.name + '\n');
            process.stderr.write(
                '  ' + item.name + ': ' +
                (err && err.message ? err.message : String(err)) + '\n'
            );
        }
    }
    const summary = failed === 0
        ? 'all ' + fixtures.length + ' checks passed'
        : failed + ' of ' + fixtures.length + ' checks failed';
    process.stdout.write((failed === 0 ? 'PASS ' : 'FAIL ') + summary + '\n');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(function (err) {
    process.stderr.write(
        'room-webview-defaults: ' +
        (err && err.message ? err.message : String(err)) + '\n'
    );
    process.exit(1);
});
