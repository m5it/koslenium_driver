#!/usr/bin/env python3
"""
examples/smoke_test.py — Basic sanity check for koslenium_driver.

Run with:
    python3 examples/smoke_test.py
"""

import os
import sys
import tempfile

# Ensure the repo root is on the path so we can import koslenium_driver
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from koslenium_driver import WebDriver


def main():
    print("[smoke] Starting driver...")
    with WebDriver(wait_ms=2000, timeout=30) as driver:
        print(f"[smoke] Server port: {driver._port}")

        driver.get("https://example.com")
        print(f"[smoke] URL: {driver.current_url}")
        print(f"[smoke] Title: {driver.title}")
        assert "Example Domain" in driver.page_source

        el = driver.find_element("h1")
        print(f"[smoke] h1 tag={el['tag']} text={el['text']!r}")
        assert el["text"] == "Example Domain"

        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            driver.screenshot(f.name)
            print(f"[smoke] Screenshot saved: {f.name}")
            os.unlink(f.name)

    print("[smoke] All checks passed.")


if __name__ == "__main__":
    main()
