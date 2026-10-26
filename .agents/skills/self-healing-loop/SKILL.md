---
name: self-healing-loop
description: >-
  Automated Self-Healing Build, Test, and Runtime Recovery Loop with Git
  Checkpoint protection. Activate this skill whenever a build, compiler check,
  unit test, or runtime execution fails across any platform (Web, Server,
  Windows, Linux, iOS, or Android).
---

# Automated Self-Healing & Rollback Protocol (`self-healing-loop`)

## 1. Context & Prerequisites
Provides deterministic, non-hallucinated error recovery when compiling or testing code across any stack, preventing infinite retry loops or broken workspace states.

## 2. Verified Execution Steps
1. **Git Safety Checkpoint (Pre-Edit)**:
   - Before major refactors or risky multi-file edits, ensure the working tree state is tracked (`git status` / `git diff`) so changes can be surgically reverted if needed.
2. **Root-Cause Triage (Read Before Fix)**:
   - Capture the exact compiler/runtime error output (file path, line number, symbol, exit code).
   - Read the exact source file lines around the failure before proposing any edit. Never guess or blindly re-run a failed command unchanged.
3. **Bounded Surgical Repair (Max 3 Iterations)**:
   - **Attempt 1**: Fix the direct syntax, type, import, or configuration error.
   - **Attempt 2**: If dependency or toolchain mismatch occurs (e.g., missing cached dependency in Android offline Gradle, missing npm/cargo/pip package, or cross-compiler flag), apply the documented platform fallback once.
   - **Attempt 3**: Isolate the failing module, run targeted unit/syntax verification, and invoke `universal-fullstack-auditor` to check for deeper architectural conflicts.
4. **Halt & Report on External Blockers**:
   - If an error is caused by a missing external hardware resource, network restriction, or unresolvable requirement after 3 bounded attempts, roll back broken partial edits and report the exact factual error to the user.

## 3. Verification & Common Pitfalls
- **Never Retry Timeouts Unchanged**: If a command times out or hits OOM, diagnose the bottleneck instead of re-running the identical command.
- **Verify Exit Code 0**: Every self-healing cycle must conclude with an actual verification run returning exit code `0`.
