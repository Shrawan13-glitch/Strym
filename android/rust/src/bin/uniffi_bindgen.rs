//! `uniffi-bindgen` entry point, matching the pinned core's uniffi 0.32.
//! Generates the Kotlin bindings from the compiled `libstream_ffi.so`.
//! See `scripts/generate-bindings.sh`.

fn main() {
    uniffi::uniffi_bindgen_main();
}
