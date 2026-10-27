#!/usr/bin/env python3
import argparse
import json
import os
import re
import sqlite3
import sys
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
DEFAULT_DB_PATH = os.path.join(WORKSPACE_ROOT, ".agents/memory/forge_reference_index.db")

SKIP_DIRS = {
    ".git", ".forge", "build", ".gradle", "node_modules", "__pycache__", ".next",
    "dist", "target", ".idea", ".vscode", "captures", ".cxx", "out", "attachments",
}

INDEXABLE_EXTS = {
    ".kt", ".kts", ".java", ".py", ".js", ".ts", ".tsx", ".jsx",
    ".rs", ".go", ".c", ".cpp", ".h", ".hpp", ".cs", ".swift",
    ".dart", ".rb", ".php", ".sh", ".sql", ".md", ".json", ".yaml",
    ".yml", ".toml", ".xml", ".html", ".css",
}

SYMBOL_PATTERNS = [
    ("class", re.compile(r"^\s*(?:(?:public|private|protected|internal|abstract|final|open|data|sealed|value|inline| static)\s+)*(?:class|interface|object|enum\s+class|struct|trait|record)\s+([A-Za-z_][A-Za-z0-9_]*)")),
    ("function", re.compile(r"^\s*(?:(?:public|private|protected|internal|override|suspend|inline|operator|static|async|pub|extern)\s+)*(?:fun|def|fn|func|function)\s+(?:[A-Za-z0-9_<>,.?*\s]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[(\[<]")),
    ("const", re.compile(r"^\s*(?:(?:public|private|protected|internal|const|static|final)\s+)*(?:val|var|const|let)\s+([A-Za-z_][A-Za-z0-9_]*)\s*[:=]")),
]

def _resolve_db_path(target_dir: str = "") -> str:
    if target_dir:
        real = os.path.realpath(os.path.abspath(target_dir))
        if real.startswith("/workspace/"):
            parts = real.split(os.sep)
            proj_root = "/".join(parts[:3]) if len(parts) >= 3 else real
            return os.path.join(proj_root, ".agents/memory/forge_reference_index.db")
    return DEFAULT_DB_PATH

def get_db(db_path: str = "") -> sqlite3.Connection:
    path = db_path or DEFAULT_DB_PATH
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path)
    conn.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_passages_fts USING fts5(file_path, start_line, end_line, symbol_context, content, tokenize='porter unicode61')")
    conn.execute("CREATE TABLE IF NOT EXISTS symbol_defs (symbol_name TEXT, kind TEXT, file_path TEXT, line_num INTEGER, signature TEXT)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_symbol_name ON symbol_defs(symbol_name)")
    conn.execute("CREATE TABLE IF NOT EXISTS indexed_files (file_path TEXT PRIMARY KEY, mtime REAL, size_bytes INTEGER, total_lines INTEGER)")
    conn.execute("CREATE TABLE IF NOT EXISTS gain_metrics (id INTEGER PRIMARY KEY AUTOINCREMENT, query_type TEXT, query_text TEXT, full_bytes INTEGER, returned_bytes INTEGER, saved_tokens INTEGER, created_at REAL)")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    return conn

def _extract_symbols(lines: list) -> list:
    found = []
    for idx, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith(("//", "#", "/*", "*")):
            continue
        for kind, regex in SYMBOL_PATTERNS:
            m = regex.match(raw_line)
            if m:
                found.append((m.group(1), kind, idx, line[:180]))
                break
    return found

