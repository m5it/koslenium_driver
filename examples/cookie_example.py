#!/usr/bin/env python3
"""
examples/cookie_example.py — Read, add, and delete cookies.

Run with:
    python3 examples/cookie_example.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from koslenium_driver import WebDriver


def main():
    with WebDriver(wait_ms=2000) as driver:
        driver.get("https://example.com")

        # Read cookies
        cookies = driver.get_cookies()
        print(f"Found {len(cookies)} cookie(s):")
        for c in cookies:
            print(f"  {c['name']}={c['value']} (domain={c['domain']})")

        # Add a custom cookie
        driver.add_cookie("test_session", "abc123")
        print("Added cookie 'test_session'.")

        # Verify it exists
        cookies = driver.get_cookies()
        names = [c["name"] for c in cookies]
        assert "test_session" in names
        print("Cookie confirmed present.")

        # Delete it
        driver.delete_cookie("test_session")
        print("Deleted cookie 'test_session'.")

        cookies = driver.get_cookies()
        names = [c["name"] for c in cookies]
        assert "test_session" not in names
        print("Cookie confirmed gone.")

    print("Done.")


if __name__ == "__main__":
    main()
