//! Platform bridge: re-export the pinned core's UniFFI facade.
//!
//! The `stream-ffi` crate emits the UniFFI scaffolding (all exported symbols)
//! via its own `uniffi::setup_scaffolding!()`. Depending on it here and naming
//! this crate's cdylib `stream_ffi` gives us `libstream_ffi.so` carrying every
//! symbol the Kotlin bindings link against.

pub use stream_ffi::*;
