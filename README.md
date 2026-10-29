# JARVIS Voice Assistant

An Android voice assistant built with Kotlin, Gemini Live, on-device voice components, accessibility automation, screen capture, and a native Android UI.

## Project status

- Android application module: `app`
- Application ID: `com.jarvis.assistant`
- Minimum Android version: Android 8.0 (API 26)
- Compile and target SDK: 34
- Java/Kotlin target: 17
- Current app version: 1.8.7 (version code 143)

## Getting started

1. Open the project root in Android Studio and allow Gradle to sync.
2. Set the Android SDK path in your local `local.properties` file. The file is machine-specific and is ignored by Git.
3. Launch the app on an Android 8.0+ device or emulator.
4. Add a Gemini API key in the app's Settings and grant the requested runtime permissions.

The Gradle wrapper is included in this project. Use `gradlew.bat` on Windows or `./gradlew` on macOS/Linux.

## Build commands

```powershell
# Windows: compile and package a debug APK
.\gradlew.bat :app:assembleDebug

# Windows: compile and package a release APK
.\gradlew.bat :app:assembleRelease
```

```bash
# macOS/Linux
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Build outputs are written under `app/build/outputs/` and are generated files, not source files. Release builds enable R8 and resource shrinking. `assembleRelease` intentionally refuses to package unless a dedicated keystore, its SHA-256 certificate pin, and publisher metadata are configured. Never commit keystores or passwords.

Create and protect a release key outside this repository, then supply these values only in the local shell or Gradle user properties:

```powershell
$env:JARVIS_RELEASE_STORE_FILE = 'C:\secure\jarvis-release.jks'
$env:JARVIS_RELEASE_STORE_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_KEY_ALIAS = 'jarvis-release'
$env:JARVIS_RELEASE_KEY_PASSWORD = '<local secret>'
$env:JARVIS_RELEASE_CERT_SHA256 = '<64 hex characters from keytool -list -v>'
$env:JARVIS_DEVELOPER_NAME = '♪Sukant♪Codes♪'
$env:JARVIS_COPYRIGHT_OWNER = '♪Sukant♪Codes♪'
.\gradlew.bat :app:assembleRelease
```

The certificate fingerprint must be calculated from the same key used to sign the APK. This project currently has no deployed verification backend or agreed verification API protocol. Local signer pinning and APK signatures do not provide server-side authentication, Play Integrity attestation, or rate limiting; the app identity screen reports remote verification as unavailable until that service is implemented and deployed.

## Repository layout

```text
.
├── app/                         Android application module
│   └── src/main/
│       ├── java/com/jarvis/assistant/
│       │   ├── audio/           Audio capture, playback, and voice handling
│       │   ├── automation/      Accessibility, actions, and app integrations
│       │   ├── background/      Conversation foreground service
│       │   ├── data/            Preferences, persistence, and data models
│       │   ├── network/         Gemini Live transport
│       │   ├── ui/              Activities, fragments, and adapters
│       │   ├── util/            Shared app utilities
│       │   └── vision/          Screen capture and visual task execution
│       ├── assets/              Bundled web UI and offline voice models
│       └── res/                 Android layouts, drawables, values, and config
├── docs/
│   ├── architecture/            Design notes and research
│   └── diagnostics/             User-provided issue recordings and index
├── gradle/                      Gradle wrapper configuration
├── build.gradle.kts             Root Gradle plugin declarations
├── settings.gradle.kts          Project and repository configuration
└── gradle.properties             Gradle runtime settings
```

## Documentation

- [Documentation index](docs/README.md)
- [Voice architecture research](docs/architecture/voice-architecture-research.md)
- [Voice issue recordings](docs/diagnostics/voice-videos/README.md)

## Configuration and privacy

- Keep API keys, SDK paths, signing keys, and local machine settings out of source control.
- `local.properties` is local-only; start from `local.properties.example` when setting up a new machine.
- Screen sharing and accessibility automation require explicit Android user consent and permissions.
- Bundled Vosk model assets are runtime dependencies; do not remove or rename their directories without updating the model-loading code.

## AI API Gateway

The Google branch includes a configurable text API gateway for OpenAI, Anthropic/Claude, and DeepSeek. OpenAI uses the Responses API; Claude uses the Messages API; DeepSeek uses its OpenAI-compatible chat endpoint. Provider credentials are stored with Android encrypted preferences. The gateway can discover models available to the supplied key, suggest a low-latency model, remember measured response latency, and fall back to another configured provider after a request error. General text chat and text/code/Office document analysis can use this gateway; live voice sessions, phone-control tools, and inline PDF/image/audio/video analysis continue through the existing Gemini/Groq paths until provider-specific tool and multimodal adapters are implemented.

## Skill Store

Settings > Permissions & Automation > Skill Store lets you switch skills on or off and install more from a GitHub skill store. Skills are step-by-step playbooks the AI loads on demand with the `use_skill` tool. Eight Jarvis-adapted skills are built in (`app/src/main/assets/skills/`). See `docs/architecture/settings-structure.md` for how it works.

## Orb look and icon shape

Theme & 3D Orb Colors in Settings has per-orb Glow and Size sliders and one slider for the Settings icon shape (Plain, Rectangular, Pillowy, Circular).
