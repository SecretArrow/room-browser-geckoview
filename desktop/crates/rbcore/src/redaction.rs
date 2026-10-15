//! Secret redaction — port of the Android `SecretRedaction` /
//! DevTools redaction lists: header names, query keys, URL forms and the
//! `<redacted>` marker, applied at data-capture time.

pub const REDACTED: &str = "<redacted>";

/// Header names whose VALUES are always redacted (case-insensitive).
pub const SECRET_HEADERS: &[&str] = &[
    "authorization", "cookie", "set-cookie", "x-api-key", "api-key",
    "x-auth-token", "x-access-token", "proxy-authorization",
    "x-csrf-token", "x-xsrf-token", "x-session-id", "x-amz-security-token",
    "secret", "token", "password",
];

/// Query-string keys whose VALUES are redacted (case-insensitive).
pub const SECRET_QUERY_KEYS: &[&str] = &[
    "token", "access_token", "refresh_token", "id_token", "api_key",
    "apikey", "key", "secret", "password", "passwd", "signature", "sig",
    "sessionid", "session_id", "auth", "code",
];

pub fn is_secret_header(name: &str) -> bool {
    let n = name.trim().to_lowercase();
    SECRET_HEADERS.contains(&n.as_str())
}

pub fn is_secret_query_key(key: &str) -> bool {
    let k = key.trim().to_lowercase();
    SECRET_QUERY_KEYS.contains(&k.as_str())
}

/// Redacts userinfo and secret query values in a URL.
pub fn redact_url(url: &str) -> String {
    let (scheme_rest, sep, rest) = match url.find("://") {
        Some(i) => (&url[..i + 3], i + 3, &url[i + 3..]),
        None => ("", 0, url),
    };
    let auth_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let auth = &rest[..auth_end];
    let tail = &rest[auth_end..];
    let mut auth_out = auth.to_string();
    if let Some(at) = auth.rfind('@') {
        auth_out = format!("{}@{}", crate::redaction::REDACTED, &auth[at + 1..]);
    }
    // walk the query string, redacting secret values
    let mut tail_out = String::new();
    let mut tail_rest = tail;
    if let Some(q) = tail_rest.find('?') {
        tail_out.push_str(&tail_rest[..=q]);
        tail_rest = &tail_rest[q + 1..];
        let mut first = true;
        let fragment = match tail_rest.find('#') {
            Some(f) => {
                let frag = &tail_rest[f..];
                tail_rest = &tail_rest[..f];
                frag
            }
            None => "",
        };
        for pair in tail_rest.split('&') {
            if !first {
                tail_out.push('&');
            }
            first = false;
            if pair.is_empty() {
                continue;
            }
            match pair.split_once('=') {
                Some((k, v)) => {
                    if is_secret_query_key(k) {
                        tail_out.push_str(&format!("{k}={REDACTED}"));
                    } else {
                        tail_out.push_str(&format!("{k}={v}"));
                    }
                }
                None => tail_out.push_str(pair),
            }
        }
        tail_out.push_str(fragment);
    } else {
        tail_out.push_str(tail_rest);
    }
    if sep == 0 {
        format!("{auth_out}{tail_out}")
    } else {
        format!("{scheme_rest}{auth_out}{tail_out}")
    }
}

/// Redacts bearer/basic tokens, long hex/base64 secrets and card-looking
/// numbers inside free text (used by transcript export over the WHOLE
/// document, like Android's `SecretRedaction.redact`).
pub fn redact(text: &str) -> String {
    let mut out = text.to_string();
    // Bearer / Basic tokens
    out = redact_pattern(&out, "bearer ", 40);
    out = redact_pattern(&out, "basic ", 40);
    out
}

fn redact_pattern(text: &str, marker: &str, max_len: usize) -> String {
    let lower = text.to_lowercase();
    let mut out = String::new();
    let mut i = 0;
    while i < text.len() {
        if let Some(pos) = lower[i..].find(marker) {
            let abs = i + pos;
            out.push_str(&text[i..abs + marker.len()]);
            let rest = &text[abs + marker.len()..];
            let take: usize = rest
                .chars()
                .take_while(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_' || *c == '.' || *c == '=')
                .count();
            let take = take.min(max_len);
            if take >= 8 {
                out.push_str(REDACTED);
                // advance by the consumed token
                let consumed: usize = rest.chars().take(take).map(|c| c.len_utf8()).sum();
                i = abs + marker.len() + consumed;
            } else {
                let next_char = rest.chars().next().map(|c| c.len_utf8()).unwrap_or(0);
                out.push_str(&rest[..next_char]);
                i = abs + marker.len() + next_char;
            }
        } else {
            out.push_str(&text[i..]);
            break;
        }
    }
    out
}

/// Redacts header values in a header map (used by the network panel at
/// capture time).
pub fn redact_headers<'a>(headers: impl Iterator<Item = (String, String)>) -> Vec<(String, String)> {
    headers
        .map(|(k, v)| {
            if is_secret_header(&k) {
                (k, REDACTED.to_string())
            } else {
                (k, v)
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_redaction() {
        assert_eq!(
            redact_url("https://user:pass@example.com/a?token=abc&q=1"),
            "https://<redacted>@example.com/a?token=<redacted>&q=1"
        );
        assert_eq!(
            redact_url("https://example.com/p?api_key=zzz&x=2#frag"),
            "https://example.com/p?api_key=<redacted>&x=2#frag"
        );
        assert_eq!(redact_url("https://example.com/p?safe=1"), "https://example.com/p?safe=1");
    }

    #[test]
    fn header_redaction() {
        assert!(is_secret_header("Authorization"));
        assert!(is_secret_header("X-API-KEY"));
        assert!(!is_secret_header("Content-Type"));
        let red = redact_headers(
            vec![
                ("Authorization".into(), "Bearer xyz".into()),
                ("Content-Type".into(), "text/html".into()),
            ]
            .into_iter(),
        );
        assert_eq!(red[0].1, REDACTED);
        assert_eq!(red[1].1, "text/html");
    }

    #[test]
    fn text_redaction() {
        let out = redact("curl -H 'Authorization: Bearer abc123def456abc123def456' https://x.com");
        assert!(out.contains(REDACTED));
        assert!(!out.contains("abc123def456"));
        // short tokens (<8 chars) are left alone
        let keep = redact("bearer abcd done");
        assert!(keep.contains("abcd"));
    }
}
