# Development notes

## Toolchain

| Component | Version |
|---|---|
| JDK (Gradle daemon / toolchain / CI) | 27 (Temurin) |
| Java & Kotlin bytecode target | 25 (`JavaVersion.VERSION_25`, `JvmTarget.JVM_25`; Kotlin 2.4.21 supports at most 26) |
| Gradle wrapper | 9.8.1 |
| Android Gradle Plugin | 9.4.1 (built-in Kotlin; do not re-add `org.jetbrains.kotlin.android`) |
| Kotlin / KSP | 2.4.21 / 2.3.12 (annotation processing: KSP only, no kapt) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 |
| Android SDK packages | `platforms;android-37.0`, `build-tools;37.0.0` |
| LiteRT-LM | 0.18.0 |

Local setup used on Linux:
```bash
mise use -g java@temurin-27
# Android cmdline-tools unpacked in ~/Android/Sdk/cmdline-tools/latest, then:
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "platform-tools"
echo "sdk.dir=$HOME/Android/Sdk" > Android/src/local.properties
cd Android/src && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Notes:
- AGP 9 rejects `minSdkVersion`/`targetSdkVersion` in `AndroidManifest.xml`; set them only in `app/build.gradle.kts`.
- targetSdk 37 enforces edge-to-edge and the newer foreground-service rules. `PocketNodeService` uses the `specialUse` foreground service type on API 34+ (no daily time limit; manifest property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) and `dataSync` on API 31–33, and implements `onTimeout()` to stop cleanly if a time-limited type expires.
- Security scan: `trivy fs --scanners vuln,secret,misconfig .`. For Gradle dependencies, generate a temporary lockfile (init script with `dependencyLocking { lockAllConfigurations() }` + `./gradlew :app:dependencies --write-locks`), scan `app/gradle.lockfile`, then delete it. Last scan: no findings in app runtime dependencies; BouncyCastle 1.80.2 (CVE-2026-8763, CVE-2026-13506) is only pulled by the AGP lint tool (`androidLintTool`), not shipped in the APK.
- Versions re-checked against Maven Central, Google Maven and services.gradle.org on 2026-10-15: every pin above is already the latest stable (Kotlin 2.5.0-Beta1, AGP 9.5.0-alpha and CameraX 1.7.0-alpha are pre-releases and were not adopted).
- CI (`.github/workflows/build_android.yaml`) runs `:app:testDebugUnitTest`, `:app:lintDebug` and `assembleRelease`.
- The API key is stored with `EncryptedSharedPreferences` (`security-crypto`, file `pocket_node_secret_prefs`). `<queries>` in the manifest lets the app bind to the system text-to-speech engine (Android 11+ package visibility).
- Server screen strings live in `res/values/pocket_node_strings.xml` and `res/values-es/`; do not hardcode UI text (lint rejects `LocalContext.current` resource reads in Composables, use `stringResource`).
- OpenAI-compatible server API: see [docs/OPENAI_API.md](docs/OPENAI_API.md).

## Build app locally

To successfully build and run the application through Android Studio, you need to configure it with your own HuggingFace Developer Application ([official doc](https://huggingface.co/docs/hub/oauth#creating-an-oauth-app)). This is required for the model download functionality to work correctly.

After you've created a developer application:

1. In [`ProjectConfig.kt`](https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt), replace the placeholders for `clientId` and `redirectUri` with the values from your HuggingFace developer application.

1. In [`app/build.gradle.kts`](https://github.com/google-ai-edge/gallery/blob/c1b50e160a66d5ea2ec2d8d8e63088b3cc0761bc/Android/src/app/build.gradle.kts#L41-L44), modify the `manifestPlaceholders["appAuthRedirectScheme"]` value to match the redirect URL you configured in your HuggingFace developer application.
