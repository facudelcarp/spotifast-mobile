fn main() {
    // Android 4.4 (32 bits): `dlsym(RTLD_DEFAULT, …)` recorre todas las librerías cargadas y el
    // linker de KitKat divide por cero en una entrada sin tabla hash (SIGFPE). Redirigimos todas
    // las llamadas a dlsym de este binario a `__wrap_dlsym` (ver src/compat.rs).
    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    let width = std::env::var("CARGO_CFG_TARGET_POINTER_WIDTH").unwrap_or_default();
    if os == "android" && width == "32" {
        println!("cargo:rustc-cdylib-link-arg=-Wl,--wrap=dlsym");
    }
}
