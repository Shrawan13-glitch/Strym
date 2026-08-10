# Strym Android — common tasks.
# Examples:
#   make native-release      build .so for arm64-v8a + x86_64
#   make apk                 assembleDebug + unit tests
#   make install             install CI-built APK on a connected device

.PHONY: native native-release apk test install bindings

native:            # arm64-v8a debug .so (fast)
	./scripts/build-native.sh debug

native-release:    # arm64-v8a + x86_64 release .so
	./scripts/build-native.sh release

bindings:          # regenerate committed Kotlin bindings (after core-rev bump)
	./scripts/generate-bindings.sh

apk:               # assemble debug APK + run unit tests
	./gradlew --no-daemon assembleDebug testDebugUnitTest

test:              # JVM unit tests only
	./gradlew --no-daemon testDebugUnitTest

install:           # install CI-built APK on a connected device
	./scripts/install.sh