def autoindex(root_dir: str, force: bool = False) -> dict:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    if not (real_root.startswith("/workspace") or real_root.startswith("/tmp")):
        raise ValueError(f"Indexing restricted to /workspace or /tmp (got {real_root})")
    db_path = _resolve_db_path(real_root)
    conn = get_db(db_path)
    t0 = time.time()
    indexed_files, skipped_unchanged = 0, 0
    existing = {row[0]: (row[1], row[2]) for row in conn.execute("SELECT file_path, mtime, size_bytes FROM indexed_files").fetchall()}
    for dirpath, dirnames, filenames in os.walk(real_root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS and not d.startswith(".git") and d != "memory"]
        for fname in filenames:
            _, ext = os.path.splitext(fname)
            if ext.lower() not in INDEXABLE_EXTS:
                continue
            fpath = os.path.realpath(os.path.join(dirpath, fname))
            if not fpath.startswith(real_root):
                continue
            try:
                st = os.stat(fpath)
            except OSError:
                continue
            if st.st_size <= 0 or st.st_size > 1024 * 1024:
                continue
            rel_path = os.path.relpath(fpath, real_root)
            if not force and rel_path in existing:
                old_mtime, old_size = existing[rel_path]
                if abs(st.st_mtime - old_mtime) < 0.001 and st.st_size == old_size:
                    skipped_unchanged += 1
                    continue
            try:
                with open(fpath, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            lines = text.splitlines()
            if not lines:
                continue
            conn.execute("DELETE FROM code_passages_fts WHERE file_path = ?", (rel_path,))
            conn.execute("DELETE FROM symbol_defs WHERE file_path = ?", (rel_path,))
            symbols = _extract_symbols(lines)
            for sym, kind, lnum, sig in symbols:
                conn.execute("INSERT INTO symbol_defs (symbol_name, kind, file_path, line_num, signature) VALUES (?, ?, ?, ?, ?)", (sym, kind, rel_path, lnum, sig))
            chunk_size, overlap = 45, 5
            step = max(1, chunk_size - overlap)
            for start_idx in range(0, len(lines), step):
                chunk_lines = lines[start_idx:start_idx + chunk_size]
                if not chunk_lines:
                    break
                s_line, e_line = start_idx + 1, start_idx + len(chunk_lines)
                sym_ctx = ", ".join([s[0] for s in symbols if s_line <= s[2] <= e_line][:6])
                numbered = "\n".join(f"{s_line + i}: {l}" for i, l in enumerate(chunk_lines))
                conn.execute("INSERT INTO code_passages_fts (file_path, start_line, end_line, symbol_context, content) VALUES (?, ?, ?, ?, ?)", (rel_path, str(s_line), str(e_line), sym_ctx, numbered[:6000]))
                if e_line >= len(lines):
                    break
            conn.execute("INSERT OR REPLACE INTO indexed_files (file_path, mtime, size_bytes, total_lines) VALUES (?, ?, ?, ?)", (rel_path, st.st_mtime, st.st_size, len(lines)))
            indexed_files += 1
    conn.commit()
    elapsed = round(time.time() - t0, 2)
    return {
        "root": real_root,
        "dbPath": db_path,
        "updatedFiles": indexed_files,
        "unchangedFiles": skipped_unchanged,
        "totalFiles": conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0],
        "totalPassages": conn.execute("SELECT COUNT(*) FROM code_passages_fts").fetchone()[0],
        "totalSymbols": conn.execute("SELECT COUNT(*) FROM symbol_defs").fetchone()[0],
        "elapsedSec": elapsed,
    }

def _record_gain(conn: sqlite3.Connection, qtype: str, qtext: str, matched_files: set, returned_text: str) -> int:
    if not matched_files:
        return 0
    placeholders = ",".join("?" for _ in matched_files)
    row = conn.execute(f"SELECT COALESCE(SUM(size_bytes), 0) FROM indexed_files WHERE file_path IN ({placeholders})", tuple(matched_files)).fetchone()
    full_bytes = int(row[0] or 0)
    ret_bytes = len(returned_text.encode("utf-8"))
    saved_tokens = max(0, full_bytes - ret_bytes) // 4
    conn.execute("INSERT INTO gain_metrics (query_type, query_text, full_bytes, returned_bytes, saved_tokens, created_at) VALUES (?, ?, ?, ?, ?, ?)", (qtype, qtext[:200], full_bytes, ret_bytes, saved_tokens, time.time()))
    conn.commit()
    return saved_tokens

def search_code(query: str, root_dir: str = "", limit: int = 5) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    sql = "SELECT file_path, start_line, end_line, symbol_context, content, bm25(code_passages_fts) AS rank FROM code_passages_fts WHERE code_passages_fts MATCH ? ORDER BY rank LIMIT ?"
    try:
        rows = conn.execute(sql, (query, limit)).fetchall()
    except sqlite3.OperationalError:
        safe_q = " OR ".join(f'"{w}"' for w in re.findall(r"[A-Za-z0-9_]+", query) if len(w) > 1) or f'"{query}"'
        rows = conn.execute(sql, (safe_q, limit)).fetchall()
    if not rows:
        return f"No code passages matched query: `{query}`"
    out, matched_files = [f"### Forge Reference Index — BM25 Code Search: `{query}` (Top {len(rows)})"], set()
    for idx, (fpath, s_line, e_line, sym_ctx, content, rank) in enumerate(rows, 1):
        matched_files.add(fpath)
        sym_label = f" | symbols: `{sym_ctx}`" if sym_ctx else ""
        out.append(f"\n#### {idx}. `{fpath}:{s_line}-{e_line}` (BM25: `{rank:.2f}`{sym_label})\n```\n{content}\n```")
    result_text = "\n".join(out)
    saved = _record_gain(conn, "search", query, matched_files, result_text)
    if saved > 0:
        result_text += f"\n\n> **Token Gain**: Saved ~`{saved:,}` tokens vs reading {len(matched_files)} full file(s)."
    return result_text

