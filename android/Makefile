.DEFAULT_GOAL := help

.PHONY: help native debug bindings apk android-test install clean

help:
	@echo "Strym Android build targets"
	@echo "  make native      - release .so for arm64-v8a + x86_64 into jniLibs/"
	@echo "  make debug       - arm64-v8a-only .so (faster local turnaround)"
	@echo "  make bindings    - regenerate Kotlin bindings from the pinned core"
	@echo "  make apk         - debug APK via Gradle"
	@echo "  make android-test - instrumented lifecycle tests on a connected device"
	@echo "  make install     - latest CI APK -> adb install -> launch"
	@echo "  make clean       - cargo clean + gradle clean"

native:
	./scripts/build-native.sh release

debug:
	./scripts/build-native.sh debug

bindings:
	./scripts/generate-bindings.sh

apk:
	./gradlew --no-daemon assembleDebug testDebugUnitTest

android-test:
	./gradlew --no-daemon connectedDebugAndroidTest

install:
	./scripts/install.sh

clean:
	cargo clean --manifest-path rust/Cargo.toml || true
	./gradlew clean || true
