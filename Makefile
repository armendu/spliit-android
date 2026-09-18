# Everything this project needs, from the command line. Android Studio is never required.
#
#   make test      fast unit tests (seconds, no emulator)
#   make build     assemble the debug APK
#   make lint      Android Lint
#   make e2e       the instrumented suite against the shared server
#   make run       install and launch the debug APK on the running emulator
#   make shot      screenshot that emulator
#
# `make` on its own lists every target, which is the only documentation of this file that
# cannot go stale.

# Android Studio's JBR, exported so nobody has to remember it and so `./gradlew` picks the same
# JDK here as it does in the IDE. The system `java` on a dev machine here is 11; AGP 9.4 needs
# 17 or newer and the toolchains ask for 21, and the failure when it gets 11 is an unreadable
# class-file-version error rather than anything naming the JDK.
#
# Applied only when the JBR is actually present, which is what makes this file work unchanged on
# CI: there is no Android Studio on a Linux runner, so `setup-java`'s JAVA_HOME stands. Note
# this deliberately OVERRIDES an existing JAVA_HOME rather than deferring to it (`?=`) — the
# whole point is to win against a stale Java 11 left in somebody's shell profile. Point JBR
# elsewhere if you have a reason to.
# The test is a shell `test -x` and not make's own $(wildcard …) because "Android Studio.app"
# contains a space, and $(wildcard) splits its argument on whitespace — it silently matches
# nothing and you get Gradle refusing to run on JVM 11 with no hint as to why.
JBR ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
ifneq ($(shell test -x "$(JBR)/bin/java" && echo found),)
export JAVA_HOME := $(JBR)
endif

GRADLE      := ./gradlew
SDK         ?= $(HOME)/Library/Android/sdk
ADB         := $(SDK)/platform-tools/adb
EMULATOR    := $(SDK)/emulator/emulator
AVD         ?= Pixel_8_API_31

APPLICATION_ID := app.spliit.android
LAUNCH         := $(APPLICATION_ID)/.MainActivity
APK            := app/build/outputs/apk/debug/app-debug.apk

# 10.0.2.2 is the host's loopback as seen from inside an Android emulator — `localhost` there is
# the emulator itself. Seeding runs on this machine and uses localhost; only the app's own base
# URL needs the translation. See e2e/README.md.
E2E_URL      ?= http://10.0.2.2:3009/
E2E_HOST_URL ?= http://localhost:3009/

.DEFAULT_GOAL := help
.PHONY: help test build lint test-live e2e e2e-up e2e-down e2e-seed fixtures emulator run shot clean

help: ## List these targets
	@grep -E '^[a-z0-9-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

# :api and :core are plain Kotlin/JVM, which is the whole point of them being separate modules:
# the transport, money maths and split logic are testable in seconds with nothing booted. If
# this target ever needs an emulator, a dependency has gone in the wrong module.
test: ## Fast unit tests — the JVM modules, no emulator
	@$(GRADLE) :api:test :core:test

build: ## Assemble the debug APK
	@$(GRADLE) :app:assembleDebug

lint: ## Android Lint
	@$(GRADLE) lint

# The API client against a server that talks back, including writes. Separate from `make test`
# because it needs something running, and pointed at the host address rather than the
# emulator's: this runs on the JVM, on this machine. Selects by JUnit5 @Tag("live") — see
# :api's build.gradle.kts — rather than by class name, so `make test` can exclude the same tag
# and never accidentally depend on a server being up.
test-live: ## API tests against a real server (needs make e2e-up, or BASE_URL=…)
	@$(GRADLE) :api:testLive -Pspliit.baseUrl=$(or $(BASE_URL),$(E2E_HOST_URL))

e2e-up: ## Start the shared Spliit instance on :3009, if it isn't already up
	@curl -sf $(E2E_HOST_URL)api/health/readiness >/dev/null || { \
		docker compose -f e2e/compose.yaml up -d --wait; \
		docker compose -f e2e/compose.yaml run --rm --quiet-pull s3-policy >/dev/null; \
	}
	@echo "Spliit is up at $(E2E_HOST_URL) — make e2e-down stops it."

# The warning is printed rather than left to `make help`, because the person about to discard
# somebody else's in-flight test data is typing this target, not reading the target list.
e2e-down: ## Stop it and discard its data
	@echo "Stopping the shared Spliit instance and discarding its data — the database and the"
	@echo "document bucket are both tmpfs, so anything any run has put there goes with it."
	@docker compose -f e2e/compose.yaml down -v

e2e-seed: ## Load fixture groups and expenses into the running instance
	@node e2e/seed.mjs --base-url $(E2E_HOST_URL)

fixtures: ## Re-record the API fixtures the unit tests decode
	@node Scripts/record-fixtures.mjs --base-url $(E2E_HOST_URL) \
		--out api/src/test/resources/fixtures
	@echo "Fixtures refreshed."

# Deliberately leaves the server running. A run that tore it down on its way out would be
# pulling the floor from under anything else testing at the same time — and the database lives
# in tmpfs, so it would take their data with it. Stopping it is `make e2e-down`, on purpose,
# when nothing is using it.
e2e: ## Full end-to-end run: the instrumented suite against the shared server
	@$(MAKE) e2e-up
	@$(GRADLE) :app:connectedDebugAndroidTest -Pspliit.baseUrl=$(E2E_URL)

# Detached, because the emulator holds the terminal for as long as it runs. `adb wait-for-device`
# returns as soon as adbd answers, which is well before the launcher is up — hence the second
# wait on sys.boot_completed, which is the one that means the device can install an APK.
emulator: ## Boot the emulator and wait until it can take an install
	@pgrep -q qemu-system || $(EMULATOR) -avd $(AVD) -netdelay none -netspeed full >/dev/null 2>&1 &
	@$(ADB) wait-for-device
	@while [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do sleep 1; done
	@echo "$(AVD) is up."

run: build ## Install and launch the debug APK on the running device
	@$(ADB) install -r $(APK)
	@$(ADB) shell am start -n $(LAUNCH)

shot: ## Screenshot the running device into build/screenshot.png
	@mkdir -p build
	@$(ADB) exec-out screencap -p > build/screenshot.png
	@echo "Wrote build/screenshot.png."

clean: ## Delete all build output
	@$(GRADLE) clean
	@rm -rf build
