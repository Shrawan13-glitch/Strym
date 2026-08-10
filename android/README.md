# Android publisher app

The user-facing app: Jetpack Compose UI on top of the core's UniFFI
bindings (`libstream_ffi.so`). Layout, toolchain, and phase status live in
[PLAN.md](../PLAN.md); building the core for Android is documented in
[docs/core-integration.md](../docs/core-integration.md).

```sh
make apk           # debug APK (native libs must be present, or build in CI)
make android-test  # instrumented lifecycle tests on a connected device
make install       # latest CI APK -> adb install -> launch
```

Normal development builds run in CI (this machine is not an Android dev
host); the APK artifact is uploaded as `strym-debug-apk`.
