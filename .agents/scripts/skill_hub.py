#!/usr/bin/env python3
import argparse
import os
import re
import sys
import tarfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
WORKSPACE_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "../.."))
sys.path.insert(0, SCRIPT_DIR)
from security_gate import SECRET_AND_INJECTION_PATTERNS

SKILLS_DIR = os.path.join(WORKSPACE_ROOT, ".agents/skills")

def audit_skills() -> bool:
    if not os.path.exists(SKILLS_DIR):
        print("No skills directory found.")
        return True
    ok, count = True, 0
    for root, _, files in os.walk(SKILLS_DIR):
        for f in files:
            if f.endswith(".md"):
                count += 1
                p = os.path.join(root, f)
                with open(p, "r", encoding="utf-8", errors="replace") as fh:
                    text = fh.read()
                for pattern, reason in SECRET_AND_INJECTION_PATTERNS:
                    if re.search(pattern, text):
                        print(f"[FAIL] {p}: {reason}")
                        ok = False
    if ok:
        print(f"[PASS] Audited {count} skill file(s): 0 secrets or injection patterns found.")
    return ok

def export_bundle(out_path: str) -> None:
    if not audit_skills():
        sys.exit(1)
    with tarfile.open(out_path, "w:gz") as tar:
        tar.add(SKILLS_DIR, arcname="skills")
    print(f"Exported skills bundle to: {out_path} ({os.path.getsize(out_path)} bytes)")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Skill Auditor & Portable Bundler")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("audit")
    p_exp = sub.add_parser("export")
    p_exp.add_argument("output")
    args = parser.parse_args()
    if args.cmd == "audit":
        sys.exit(0 if audit_skills() else 1)
    elif args.cmd == "export":
        export_bundle(args.output)

if __name__ == "__main__":
    main()
