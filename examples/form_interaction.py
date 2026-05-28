#!/usr/bin/env python3
"""
examples/form_interaction.py — Fill a form, submit, and read results.

Run with:
    python3 examples/form_interaction.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from koslenium_driver import WebDriver


def main():
    with WebDriver(wait_ms=3000) as driver:
        # Use a simple test form page
        driver.get("https://httpbin.org/forms/post")
        print(f"Loaded: {driver.current_url}")

        # Fill form fields
        driver.send_keys("input[name='custname']", "Alice")
        driver.send_keys("input[name='custtel']", "555-1234")
        driver.send_keys("textarea[name='comments']", "Hello from koslenium_driver!")

        # Select option
        driver.select_option("select[name='size']", "large")
        print("Selected 'large' size.")

        # Click submit button
        driver.click("input[type='submit']")
        print("Form submitted.")

        # Wait for result page
        driver.wait_for_url("/post", timeout=15)
        print(f"Result URL: {driver.current_url}")

        # Extract some result text
        text = driver.element_text("pre")
        print(f"Response preview: {text[:200]}...")

    print("Done.")


if __name__ == "__main__":
    main()
