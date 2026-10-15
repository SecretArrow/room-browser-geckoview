//! DNS modes, presets and effective-mode reporting — port of `rb_dns.c`
//! (which mirrors Android's `DnsPresets` / `DnsValidator` semantics at the
//! configuration level).

use crate::prefs::DnsMode;

/// Port of `RB_DNS_STATUS_NAMES`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsStatus {
    System,
    ProtectedDoh,
    ProtectedDot,
    Misconfigured,
}

impl DnsStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            DnsStatus::System => "system",
            DnsStatus::ProtectedDoh => "protected_doh",
            DnsStatus::ProtectedDot => "protected_dot",
            DnsStatus::Misconfigured => "misconfigured",
        }
    }
}

#[derive(Debug, Clone)]
pub struct DnsPreset {
    pub id: &'static str,
    pub label: &'static str,
    pub primary_ipv4: &'static str,
    pub secondary_ipv4: &'static str,
    pub doh_url: &'static str,
}

pub const PRESETS: &[DnsPreset] = &[
    DnsPreset {
        id: "cloudflare",
        label: "Cloudflare",
        primary_ipv4: "1.1.1.1",
        secondary_ipv4: "1.0.0.1",
        doh_url: "https://cloudflare-dns.com/dns-query",
    },
    DnsPreset {
        id: "google",
        label: "Google",
        primary_ipv4: "8.8.8.8",
        secondary_ipv4: "8.8.4.4",
        doh_url: "https://dns.google/dns-query",
    },
    DnsPreset {
        id: "opendns",
        label: "OpenDNS",
        primary_ipv4: "208.67.222.222",
        secondary_ipv4: "208.67.220.220",
        doh_url: "https://doh.opendns.com/dns-query",
    },
];

pub fn preset(id: &str) -> Option<&'static DnsPreset> {
    PRESETS.iter().find(|p| p.id == id)
}

/// A DoH URL is usable when it is a well-formed https:// URL with a host.
pub fn valid_doh_url(url: &str) -> bool {
    let u = url.trim();
    if !u.to_lowercase().starts_with("https://") {
        return false;
    }
    let rest = &u[8..];
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    !rest[..host_end].is_empty() && !rest[..host_end].contains(char::is_whitespace)
}

/// A DoT hostname is usable when it is a bare hostname (or IP) without
/// scheme, slash or whitespace.
pub fn valid_dot_hostname(host: &str) -> bool {
    let h = host.trim();
    !h.is_empty()
        && !h.contains("://")
        && !h.contains('/')
        && !h.contains(char::is_whitespace)
}

/// The effective-mode note the Network preferences page shows — the same
/// logic the C core used to decide which label to render.
pub fn effective_status(mode: DnsMode, doh_url: &str, dot_host: &str) -> DnsStatus {
    match mode {
        DnsMode::System => DnsStatus::System,
        DnsMode::Auto => DnsStatus::ProtectedDoh,
        DnsMode::Doh => {
            if valid_doh_url(doh_url) {
                DnsStatus::ProtectedDoh
            } else {
                DnsStatus::Misconfigured
            }
        }
        DnsMode::Dot => {
            if valid_dot_hostname(dot_host) {
                DnsStatus::ProtectedDot
            } else {
                DnsStatus::Misconfigured
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn statuses() {
        assert_eq!(effective_status(DnsMode::System, "", ""), DnsStatus::System);
        assert_eq!(effective_status(DnsMode::Auto, "", ""), DnsStatus::ProtectedDoh);
        assert_eq!(
            effective_status(DnsMode::Doh, "https://cloudflare-dns.com/dns-query", ""),
            DnsStatus::ProtectedDoh
        );
        assert_eq!(effective_status(DnsMode::Doh, "http://plain.example", ""), DnsStatus::Misconfigured);
        assert_eq!(effective_status(DnsMode::Dot, "", "dns.google"), DnsStatus::ProtectedDot);
        assert_eq!(effective_status(DnsMode::Dot, "", "https://dns.google"), DnsStatus::Misconfigured);
    }

    #[test]
    fn presets_exist() {
        assert!(preset("cloudflare").is_some());
        assert!(preset("nope").is_none());
    }
}
