# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About

Deliveries is an Android parcel-tracking app (package ID `dev.itsvic.parceltracker`, min SDK 26, target SDK 35). It is distributed via Google Play, F-Droid, and IzzyOnDroid. Licensed GPL-3.0-or-later.

## Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (unsigned unless keystore.properties exists)
./gradlew assembleRelease

# Run unit tests
./gradlew testRelease
# or for a specific test class:
./gradlew testDebugUnitTest --tests "FormatValidationTest"

# Format all Kotlin source
./scripts/ktfmt.sh .

# Sort translation XML files by key (requires Nix for xsltproc)
./scripts/sort-strings.sh
# Check only (no write):
./scripts/sort-strings-check.sh

# Fastlane: release to Play Store production
bundle exec fastlane release

# Fastlane: release to closed beta (alpha track)
bundle exec fastlane alpha
```

CI runs `assembleRelease` + `testRelease` on every push/PR (`.github/workflows/build.yml`).

## Architecture

### Layers

```
api/          — one object per carrier, all implement DeliveryService
db/           — Room entities: Parcel, ParcelStatus, ParcelHistoryItem
ui/views/     — Compose screens (Home, AddEditParcel, Parcel detail, Settings)
ui/components — Reusable Compose components
```

### Adding a new carrier

1. Create `app/src/main/java/dev/itsvic/parceltracker/api/<Name>DeliveryService.kt` as a Kotlin `object` implementing `DeliveryService`.
2. Add an entry to the `Service` enum in `api/Core.kt`.
3. Wire it into `getDeliveryService()` in `api/Core.kt`.
4. Add a string resource `service_<name>` in `res/values/strings.xml` (then propagate to all translation files and run `sort-strings.sh`).

`DeliveryService` contract (`api/Core.kt`):
- `nameResource: Int` — string resource for display name
- `acceptsPostCode: Boolean` / `requiresPostCode: Boolean`
- `requiresApiKey: Boolean` + `apiKeyPreference` — for services needing a user-supplied key
- `acceptsFormat(trackingId: String): Boolean` — regex-based format hint (used in UI auto-detection)
- `getParcel(trackingId, postalCode): Parcel` — main fetch; throw `ParcelNonExistentException` or `APIKeyMissingException` on failure

`ExampleDeliveryService` is Demo Mode only — not a real carrier.

### Data model

There are two `Parcel` types — don't confuse them:
- `api.Parcel` — ephemeral API response with history and `Status`
- `db.Parcel` — Room entity (the user's saved tracking entry)

`db.ParcelStatus` and `db.ParcelHistoryItem` are separate Room entities linked by `parcelId`.

### Background refresh

`NotificationWorker` (WorkManager, 15-min periodic) polls all non-archived parcels, compares `lastChange` timestamps, and fires a notification if the status changed. Re-enqueued via `enqueueNotificationWorker()` on settings changes.

### Settings

`DataStore<Preferences>` via `Context.dataStore` (defined in `Settings.kt`). Keys: `DEMO_MODE`, `UNMETERED_ONLY`, `DHL_API_KEY`.

### HTTP / serialization

All carriers use a shared `OkHttpClient` (`api_client`) with optional body logging in debug builds. Retrofit + Moshi handle JSON; `@JsonClass(generateAdapter = true)` is required on all Moshi data classes (KSP generates the adapters). Some services use direct OkHttp instead of Retrofit.

## Code style

- `ktfmt` enforces formatting — run `./scripts/ktfmt.sh .` before committing.
- Translation strings must stay sorted — run `./scripts/sort-strings.sh` after editing any `strings.xml`.
- `dependenciesInfo` is disabled in the APK/bundle (F-Droid policy).
