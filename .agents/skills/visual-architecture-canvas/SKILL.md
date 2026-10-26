---
name: visual-architecture-canvas
description: >-
  Sokudo-style Visual Canvas & Architecture Blueprint Engine. Activate this
  skill before coding a new application or major feature to map out UI
  wireframes, system architecture, database ER diagrams, and state flows using
  Mermaid diagrams and visual assets.
---

# Visual Canvas & Architecture Blueprint Engine (`visual-architecture-canvas`)

## 1. Context & Prerequisites
Before writing complex multi-file code across Web, Server, Windows, Linux, iOS, or Android, `universal-lead-architect` and `ui-frontend-designer` use this skill to produce a clear visual blueprint.

## 2. Verified Execution Steps
1. **System & Data Flow Blueprint (`flowchart TD` / `sequenceDiagram`)**:
   - Map client-to-server, UI-to-ViewModel/Store, or IPC boundaries.
   - Always quote node labels containing parentheses or brackets (e.g., `id["Client App (Web/Mobile)"]`).
2. **Database & State Schema (`erDiagram` / `stateDiagram-v2`)**:
   - Define SQL/NoSQL tables, relationships, indexes, and UI state transitions (`Idle -> Loading -> Success / Error`).
3. **UI Component Hierarchy & Wireframe Spec**:
   - Define screen navigation trees, responsive breakpoints (Mobile/Tablet/Desktop), design tokens (color palette, typography, spacing), and component props/state contracts.
   - When custom icons, illustrations, or mockups are needed, invoke the `image-generator` subagent to generate assets directly into the project's asset directory.
4. **Lock File Ownership in Active Task Board (`AGENTS.md`)**:
   - Translate the blueprint into concrete tickets in the `AGENTS.md` Active Task Board with explicit file ownership per agent persona.

## 3. Verification & Common Pitfalls
- Only use supported Mermaid diagram types (`flowchart`, `sequenceDiagram`, `stateDiagram-v2`, `classDiagram`, `erDiagram`, `xychart-beta`).
- Never embed raw HTML tags inside Mermaid node labels.
