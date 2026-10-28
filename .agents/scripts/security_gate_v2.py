#!/usr/bin/env python3
# PreToolUse gate shared by Claude Code and Antigravity (agy).
#
# Mode (first match wins): env AGENT_GATE_MODE, then .agents/gate-mode, then "release".
#   release -> full checks below.
#   debug   -> no checks (only the system_server crash guard stays).
# Anything unreadable in release mode is denied (fail closed).
import json
import os
import re
import shlex
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.realpath(os.path.join(SCRIPT_DIR, "../.."))
MODE_FILE = os.path.join(WORKSPACE_ROOT, ".agents/gate-mode")
MEMORY_FILES = {
    os.path.join(WORKSPACE_ROOT, "AGENTS.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/USER.md"),
    os.path.join(WORKSPACE_ROOT, ".agents/memory/MEMORY.md"),
}
MAX_MEMORY_BYTES = 24 * 1024  # 24 KB total budget

# Files that define the gate itself: an agent must not be able to switch the mode or unregister the hook.
CONTROL_FILES = {os.path.join(WORKSPACE_ROOT, p) for p in (
    ".agents/gate-mode", ".agents/hooks.json", ".agents/scripts/security_gate_v2.py",
    ".claude/settings.json", ".claude/settings.local.json",
)}
CONTROL_CMD_RE = re.compile(r"gate-mode|AGENT_GATE_MODE|security_gate_v2|\.agents/hooks\.json|\.claude/settings")
READONLY_CMDS = {"cat", "ls", "grep", "egrep", "head", "tail", "stat", "sha256sum", "wc", "diff", "file"}

# Tool names / argument keys differ per harness (Antigravity vs. Claude Code); accept both.
COMMAND_TOOLS = {"run_command", "Bash"}
FILE_TOOLS = {"write_file", "edit_file", "replace_file_content", "multi_replace_file_content", "Write", "Edit", "MultiEdit", "NotebookEdit"}
PATH_KEYS = ("Path", "path", "TargetFile", "file_path", "notebook_path")

# Denied in every mode: this sysctl change reboots Android's system_server.
ALWAYS_DENY_CMD = (r"unprivileged_bpf_disabled", "Blocked: Modifying unprivileged_bpf_disabled crashes Android system_server.")

DENY_CMD_PATTERNS = [
    (r"\b(rm|unlink|shred|truncate)\b.*universal-agent-telemetry-protocol\.md", "Blocked by Immutable Protocol Guard: universal-agent-telemetry-protocol.md may only be upgraded, never deleted."),
    (r"\b(mkfs|fdisk|wipefs)\b", "Blocked: Disk formatting utilities are forbidden."),
    (r"\bdd\b.*\bof=/dev/(sd|vd|nvme|mmcblk|loop|block|dm-)", "Blocked: Direct dd write to block device is forbidden."),
    (r":\(\)\s*\{\s*:\|:&\s*\};:", "Blocked: Fork bomb detected."),
    (r"(\w+)\s*\(\)\s*\{[^}]*\1\s*\|\s*\1\s*&[^}]*\}\s*;?\s*\1", "Blocked: Fork bomb detected."),
]

# Recursive `rm` on these targets is always blocked (checked by token, so flag order/glob suffix do not matter).
PROTECTED_RM_TARGETS = {"/", "/root", "/workspace", "/home", "/etc", "/usr", "/var", "/bin", "/lib", "/sdcard", "~", "$HOME", "${HOME}", WORKSPACE_ROOT}

