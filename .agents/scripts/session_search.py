#!/usr/bin/env python3
import argparse
import glob
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
DB_PATH = os.path.join(WORKSPACE_ROOT, ".agents/memory/history.db")
BRAIN_GLOB = "/root/.gemini/antigravity-cli/brain/*/.system_generated/logs/transcript.jsonl"

def get_db() -> sqlite3.Connection:
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(session_id, step_id, source, created_at, content, tokenize='porter unicode61')")
    conn.execute("CREATE TABLE IF NOT EXISTS indexed_steps (unique_key TEXT PRIMARY KEY, indexed_at TEXT)")
    return conn

def sync_transcripts(conn: sqlite3.Connection) -> int:
    added = 0
    now_str = datetime.now(timezone.utc).isoformat()
    for path in glob.glob(BRAIN_GLOB):
        parts = path.split(os.sep)
        try:
            session_id = parts[parts.index("brain") + 1]
        except (ValueError, IndexError):
            session_id = "unknown"
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if not line.strip():
                        continue
                    try:
                        obj = json.loads(line)
                    except Exception:
                        continue
                    step_idx = str(obj.get("step_index", ""))
                    source = obj.get("source", "UNKNOWN")
                    step_type = obj.get("type", "")
                    content = obj.get("content", "")
                    created_at = obj.get("created_at", "")
                    if not content or step_type not in ("USER_INPUT", "PLANNER_RESPONSE"):
                        continue
                    content_clean = content
                    if "<nextcraft_workspace>" in content_clean and "</nextcraft_workspace>" in content_clean:
                        content_clean = content_clean.split("</nextcraft_workspace>", 1)[-1].strip()
                    if len(content_clean) < 3:
                        continue
                    unique_key = f"{session_id}:{step_idx}:{step_type}"
                    if conn.execute("SELECT 1 FROM indexed_steps WHERE unique_key = ?", (unique_key,)).fetchone():
                        continue
                    conn.execute("INSERT INTO memory_fts (session_id, step_id, source, created_at, content) VALUES (?, ?, ?, ?, ?)", (session_id, step_idx, source, created_at, content_clean[:8000]))
                    conn.execute("INSERT INTO indexed_steps (unique_key, indexed_at) VALUES (?, ?)", (unique_key, now_str))
                    added += 1
        except OSError:
            continue
    conn.commit()
    return added

def log_note(conn: sqlite3.Connection, title: str, text: str) -> None:
    now_str = datetime.now(timezone.utc).isoformat()
    unique_key = f"note:{now_str}:{title}"
    conn.execute("INSERT INTO memory_fts (session_id, step_id, source, created_at, content) VALUES (?, ?, ?, ?, ?)", ("episodic-note", "note", "AGENT_NOTE", now_str, f"[{title}] {text}"))
    conn.execute("INSERT INTO indexed_steps (unique_key, indexed_at) VALUES (?, ?)", (unique_key, now_str))
    conn.commit()
    print(f"Logged episodic note: {title}")

def search(conn: sqlite3.Connection, query: str, limit: int = 5) -> None:
    sync_transcripts(conn)
    sql = "SELECT session_id, step_id, source, created_at, snippet(memory_fts, 4, '«', '»', ' ... ', 24), bm25(memory_fts) AS rank FROM memory_fts WHERE memory_fts MATCH ? ORDER BY rank LIMIT ?"
    try:
        rows = conn.execute(sql, (query, limit)).fetchall()
    except sqlite3.OperationalError:
        rows = conn.execute(sql, ('"' + query.replace('"', ' ') + '"', limit)).fetchall()
    if not rows:
        print(f"No matches found for query: {query}")
        return
    print(f"=== FTS5 Search Results for '{query}' (Top {len(rows)}) ===")
    for idx, (sess_id, step_id, src, ts, excerpt, rank) in enumerate(rows, 1):
        print(f"{idx}. [{src} | sess:{sess_id[:8]}#{step_id} | {ts[:19]}] (score: {rank:.2f})\n   {' '.join(excerpt.splitlines())}")

def stats(conn: sqlite3.Connection) -> None:
    added = sync_transcripts(conn)
    total = conn.execute("SELECT COUNT(*) FROM memory_fts").fetchone()[0]
    sessions = conn.execute("SELECT COUNT(DISTINCT session_id) FROM memory_fts").fetchone()[0]
    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    print(f"FTS5 DB: {DB_PATH} ({db_size} bytes) | Indexed Entries: {total} (+{added} new) | Sessions: {sessions}")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite SQLite FTS5 Session & Episodic Search")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("sync")
    sub.add_parser("stats")
    p_search = sub.add_parser("search")
    p_search.add_argument("query")
    p_search.add_argument("-n", "--limit", type=int, default=5)
    p_log = sub.add_parser("log")
    p_log.add_argument("title")
    p_log.add_argument("text")
    args = parser.parse_args()
    conn = get_db()
    if args.cmd == "sync":
        print(f"Synced {sync_transcripts(conn)} new entries into FTS5.")
    elif args.cmd == "stats":
        stats(conn)
    elif args.cmd == "search":
        search(conn, args.query, args.limit)
    elif args.cmd == "log":
        log_note(conn, args.title, args.text)

if __name__ == "__main__":
    main()
