//! Device-identity catalogue — 1041 real desktop/laptop machines
//! (2022-2025), generated from the same table the C edition ships.

mod data {
    include!("devices_data.rs");
}

pub use data::{Device, DEVICES};

pub fn by_id(id: &str) -> Option<&'static Device> {
    DEVICES.iter().find(|d| d.id == id)
}

/// Random un-taken assignment for a new profile (the taken set is the ids
/// already used by other profiles; the caller passes them).
pub fn random_free(taken: &[String]) -> Option<&'static Device> {
    let free: Vec<&Device> = DEVICES.iter().filter(|d| !taken.iter().any(|t| t == d.id)).collect();
    if free.is_empty() {
        return None;
    }
    use rand::Rng;
    Some(free[rand::thread_rng().gen_range(0..free.len())])
}

/// Search the catalogue by brand/model/year (case-insensitive substring),
/// marking taken machines so the picker UI can show them.
pub fn search(query: &str, limit: usize) -> Vec<&'static Device> {
    let q = query.to_lowercase();
    let mut out: Vec<&Device> = Vec::new();
    for d in DEVICES {
        if q.is_empty()
            || d.brand.to_lowercase().contains(&q)
            || d.model.to_lowercase().contains(&q)
            || d.year.to_string().contains(&q)
        {
            out.push(d);
            if out.len() >= limit {
                break;
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catalogue_shape() {
        assert_eq!(DEVICES.len(), 1041);
        // every fingerprint distinct (the generator's invariant)
        let mut ids: Vec<&str> = DEVICES.iter().map(|d| d.id).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), DEVICES.len());
        // desktop rules: no Mobile token, memory capped at 8
        for d in DEVICES.iter() {
            assert!(!d.ua.contains("Mobile"));
            assert!(d.memory <= 8);
            assert!(matches!(d.os, "windows" | "macos" | "linux"));
            assert!(matches!(d.form, "laptop" | "desktop"));
        }
    }

    #[test]
    fn lookup_and_search() {
        assert!(by_id(DEVICES[0].id).is_some());
        assert!(by_id("nope").is_none());
        assert!(!search("", 5).is_empty());
        assert!(!search("thinkpad", 5).is_empty());
    }
}