ASK_CMD_PATTERNS = [
    (r"curl\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"wget\s+.*\|\s*(ba)?sh", "Remote script pipe-to-shell detected. Confirm execution?"),
    (r"(bash|sh|zsh)\s+<\(\s*(curl|wget)", "Remote script process-substitution-to-shell detected. Confirm execution?"),
    (r"\b(nc|ncat|netcat)\b.*-e", "Reverse shell pattern detected. Confirm execution?"),
]


def get_mode() -> str:
    value = os.environ.get("AGENT_GATE_MODE", "").strip().lower()
    if value not in ("debug", "release"):
        try:
            with open(MODE_FILE, "r") as fh:
                value = fh.read().strip().lower()
        except OSError:
            value = ""
    return value if value in ("debug", "release") else "release"  # unknown/missing -> safest


def _segments(cmd: str):
    return re.split(r"[;&|\n`]+|\$\(", cmd)


def _tokens(segment: str) -> list:
    try:
        tokens = shlex.split(segment)
    except ValueError:
        tokens = segment.split()
    while tokens and (tokens[0] in ("sudo", "command", "exec", "nohup", "env") or "=" in tokens[0]):
        tokens.pop(0)
    return tokens


def _is_dangerous_rm(cmd: str):
    for segment in _segments(cmd):
        tokens = _tokens(segment)
        if not tokens or os.path.basename(tokens[0]) != "rm":
            continue
        flags = [t for t in tokens[1:] if t.startswith("-")]
        recursive = any(t == "--recursive" or (not t.startswith("--") and ("r" in t or "R" in t)) for t in flags)
        if not recursive:
            continue
        for target in (t for t in tokens[1:] if not t.startswith("-")):
            norm = target.rstrip("*").rstrip("/")
            if norm.endswith("/."):
                norm = norm[:-2]
            norm = norm or "/"
            if norm in PROTECTED_RM_TARGETS or os.path.realpath(os.path.expanduser(norm)) in PROTECTED_RM_TARGETS:
                return target
    return None


def _touches_control_files(cmd: str) -> bool:
    # Read-only inspection is fine; anything else that mentions a control file is treated as tampering.
    for segment in _segments(cmd):
        if not CONTROL_CMD_RE.search(segment):
            continue
        tokens = _tokens(segment)
        if ">" in segment or not tokens or os.path.basename(tokens[0]) not in READONLY_CMDS:
            return True
    return False


def check_command(args: dict, mode: str):
    cmd = args.get("CommandLine", "") or args.get("command", "")
    if re.search(ALWAYS_DENY_CMD[0], cmd):
        return {"decision": "deny", "reason": ALWAYS_DENY_CMD[1]}
    if mode == "debug":
        return {"decision": "allow"}
    if _touches_control_files(cmd):
        return {"decision": "deny", "reason": "Blocked: gate control files (gate-mode, hooks, gate script, .claude/settings*) can only be changed by the user."}
    for pattern, reason in DENY_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "deny", "reason": reason}
    bad_target = _is_dangerous_rm(cmd)
    if bad_target:
        return {"decision": "deny", "reason": f"Blocked: Catastrophic recursive deletion of {bad_target}."}
    for pattern, reason in ASK_CMD_PATTERNS:
        if re.search(pattern, cmd):
            return {"decision": "ask", "reason": reason}
    return {"decision": "allow"}


def _get_path(args: dict) -> str:
    for key in PATH_KEYS:
        if args.get(key):
            return args[key]
    return ""


def _allowed_write_roots() -> list:
    # Workspace, temp, agent memory/brain dirs. ~/.claude is limited to projects/ so settings.json stays out of reach.
    home = os.path.expanduser("~")
    roots = [WORKSPACE_ROOT, "/tmp", os.path.join(home, ".claude/projects"), os.path.join(home, ".gemini")]
    for var in ("AGY_CONFIG_ROOT", "ANTIGRAVITY_CONFIG_ROOT", "TMPDIR"):
        value = os.environ.get(var)
        if value:
            roots.append(value)
    return [os.path.realpath(r) for r in roots]


def _within(path: str, root: str) -> bool:
    return path == root or path.startswith(root.rstrip("/") + "/")


def is_outside_workspace(path: str) -> bool:
    if not path or not os.path.isabs(path):
        return False  # relative paths resolve against the agent's cwd (the project)
    real_path = os.path.realpath(path)  # resolves symlinks so a link inside the workspace cannot escape it
    return not any(_within(real_path, root) for root in _allowed_write_roots())


def is_protected_cognitive_file(path: str) -> bool:
    real = os.path.realpath(path)
    # AGENTS.md section 1.C: no secrets in ANY .md file, not only memory/skill files.
    return real in MEMORY_FILES or real.lower().endswith(".md")


