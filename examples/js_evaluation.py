#!/usr/bin/env python3
"""
examples/js_evaluation.py — Execute JavaScript and use async scripts.

Run with:
    python3 examples/js_evaluation.py
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from wwwjs_driver import WebDriver


def main():
    with WebDriver(wait_ms=2000) as driver:
        driver.get("https://example.com")

        # Simple script
        heading = driver.execute_script("return document.querySelector('h1').textContent;")
        print(f"H1 via JS: {heading}")

        # Async script: wait 500ms then return a value
        result = driver.execute_async_script("""
            var done = arguments[0];
            setTimeout(function() { done('async value'); }, 500);
        """)
        print(f"Async result: {result}")

        # Collect all links via JS
        links = driver.execute_script("""
            return (function() {
                var out = [];
                document.querySelectorAll('a').forEach(function(a) {
                    out.push({href: a.href, text: a.textContent.trim()});
                });
                return JSON.stringify(out);
            })();
        """)
        import json
        for link in json.loads(links):
            print(f"  Link: {link['text'][:40]!r} -> {link['href']}")

    print("Done.")


if __name__ == "__main__":
    main()
