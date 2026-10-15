//! Bakes the release version into the crate. CI exports `RB_VERSION`
//! (the same number the Android release carries); everything else gets the
//! dev placeholder so a bare build never claims to be a release.

fn main() {
    let ver = std::env::var("RB_VERSION").unwrap_or_else(|_| "0.0.0-dev".into());
    println!("cargo:rustc-env=RB_VERSION={ver}");
    println!("cargo:rerun-if-env-changed=RB_VERSION");
}
