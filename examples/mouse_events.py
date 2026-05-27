#!/usr/bin/env python3
"""
examples/mouse_events.py — Hover, double-click, drag-and-drop, implicit wait.

Run with:
    python3 examples/mouse_events.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wwwjs_driver import WebDriver


def main():
    with WebDriver(wait_ms=2000) as driver:
        # Implicit wait example
        driver.implicitly_wait(10)
        print(f"Implicit wait set to: {driver.implicit_wait}s")

        driver.get("https://example.com")
        print(f"Loaded: {driver.current_url}")

        # Hover / move_to_element
        driver.hover("h1")
        print("Hovered over h1.")

        # Double-click
        driver.double_click("p")
        print("Double-clicked on paragraph.")

        # Context click (right-click)
        driver.context_click("a")
        print("Right-clicked on anchor.")

        # Mouse down / up sequence
        driver.mouse_down("h1")
        driver.mouse_up("h1")
        print("Mouse down/up on h1.")

        # Drag and drop example (requires a page with DnD)
        # driver.get("https://example.com/drag-drop-demo")
        # driver.drag_and_drop("#source", "#target")

    print("Done.")


if __name__ == "__main__":
    main()