def _read_existing(path: str) -> str:
    try:
        with open(path, "r", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def _added_and_resulting(args: dict, existing: str):
    # Returns (text being added, full file text after the write or None when it cannot be derived).
    full = args.get("Content") or args.get("content")
    if full is not None:
        return full, full
    edits = args.get("edits")
    if isinstance(edits, list):
        text = existing
        for edit in edits:
            text = text.replace(edit.get("old_string", ""), edit.get("new_string", ""), 1)
        return "\n".join(e.get("new_string", "") for e in edits), text
    if "new_string" in args:
        old, new = args.get("old_string", ""), args["new_string"]
        return new, existing.replace(old, new, -1 if args.get("replace_all") else 1)
    return args.get("Replacement", "") or "", None


def check_file_mutation(args: dict):
    path = _get_path(args)
    if not path:
        return {"decision": "allow"}
    real = os.path.realpath(path)
    if real in CONTROL_FILES:
        return {"decision": "deny", "reason": "Blocked: gate control files (gate-mode, hooks, gate script, .claude/settings*) can only be changed by the user."}
    if is_outside_workspace(path):
        return {"decision": "deny", "reason": f"Blocked: writes are confined to {WORKSPACE_ROOT} (plus /tmp and agent memory dirs); got {path}"}
    if not is_protected_cognitive_file(path):
        return {"decision": "allow"}

    from security_gate import SECRET_AND_INJECTION_PATTERNS  # reuse the shared secret/injection patterns
    existing = _read_existing(real) if os.path.exists(real) else ""
    added, resulting = _added_and_resulting(args, existing)

    for pattern, reason in SECRET_AND_INJECTION_PATTERNS:
        if re.search(pattern, added):
            return {"decision": "deny", "reason": reason}

    anchors = ("CORE-EXEC-001", "CORE-EXEC-002", "CORE-EXEC-003", "CORE-EXEC-004", "CORE-EXEC-005")
    if resulting is not None:
        missing = [a for a in anchors if a in existing and a not in resulting]
        if missing:
            return {"decision": "deny", "reason": f"Blocked by Immutable Protocol Guard: {os.path.basename(real)} contains protected execution notes ({', '.join(missing)}) which may only be upgraded, never deleted."}

    if real in MEMORY_FILES:
        total = len((resulting if resulting is not None else added).encode("utf-8"))
        for mem_file in MEMORY_FILES - {real}:
            if os.path.exists(mem_file):
                total += os.path.getsize(mem_file)
        if total > MAX_MEMORY_BYTES:
            return {"decision": "deny", "reason": f"Blocked by Memory Guard: Combined memory size ({total} B) exceeds 24 KB limit ({MAX_MEMORY_BYTES} B). Compact memory first."}
    return {"decision": "allow"}


def emit(result: dict) -> None:
    # Legacy shape ("decision") plus the Claude Code PreToolUse shape so either harness honours the verdict.
    result["hookSpecificOutput"] = {
        "hookEventName": "PreToolUse",
        "permissionDecision": result["decision"],
        "permissionDecisionReason": result.get("reason", ""),
    }
    print(json.dumps(result))


def main():
    mode = get_mode()
    try:
        sys.path.insert(0, SCRIPT_DIR)
        payload = json.load(sys.stdin)
        tool_call = payload.get("toolCall") or {}
        tool_name = tool_call.get("name") or payload.get("toolName") or payload.get("tool_name") or ""
        args = tool_call.get("args") or payload.get("tool_input") or {}

        if tool_name in FILE_TOOLS or _get_path(args):
            result = {"decision": "allow"} if mode == "debug" else check_file_mutation(args)
        elif tool_name in COMMAND_TOOLS or "CommandLine" in args or "command" in args:
            result = check_command(args, mode)
        else:
            result = {"decision": "allow"}
    except Exception as exc:
        # Release fails closed; debug never blocks on a gate error.
        result = {"decision": "allow"} if mode == "debug" else {"decision": "deny", "reason": f"Blocked: security gate could not evaluate the request ({type(exc).__name__})."}
    emit(result)


if __name__ == "__main__":
    main()
