#!/usr/bin/env python3
import argparse
import os
import shutil
import stat
import sys

SOURCE_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
SOURCE_WORKSPACE = os.path.abspath(os.path.join(SOURCE_SCRIPTS_DIR, "../.."))

def make_executable(path: str) -> None:
    st = os.stat(path)
    os.chmod(path, st.st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

def bootstrap_workspace(target_dir: str) -> None:
    target_dir = os.path.abspath(target_dir)
    if not target_dir.startswith("/workspace"):
        print(f"Error: Target directory must be inside /workspace (got {target_dir})")
        sys.exit(1)
    for sub in (".agents/memory", ".agents/scripts", ".agents/skills"):
        os.makedirs(os.path.join(target_dir, sub), exist_ok=True)
    if os.path.abspath(SOURCE_WORKSPACE) != target_dir:
        for script_name in os.listdir(SOURCE_SCRIPTS_DIR):
            if not script_name.endswith(".py"):
                continue
            src_file = os.path.join(SOURCE_SCRIPTS_DIR, script_name)
            dst_file = os.path.join(target_dir, ".agents/scripts", script_name)
            with open(src_file, "r", encoding="utf-8") as fh:
                content = fh.read().replace(SOURCE_WORKSPACE, target_dir)
            with open(dst_file, "w", encoding="utf-8") as fh:
                fh.write(content)
            make_executable(dst_file)
    else:
        for script_name in os.listdir(SOURCE_SCRIPTS_DIR):
            if script_name.endswith(".py"):
                make_executable(os.path.join(SOURCE_SCRIPTS_DIR, script_name))
    src_hooks = os.path.join(SOURCE_WORKSPACE, ".agents/hooks.json")
    dst_hooks = os.path.join(target_dir, ".agents/hooks.json")
    if os.path.exists(src_hooks) and os.path.abspath(src_hooks) != os.path.abspath(dst_hooks):
        with open(src_hooks, "r", encoding="utf-8") as fh:
            hooks_content = fh.read().replace(SOURCE_WORKSPACE, target_dir)
        with open(dst_hooks, "w", encoding="utf-8") as fh:
            fh.write(hooks_content)
    src_skills = os.path.join(SOURCE_WORKSPACE, ".agents/skills")
    dst_skills = os.path.join(target_dir, ".agents/skills")
    if os.path.exists(src_skills):
        for item in os.listdir(src_skills):
            s_item = os.path.join(src_skills, item)
            d_item = os.path.join(dst_skills, item)
            if os.path.isdir(s_item) and not os.path.exists(d_item):
                shutil.copytree(s_item, d_item)
    for rel_path in ["AGENTS.md", ".agents/memory/USER.md", ".agents/memory/MEMORY.md"]:
        src_p = os.path.join(SOURCE_WORKSPACE, rel_path)
        dst_p = os.path.join(target_dir, rel_path)
        if os.path.exists(src_p) and not os.path.exists(dst_p):
            with open(src_p, "r", encoding="utf-8") as fh:
                text = fh.read().replace(SOURCE_WORKSPACE, target_dir)
            with open(dst_p, "w", encoding="utf-8") as fh:
                fh.write(text)
    print(f"[OK] Hermes-Lite v2 initialized in: {target_dir}")

def main():
    parser = argparse.ArgumentParser(description="Bootstrap Hermes-Lite v2 into a workspace directory")
    parser.add_argument("target", nargs="?", default=SOURCE_WORKSPACE)
    args = parser.parse_args()
    bootstrap_workspace(args.target)

if __name__ == "__main__":
    main()
