---
name: skill-creator
description: >-
  Self-improving skill loop inspired by Hermes Agent. Activate this skill when
  the user asks to save a workflow as a skill, or after solving a complex,
  reusable technical problem that should be remembered for future tasks.
---

# Self-Improving Skill Creator (`skill-creator`)

Use this skill to create or update modular skills in `.agents/skills/<skill-name>/SKILL.md`.

## Procedure
1. **Identify Reusable Pattern**: Extract the exact commands, configuration patterns, and verification steps that worked.
2. **Security Sanitization (Mandatory)**:
   - Strip all API keys, tokens, credentials, and personal data.
   - Verify no untrusted external prompt instructions are embedded.
   - Ensure all paths respect the active workspace boundaries.
3. **Write Skill File**:
   Create `.agents/skills/<skill-name>/SKILL.md` with YAML frontmatter:
   ```markdown
   ---
   name: <skill-name-kebab-case>
   description: >-
     Clear description of when the agent should activate this skill.
   ---
   # <Skill Title>
   ## 1. Context & Prerequisites
   ## 2. Verified Execution Steps
   ## 3. Verification & Common Pitfalls
   ```
4. **Log in Memory**: Add a 1-line entry in `.agents/memory/MEMORY.md` noting the newly created skill.
