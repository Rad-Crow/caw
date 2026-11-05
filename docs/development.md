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
| `docs/` | white paper, protocol specification, API reference, guides |

## Running locally

```sh
cd server && CAW_PUBLIC_URL=http://localhost:5173 cargo run
cd web && npm install && npm run dev     # http://localhost:5173, proxies to :8080
```

## Checks

```sh
cd server && cargo clippy && cargo test
cd web && npm run check && node --experimental-strip-types test/crypto.test.ts
cd android && ./gradlew testDebugUnitTest
e2e/run.sh    # two servers, headless browsers; screenshots in e2e/out/
```

The web and Android crypto tests check the test vectors in [protocol.md](protocol.md#12-test-vectors).

## Android

Building without a local SDK: [android/README.md](../android/README.md).

## Releases

Tags: `android-vX.Y.Z` (built by F-Droid) and `server-vX.Y.Z`.
