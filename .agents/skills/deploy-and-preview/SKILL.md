---
name: deploy-and-preview
description: >-
  Universal Live Preview, Background Dev Server, Cross-Compilation Packaging,
  and CI/CD Deployment Pipeline. Activate this skill when launching live preview
  servers, testing APIs/UIs, packaging native binaries (.apk, .exe, Linux,
  iOS), or configuring production deployments (Docker, Cloudflare, Vercel,
  GitHub Actions).
---

# Live Preview, Packaging & Deployment Pipeline (`deploy-and-preview`)

## 1. Context & Prerequisites
Covers end-to-end delivery from local live preview in NextCraft Forge to production packaging and deployment across all target platforms.

## 2. Verified Execution Steps

### A. Live Local Preview & Daemon Management (Web / Backend / API)
1. Start long-running dev servers (Vite, Next.js, FastAPI, Go, Node, etc.) using `run_command` with `IsDaemon: true` (or `keep-running` in NextCraft).
2. Verify server readiness by probing the local HTTP/WebSocket port (`curl -I http://localhost:<port>`) and inspecting task logs via `manage_task`.

### B. Native & Cross-Platform Binary Packaging
1. **Android (`.apk`)**:
   - Use NextCraft Forge offline-cached Gradle `assembleDebug` (`AGP 9.4.1`, `Kotlin 2.4.21`, `compileSdk 37`). Verify output in `app/build/outputs/apk/debug/`.
   - Optionally install and inspect on the host Android OS via `android-shell` and `android-screencap`.
2. **Linux (`aarch64` / `x86_64`)**:
   - Compile via `cargo build --release`, `go build`, `gcc/clang/cmake`, or Tauri/Electron bundler; test `x86_64` binaries with `run-x86` when needed.
3. **Windows (`.exe` / Installer)**:
   - Cross-compile from Linux using `mingw-w64` (`x86_64-w64-mingw32-gcc/g++`), `cargo build --target x86_64-pc-windows-gnu`, `GOOS=windows GOARCH=amd64 go build`, or `.NET` publish.
4. **iOS / macOS (`Swift` / `Flutter` / `React Native`)**:
   - Validate `Package.swift`, Xcode project structure, Swift syntax/type checks, and generate GitHub Actions macOS runners (`.github/workflows/ios-build.yml`) for signed `.ipa` archives when local macOS Xcode signing is required.

### C. Cloud & Production CI/CD
- Generate sanitized, secret-free deployment manifests: `Dockerfile`, `docker-compose.yml`, `wrangler.toml` (Cloudflare), `vercel.json`, or `.github/workflows/ci-cd.yml`.

## 3. Verification & Common Pitfalls
- Never hardcode production credentials or deployment tokens inside CI/CD YAML or Dockerfiles; always use environment variable placeholders (`${SECRET_NAME}`).
