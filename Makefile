.PHONY: dev dev-device backend reverse test test-android test-backend build install clear-data fresh-android restart restart-device restart-emulator switch-branch help

ADB := $(shell command -v adb 2>/dev/null || echo "$$HOME/Library/Android/sdk/platform-tools/adb")
GRADLE := unset ANDROID_PREFS_ROOT; ./gradlew

help:
	@echo "Talk to My Finances Monorepo Commands:"
	@echo "  make dev              - Start backend server & background ADB reverse watcher (emulator + USB physical device)"
	@echo "  make dev-device       - Start backend server, display local LAN IPs, & maintain ADB reverse for physical devices"
	@echo "  make backend          - Run the Python uvicorn backend server (port 8000)"
	@echo "  make reverse          - Run adb reverse tcp:8000 tcp:8000 once for connected devices/emulators"
	@echo "  make test             - Run both Android unit tests and Python backend tests"
	@echo "  make test-android     - Run Android unit tests"
	@echo "  make test-backend     - Run Python backend pytest suite"
	@echo "  make build            - Assemble Android debug build"
	@echo "  make install          - Install debug build on device/emulator and launch MainActivity"
	@echo "  make fresh-android    - Incrementally build, install, & launch latest code on all devices"
	@echo "  make restart          - Force-stop & cold-restart the app on all connected devices/emulators"
	@echo "  make restart-device   - Force-stop & cold-restart the app on connected physical device(s) (Wi-Fi or USB)"
	@echo "  make restart-emulator - Force-stop & cold-restart the app on running emulator(s)"
	@echo "  make clear-data       - Clear app data/cache on connected device/emulator"

dev:
	@echo "Starting ADB reverse watcher for emulators & physical devices..."
	@(while true; do \
		DEVICES=$$("$(ADB)" devices 2>/dev/null | grep -w "device" | awk '{print $$1}'); \
		for dev in $$DEVICES; do \
			"$(ADB)" -s "$$dev" reverse tcp:8000 tcp:8000 2>/dev/null || true; \
		done; \
		sleep 5; \
	done) & WATCHER_PID=$$!; \
	trap 'kill $$WATCHER_PID 2>/dev/null' EXIT INT TERM; \
	$(MAKE) backend

dev-device:
	@echo "======================================================="
	@echo "Serving backend for Physical Device / LAN Testing:"
	@echo "  Host: 0.0.0.0:8000"
	@echo "  Local IP Address(es) for Remote Config:"
	@ifconfig | grep "inet " | grep -v 127.0.0.1 | awk '{print "    http://" $$2 ":8000"}' || true
	@echo "======================================================="
	@$(MAKE) dev

backend:
	cd backend && uv run --env-file .env uvicorn auditor.main:app --host 0.0.0.0 --port 8000 --reload

reverse:
	@echo "Configuring adb reverse port forwarding (8000 -> 8000)..."
	@DEVICES=$$("$(ADB)" devices | grep -w "device" | awk '{print $$1}'); \
	if [ -z "$$DEVICES" ]; then \
		echo "Notice: adb reverse skipped (no connected ADB device found)."; \
	else \
		for dev in $$DEVICES; do \
			echo "Setting adb reverse on $$dev..."; \
			"$(ADB)" -s "$$dev" reverse tcp:8000 tcp:8000 2>/dev/null || true; \
		done; \
	fi

test: test-android test-backend

test-android:
	$(GRADLE) testDebugUnitTest

test-backend:
	cd backend && uv run pytest

build:
	$(GRADLE) assembleDebug

install-latest:
	@echo "Building & deploying latest Android code..."
	$(GRADLE) installDebug
	@$(MAKE) restart

restart:
	@DEVICES=$$("$(ADB)" devices | grep -w "device" | awk '{print $$1}'); \
	if [ -z "$$DEVICES" ]; then \
		echo "Notice: no connected ADB device found."; \
	else \
		for dev in $$DEVICES; do \
			echo "Cold restarting app on $$dev..."; \
			"$(ADB)" -s "$$dev" shell am force-stop com.talktomyfinances.android 2>/dev/null || true; \
			"$(ADB)" -s "$$dev" shell am start -n com.talktomyfinances.android/.MainActivity 2>/dev/null || true; \
		done; \
	fi

restart-device:
	@PHYS_DEVS=$$("$(ADB)" devices | grep -w "device" | awk '{print $$1}' | grep -v "^emulator-"); \
	if [ -z "$$PHYS_DEVS" ]; then \
		echo "No physical device found connected via Wi-Fi or USB."; \
	else \
		for dev in $$PHYS_DEVS; do \
			echo "Cold restarting app on physical device ($$dev)..."; \
			"$(ADB)" -s "$$dev" shell am force-stop com.talktomyfinances.android 2>/dev/null || true; \
			"$(ADB)" -s "$$dev" shell am start -n com.talktomyfinances.android/.MainActivity 2>/dev/null || true; \
		done; \
	fi

restart-emulator:
	@EMU_DEVS=$$("$(ADB)" devices | grep -w "device" | awk '{print $$1}' | grep "^emulator-"); \
	if [ -z "$$EMU_DEVS" ]; then \
		echo "No running emulator found."; \
	else \
		for dev in $$EMU_DEVS; do \
			echo "Cold restarting app on emulator ($$dev)..."; \
			"$(ADB)" -s "$$dev" shell am force-stop com.talktomyfinances.android 2>/dev/null || true; \
			"$(ADB)" -s "$$dev" shell am start -n com.talktomyfinances.android/.MainActivity 2>/dev/null || true; \
		done; \
	fi

clear-data:
	@DEVICES=$$("$(ADB)" devices | grep -w "device" | awk '{print $$1}'); \
	if [ -z "$$DEVICES" ]; then \
		echo "Notice: no connected ADB device found."; \
	else \
		for dev in $$DEVICES; do \
			echo "Clearing app data on $$dev..."; \
			"$(ADB)" -s "$$dev" shell pm clear com.talktomyfinances.android 2>/dev/null || true; \
		done; \
	fi

switch-branch: fresh-android

