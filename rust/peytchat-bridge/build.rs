use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let workspace = manifest_dir.ancestors().nth(2).unwrap().to_path_buf();
    let target = env::var("TARGET").unwrap();
    let profile = env::var("PROFILE").unwrap();

    let core_target = workspace
        .join("core")
        .join("target")
        .join(&target)
        .join(&profile);
    let deltachat_a = core_target.join("libdeltachat.a");

    if deltachat_a.exists() {
        println!("cargo:rustc-link-search=native={}", core_target.display());
        println!("cargo:rustc-link-lib=static=deltachat");
        println!("cargo:rerun-if-changed={}", deltachat_a.display());
    } else {
        eprintln!(
            "warning: libdeltachat.a not found at {}; \
             build deltachat_ffi first (cargo ndk -t arm64-v8a build -p deltachat_ffi in core/)",
            deltachat_a.display()
        );
    }
}
