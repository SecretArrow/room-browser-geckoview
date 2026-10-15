//! Prefers the STATIC WebView2Loader lib that webview2-com-sys drops into
//! its OUT_DIR (already on the link search path), so the release exe does
//! not depend on WebView2Loader.dll at load time. The DLL still ships in
//! the zip as a fallback for exotic setups.
fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rustc-link-lib=static=WebView2LoaderStatic");
        // Resolve WebView2.dll exports at runtime instead of the loader:
        println!("cargo:rustc-link-lib=dylib=runtimeobject");
    }
}
