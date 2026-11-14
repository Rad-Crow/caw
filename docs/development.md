# Development

## Repository

| directory | |
|---|---|
| `server/` | Rust (axum, sqlx with SQLite): HTTP API, WebSocket signaling, serves the web app |
| `web/` | Svelte and TypeScript |
| `android/` | Kotlin and Compose; F-Droid metadata in `android/fastlane/` |
| `protocol/` | verification word list, shared by both clients |
| `deploy/` | coturn and Caddy configuration, container entrypoint |
| `e2e/` | browser end-to-end test |
| `examples/` | `.env` templates |
| `tools/` | `sounds.py` renders the call and message sounds for both clients (needs ffmpeg) |
| `docs/` | white paper, protocol specification, API reference, guides, screenshots |

## Running locally

```sh
cd server && CAW_PUBLIC_URL=http://localhost:5173 cargo run
cd web && npm install && npm run dev     # http://localhost:5173, proxies to :8080
```

## Checks

```sh
cd server && cargo clippy && cargo test
cd web && npm run check && node --experimental-strip-types test/crypto.test.ts && node --experimental-strip-types test/groups.test.ts && node --experimental-strip-types test/remux.test.ts
cd android && ./gradlew testDebugUnitTest
cd android && ./gradlew connectedDebugAndroidTest    # needs a device or emulator
e2e/run.sh    # two servers, headless browsers; screenshots in e2e/out/
```

The web and Android tests check the test vectors in [protocol.md](protocol.md#13-test-vectors) and `testdata/groups.json`. `testdata/media` holds a photo and two videos (H.264 and VP9) with location and other metadata; the web, Android and e2e tests check that none of it is sent.

## Android

Building without a local SDK: [android/README.md](../android/README.md).

## Releases

Tags: `android-vX.Y.Z` (built by F-Droid) and `server-vX.Y.Z`.

Android release:

- raise `versionCode` and `versionName` in `android/app/build.gradle.kts`
- add `android/fastlane/metadata/android/en-US/changelogs/<versionCode * 10 + 4>.txt`
- tag `android-vX.Y.Z`

An APK's versionCode is `versionCode * 10 + n`: universal 0, armeabi-v7a 1, x86 2, arm64-v8a 3, x86_64 4. `-Pabi=<abi>` builds a single-ABI APK, which is what F-Droid builds; without it the build is universal.