def lookup_symbol(symbol: str, root_dir: str = "", limit: int = 10) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    rows = conn.execute("SELECT symbol_name, kind, file_path, line_num, signature FROM symbol_defs WHERE symbol_name = ? OR symbol_name LIKE ? ORDER BY (symbol_name = ?) DESC, symbol_name ASC LIMIT ?", (symbol, f"%{symbol}%", symbol, limit)).fetchall()
    if not rows:
        return f"No symbol definition found for `{symbol}`."
    out, matched_files = [f"### Forge Reference Index — Symbol Definitions for `{symbol}` ({len(rows)} found)"], set()
    for sym_name, kind, fpath, lnum, sig in rows:
        matched_files.add(fpath)
        out.append(f"- **`{sym_name}`** ({kind}) at `{fpath}:{lnum}`\n  `{sig}`")
    result_text = "\n".join(out)
    saved = _record_gain(conn, "def", symbol, matched_files, result_text)
    if saved > 0:
        result_text += f"\n\n> **Token Gain**: Saved ~`{saved:,}` tokens vs full-file scans."
    return result_text

def batch_search(queries: list, symbols: list = None, root_dir: str = "", limit_per_query: int = 3) -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    if conn.execute("SELECT COUNT(*) FROM indexed_files").fetchone()[0] == 0:
        autoindex(real_root)
    sections = ["## Forge Reference Index — Batch Search Results"]
    for s in (symbols or [])[:10]:
        sections.append(lookup_symbol(str(s), real_root, limit=5))
    for q in (queries or [])[:10]:
        sections.append(search_code(str(q), real_root, limit=limit_per_query))
    return "\n\n---\n\n".join(sections)

def gain_report(root_dir: str = "") -> str:
    real_root = os.path.realpath(os.path.abspath(root_dir or WORKSPACE_ROOT))
    conn = get_db(_resolve_db_path(real_root))
    tot_files = conn.execute("SELECT COUNT(*), COALESCE(SUM(size_bytes), 0), COALESCE(SUM(total_lines), 0) FROM indexed_files").fetchone()
    tot_passages = conn.execute("SELECT COUNT(*) FROM code_passages_fts").fetchone()[0]
    tot_syms = conn.execute("SELECT COUNT(*) FROM symbol_defs").fetchone()[0]
    g_row = conn.execute("SELECT COUNT(*), COALESCE(SUM(full_bytes), 0), COALESCE(SUM(returned_bytes), 0), COALESCE(SUM(saved_tokens), 0) FROM gain_metrics").fetchone()
    queries_run, full_b, ret_b, saved_tok = int(g_row[0]), int(g_row[1]), int(g_row[2]), int(g_row[3])
    ratio = f"{(full_b / max(1, ret_b)):.1f}x" if ret_b > 0 else "1.0x"
    return (
        f"### Forge Reference Index — Telemetry & Token Gain Report\n"
        f"- **Indexed Root**: `{real_root}`\n"
        f"- **Corpus Size**: `{tot_files[0]}` files (`{tot_files[2]:,}` lines, `{tot_files[1] // 1024:,} KB`)\n"
        f"- **Indexed Chunks & Symbols**: `{tot_passages:,}` BM25 passages | `{tot_syms:,}` symbol definitions\n"
        f"- **Queries Executed**: `{queries_run}` queries\n"
        f"- **Context Reduction Ratio**: **`{ratio}` fewer bytes** (`{ret_b // 1024:,} KB` returned vs `{full_b // 1024:,} KB` full files)\n"
        f"- **Estimated Tokens Saved**: **`~{saved_tok:,}` tokens**"
    )

def main():
    parser = argparse.ArgumentParser(description="Forge Reference Index — Local Code, Symbol & Reference Search Engine")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_idx = sub.add_parser("autoindex")
    p_idx.add_argument("path", nargs="?", default=WORKSPACE_ROOT)
    p_idx.add_argument("--force", action="store_true")
    p_srch = sub.add_parser("search")
    p_srch.add_argument("query")
    p_srch.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_srch.add_argument("-n", "--limit", type=int, default=5)
    p_def = sub.add_parser("def")
    p_def.add_argument("symbol")
    p_def.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_def.add_argument("-n", "--limit", type=int, default=10)
    p_batch = sub.add_parser("batch")
    p_batch.add_argument("-q", "--query", action="append", default=[])
    p_batch.add_argument("-s", "--symbol", action="append", default=[])
    p_batch.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    p_gain = sub.add_parser("gain")
    p_gain.add_argument("-p", "--path", default=WORKSPACE_ROOT)
    args = parser.parse_args()
    if args.cmd == "autoindex":
        print(json.dumps(autoindex(args.path, force=args.force), indent=2))
    elif args.cmd == "search":
        print(search_code(args.query, args.path, args.limit))
    elif args.cmd == "def":
        print(lookup_symbol(args.symbol, args.path, args.limit))
    elif args.cmd == "batch":
        print(batch_search(args.query, args.symbol, args.path))
    elif args.cmd == "gain":
        print(gain_report(args.path))

if __name__ == "__main__":
    main()
