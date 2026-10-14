.PHONY: dev backend reverse test test-android test-backend build help

ADB := $(shell command -v adb 2>/dev/null || echo "$$HOME/Library/Android/sdk/platform-tools/adb")

help:
	@echo "Talk to My Finances Monorepo Commands:"
	@echo "  make dev          - Start backend server and run adb reverse for local development"
	@echo "  make backend      - Run the Python uvicorn backend server (port 8000)"
	@echo "  make reverse      - Run adb reverse tcp:8000 tcp:8000 for connected devices/emulators"
	@echo "  make test         - Run both Android unit tests and Python backend tests"
	@echo "  make test-android - Run Android unit tests"
	@echo "  make test-backend - Run Python backend pytest suite"
	@echo "  make build        - Assemble Android debug build"

dev: reverse backend

backend:
	cd backend && uv run --env-file .env uvicorn auditor.main:app --host 0.0.0.0 --port 8000 --reload

reverse:
	@echo "Configuring adb reverse port forwarding (8000 -> 8000)..."
	@"$(ADB)" reverse tcp:8000 tcp:8000 2>/dev/null || echo "Notice: adb reverse skipped (no connected ADB device found)."

test: test-android test-backend

test-android:
	./gradlew testDebugUnitTest

test-backend:
	cd backend && uv run pytest

build:
	./gradlew assembleDebug
