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

There are **two DHL providers**:
- `DhlDeDeliveryService` — uses the public `dhl.de` tracking endpoint, **no API key required**, 20-digit tracking numbers. This is the actively maintained one.
- `DhlDeliveryService` — uses the official `api-eu.dhl.com` Tracking API, **requires a user-supplied DHL API key**. Not a focus for new development.

### Carrier tracking-website links (open in app / open from app)

Two independent, optional features per carrier — a carrier can have either, both, or neither. DHL (`DhlDeDeliveryService`) and GLS (`GLSGlobalDeliveryService`) implement both; use them as reference.

**"Open in app"** — tapping a carrier's own tracking link (e.g. from an SMS/email) opens this app instead of a browser, pre-filling the Add Parcel form.
1. Add an `<intent-filter>` in `AndroidManifest.xml` under `.MainActivity`, matching `android:scheme="https"`, `android:host="<carrier tracking domain>"`, `android:pathPrefix="<path the tracking page lives at>"`.
2. Add a branch in `MainActivity.parseDeepLink()` matching on `uri.host`/`uri.path`, extracting the tracking ID (and postal code, if the carrier's `DeliveryService.requiresPostCode` or `acceptsPostCode`) via `uri.getQueryParameter(...)`.
   - Always validate the tracking ID (non-empty, and restrict to a safe charset like `^[a-zA-Z0-9]+$`) before returning — an unvalidated ID becomes user-visible text and a `getParcel` argument.
   - Return `AddParcelPage(trackingId = ..., serviceOrdinal = Service.<X>.ordinal, postalCode = ... ?: "")`.
3. Add the new host to the "invalid tracking link" host list in `onNewIntent()` so malformed links from that domain show the `invalid_tracking_link` toast instead of silently no-opping.
4. `AddParcelPage` already carries `postalCode` — if a new carrier's link needs more fields than `trackingId`/`postalCode`, extend that data class and thread the new field through to `AddEditParcelView`'s `initial*` params (see `initialPostalCode`).
5. Add unit tests in `MainActivityTest.kt` mirroring the existing DHL/GLS cases (happy path, missing field, empty field, non-matching host).

**"Open from app"** — the parcel detail screen (`ParcelView`) shows an "open tracking page" icon button that opens the carrier's public tracking URL in the user's browser.
1. Override `trackingUrl(trackingId: String, postalCode: String? = null): String?` on the `DeliveryService` object, returning the carrier's public tracking URL for that ID (and postal code, if the URL needs one).
2. `ParcelView` already resolves and wires this up (`getDeliveryService(service)?.trackingUrl(parcel.id, postalCode)`) — no UI changes needed.
3. If a carrier has region-specific tracking domains (e.g. GLS has different national sites), only override `trackingUrl` on the concrete `object` for the region you've verified, not the shared open class — don't guess at URLs for regions you haven't confirmed.

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
