# Long-Term Project & Architecture Memory (`/workspace/moodle`)

## 1. System & Toolchain Snapshot
- **Target Platform**: Android (`arm64-v8a`, `minSdk 26`, `compileSdk 37`).
- **Build System**: AGP `9.4.1`, Kotlin `2.4.21` (built-in via AGP 9 DSL), JDK `21`.
- **Guest Runtime**: Ubuntu 24.04.5 LTS ARM64 Root Chroot & Non-Root PRoot fallback in NextCraft Forge.

## 2. Key Architectural Decisions
- Initialized Hermes-Lite v2 zero-overhead cognitive architecture (`AGENTS.md`, `.agents/hooks.json`, `.agents/scripts/{security_gate,session_search,context_compressor,skill_hub,hermes_init,web_and_ui_tool}.py`, `.agents/memory/{USER,MEMORY}.md`, and `skill-creator`).

## 3. Custom Skills Index
- `skill-creator`: `.agents/skills/skill-creator/SKILL.md` — Closed-loop generator for creating and sanitizing reusable skills.
