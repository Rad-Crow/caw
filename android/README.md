# Caw for Android

Kotlin and Compose. WebRTC from `im.conversations.webrtc`; push through the UnifiedPush connector.

## Build

No local SDK required. Mount the whole repository; the build reads `../protocol`:

```sh
docker volume create caw-gradle
docker run --rm -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/gradle -e HOME=/tmp \
  -v caw-gradle:/gradle -v "$PWD/..":/w -w /w/android ghcr.io/cirruslabs/android-sdk:35 \
  ./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Debug builds are signed with `debug.keystore` in this directory when present (gitignored), so container and Android Studio builds can update each other's installs:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug"
```

## Incoming calls

1. The app registers with the user's UnifiedPush distributor using the server's VAPID key and sends the endpoint and its WebPush keys to `POST /api/push`.
2. On an incoming call the server sends an encrypted push, `{"type":"call","call_id":...}`. Pushes that fail to decrypt are dropped.
3. The app opens the websocket; the `hello` message includes the ringing call.
4. A full-screen CallStyle notification rings; answering opens `CallActivity`.

Without a distributor, the websocket is only connected while the app is open.
