#!/usr/bin/env python3
"""
examples/basic_navigation.py — Demonstrate get, back, forward, refresh, wait helpers.

Run with:
    python3 examples/basic_navigation.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wwwjs_driver import WebDriver


def main():
    with WebDriver(wait_ms=2000) as driver:
        # Navigate
        driver.get("https://example.com")
        print(f"Loaded: {driver.current_url}")
        print(f"Title: {driver.title}")

        # Wait helpers
        el = driver.wait_for_element("h1", timeout=10)
        print(f"Waited for h1: {el['text']}")

        driver.wait_for_text("p", "illustrative", timeout=10)
        print("Waited for paragraph text.")

        # Screenshot
        driver.screenshot("/tmp/example_basic.png")
        print("Screenshot saved to /tmp/example_basic.png")

        # Refresh
        driver.refresh()
        print("Page refreshed.")

    print("Done.")


if __name__ == "__main__":
    main()
