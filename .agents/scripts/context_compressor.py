#!/usr/bin/env python3
import argparse
import glob
import json
import os
import sys
from datetime import datetime, timezone

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
import session_search

BRAIN_GLOB = "/root/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl"

def get_latest_transcript():
    files = glob.glob(BRAIN_GLOB)
    if not files:
        return "", ""
    latest = max(files, key=os.path.getmtime)
    parts = latest.split(os.sep)
    try:
        sess_id = parts[parts.index("brain") + 1]
    except (ValueError, IndexError):
        sess_id = "unknown"
    return sess_id, latest

def summarize_session(sess_id: str, path: str, max_items: int = 8) -> str:
    user_goals, tools_used, key_outcomes = [], [], []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                step_type = obj.get("type", "")
                content = obj.get("content", "") or ""
                if "<nextcraft_workspace>" in content and "</nextcraft_workspace>" in content:
                    content = content.split("</nextcraft_workspace>", 1)[-1].strip()
                if step_type == "USER_INPUT" and content:
                    first_line = content.splitlines()[0].strip()
                    if first_line and first_line not in user_goals:
                        user_goals.append(first_line[:160])
                for tc in obj.get("tool_calls", []) or []:
                    tname = tc.get("name", "")
                    if tname and tname not in tools_used:
                        tools_used.append(tname)
                if step_type == "PLANNER_RESPONSE" and content:
                    first_line = content.splitlines()[0].strip()
                    if len(first_line) > 20:
                        key_outcomes.append(first_line[:180])
    except OSError as e:
        return f"Error reading transcript: {e}"
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    return f"Session {sess_id[:8]} ({now_str}) | Goals: {' | '.join(user_goals[-max_items:]) or 'N/A'} | Tools: {', '.join(tools_used) or 'none'} | Recent Outcomes: {' | '.join(key_outcomes[-3:]) or 'N/A'}"

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Pre-Compaction Context Compressor")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    conn = session_search.get_db()
    added = session_search.sync_transcripts(conn)
    sess_id, path = get_latest_transcript()
    if not path:
        print("No active transcript found.")
        return
    digest = summarize_session(sess_id, path)
    print(f"=== Session Digest ({sess_id[:8]}) ===\n{digest}")
    if not args.dry_run:
        session_search.log_note(conn, f"Session-Digest-{sess_id[:8]}", digest)
        print(f"Synced {added} raw steps + 1 compressed session digest into FTS5 history.db.")

if __name__ == "__main__":
    main()
