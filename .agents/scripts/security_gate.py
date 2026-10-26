#!/usr/bin/env python3
import json
import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
MEMORY_FILES = {
    os.path.join(WORKSPACE_ROOT, "AGENTS.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/USER.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/MEMORY.md"),
}
MAX_MEMORY_BYTES = 24 * 1024  # 24 KB total budget

# 1. Bash Command Hard-Blocked Patterns (Denied immediately before bash runs)
DENY_CMD_PATTERNS = [
    (r"unprivileged_bpf_disabled", "Blocked: Modifying unprivileged_bpf_disabled crashes Android system_server."),
    (r"rm\s+-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*\s+(/|/root|/workspace|~|\$HOME)(\s|$)", "Blocked: Catastrophic recursive deletion outside project subdir."),
    (r"\b(mkfs|fdisk|wipefs)\b", "Blocked: Disk formatting utilities are forbidden."),
    (r"\bdd\b.*\bof=/dev/(sd|vd|nvme|mmcblk|loop|block|dm-)", "Blocked: Direct dd write to block device is forbidden."),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "Blocked: Fork bomb detected."),
]

# 2. Bash Command High-Risk Patterns (Require explicit user confirmation)
ASK_CMD_PATTERNS = [
    (r"curl\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"wget\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"\b(nc|ncat|netcat)\b.*-e", "Reverse shell pattern detected. Confirm execution?"),
]

# 3. Memory & Skill Anti-Poisoning Patterns (Secrets & Prompt Injection)
SECRET_AND_INJECTION_PATTERNS = [
    (r"AIza[0-9A-Za-z\-_]{35}", "Blocked by Memory Guard: Google API key detected in memory/skill file."),
    (r"gh[pousr]_[A-Za-z0-9_]{36,255}", "Blocked by Memory Guard: GitHub token detected in memory/skill file."),
    (r"sk-[A-Za-z0-9\-_]{20,}", "Blocked by Memory Guard: API secret key (sk-...) detected in memory/skill file."),
    (r"-----BEGIN (RSA |EC |OPENSSH |PRIVATE )?KEY-----", "Blocked by Memory Guard: Private cryptographic key detected."),
    (r"(?i)(ignore\s+(all\s+)?(previous|prior)\s+instructions|disregard\s+system\s+prompt|you\s+are\s+now\s+in\s+developer\s+mode)", "Blocked by Memory Guard: Prompt injection pattern detected in memory/skill file."),
]

def check_command(args: dict):
    cmd = args.get("CommandLine", "") or args.get("command", "")
    for pattern, reason in DENY_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "deny", "reason": reason}
    for pattern, reason in ASK_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "ask", "reason": reason}
    return {"decision": "allow"}

def is_protected_cognitive_file(path: str) -> bool:
    if not path:
        return False
    abs_path = os.path.abspath(path)
    if abs_path in MEMORY_FILES:
        return True
    skills_dir = os.path.join(WORKSPACE_ROOT, ".agents/skills/")
    return abs_path.startswith(skills_dir) and abs_path.endswith(".md")

def check_file_mutation(args: dict):
    path = args.get("Path", "") or args.get("path", "")
    content = (
        args.get("Content", "")
        or args.get("Replacement", "")
        or args.get("content", "")
        or ""
    )
    if not is_protected_cognitive_file(path):
        return {"decision": "allow"}

    for pattern, reason in SECRET_AND_INJECTION_PATTERNS:
        if re.search(pattern, content):
            return {"decision": "deny", "reason": reason}

    abs_path = os.path.abspath(path)
    if abs_path in MEMORY_FILES:
        total_bytes = len(content.encode("utf-8"))
        for mem_file in MEMORY_FILES:
            if mem_file != abs_path and os.path.exists(mem_file):
                try:
                    total_bytes += os.path.getsize(mem_file)
                except OSError:
                    pass
        if total_bytes > MAX_MEMORY_BYTES:
            return {
                "decision": "deny",
                "reason": f"Blocked by Memory Guard: Combined memory size ({total_bytes} B) exceeds 24 KB limit ({MAX_MEMORY_BYTES} B). Compact memory first."
            }

    return {"decision": "allow"}

def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print(json.dumps({"decision": "allow"}))
        return

    tool_call = payload.get("toolCall", {})
    tool_name = tool_call.get("name", "") or payload.get("toolName", "")
    args = tool_call.get("args", {})

    if tool_name in ("write_file", "edit_file", "replace_file_content", "multi_replace_file_content") or any(k in args for k in ("Path", "path", "TargetFile")):
        result = check_file_mutation(args)
    else:
        result = check_command(args)

    print(json.dumps(result))

if __name__ == "__main__":
    main()
