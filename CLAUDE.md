# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

JimTimeWear — Wear OS companion app for JimTime. Single-module Android Gradle project (`:app`), Kotlin + Kotlin DSL build scripts.

## Layout

- `app/` — the only Gradle module. Sources under `app/src/main/`. Module build script: `app/build.gradle.kts`.
- Top-level: `build.gradle.kts`, `settings.gradle.kts` (root project name `JimTimeWear`, includes only `:app`), `gradle.properties`, `gradle/` wrapper.
- `local.properties` is local-only (SDK path) — do not commit values from it.

## Common commands

```bash
./gradlew tasks                          # discover available tasks
./gradlew assembleDebug                  # build debug APK
./gradlew installDebug                   # install on connected Wear device/emulator
./gradlew test                           # unit tests
./gradlew connectedAndroidTest           # instrumented tests (needs device)
./gradlew lint
./gradlew :app:dependencies              # inspect dependency tree
```

Or open the project in Android Studio and use the Wear OS emulator / paired device.

## Gotchas

- Wear OS target — UI uses Wear-specific Compose / components, not phone Compose. Keep that in mind when adding screens.
- Pairing-dependent features (sync with the JimTime phone app) need a paired emulator setup or a real watch + phone.
