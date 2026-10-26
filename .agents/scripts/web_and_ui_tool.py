#!/usr/bin/env python3
import argparse
import html.parser
import os
import re
import shutil
import subprocess
import sys
import urllib.request

class SimpleHTMLToText(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip_tags = {"script", "style", "noscript", "svg", "head"}
        self.skip_depth = 0
        self.chunks = []

    def handle_starttag(self, tag, attrs):
        if tag in self.skip_tags:
            self.skip_depth += 1
        elif tag in ("p", "div", "br", "li", "h1", "h2", "h3", "h4", "tr"):
            self.chunks.append("\n")

    def handle_endtag(self, tag):
        if tag in self.skip_tags and self.skip_depth > 0:
            self.skip_depth -= 1

    def handle_data(self, data):
        if self.skip_depth == 0:
            text = data.strip()
            if text:
                self.chunks.append(text + " ")

    def get_text(self) -> str:
        raw = "".join(self.chunks)
        lines = [re.sub(r"\s+", " ", line).strip() for line in raw.splitlines()]
        return "\n".join(line for line in lines if line)

def fetch_web(url: str, max_chars: int = 6000) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "HermesLite-Reader/2.0 (+NextCraftForge)"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw_html = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"Error fetching {url}: {e}")
        sys.exit(1)
    parser = SimpleHTMLToText()
    parser.feed(raw_html)
    text = parser.get_text()
    print(f"=== Extracted Content from {url} ({len(text)} chars) ===\n{text[:max_chars]}")

def android_ui_status() -> None:
    dumpsys_bin = shutil.which("android-dumpsys") or "/opt/nextcraft/bin/android-dumpsys"
    if not os.path.exists(dumpsys_bin):
        print("android-dumpsys not found.")
        return
    try:
        out = subprocess.check_output(["sh", dumpsys_bin, "window", "displays"], stderr=subprocess.DEVNULL, timeout=5).decode("utf-8", errors="replace")
        for line in out.splitlines():
            if any(k in line for k in ("init=", "mCurrentFocus=", "mFocusedApp=")):
                print(line.strip())
    except Exception as e:
        print(f"UI status check failed: {e}")

def main():
    parser = argparse.ArgumentParser(description="Hermes-Lite Web Reader & Android UI Bridge")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_web = sub.add_parser("fetch")
    p_web.add_argument("url")
    p_web.add_argument("-m", "--max-chars", type=int, default=6000)
    sub.add_parser("ui-focus")
    args = parser.parse_args()
    if args.cmd == "fetch":
        fetch_web(args.url, args.max_chars)
    elif args.cmd == "ui-focus":
        android_ui_status()

if __name__ == "__main__":
    main()
