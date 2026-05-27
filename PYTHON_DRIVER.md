# wwwjs_driver — Python Selenium-like driver for wwwjs

Zero-dependency Python wrapper around the wwwjs JavaFX WebView socket server.
Auto-starts the server on first use, provides familiar WebDriver-style APIs, and
shuts everything down cleanly on exit.

## Quick start

```python
from wwwjs_driver import WebDriver

with WebDriver() as driver:
    driver.get("https://example.com")
    print(driver.title)
    el = driver.find_element("h1")
    print(el["text"])
    driver.screenshot("/tmp/page.png")
```

## Installation

No package install is required — just copy `wwwjs_driver.py` next to `run.sh`
(and ensure Java / Maven built the project).  The driver locates `run.sh`
automatically.

```bash
# Verify the Java project builds
./run.sh --help

# Run the built-in smoke test
python3 examples/smoke_test.py
```

## Constructor

```python
WebDriver(
    server_port=None,   # Connect to an existing server, or None to auto-start
    cookie_file=None,   # Path to JSON cookie store
    wait_ms=3000,       # Default post-load wait before extraction
    timeout=30,         # Page-load timeout in seconds
)
```

## Core API

### Navigation

| Method | Description |
|---|---|
| `get(url)` | Navigate to *url* in the persistent browser window. Returns rendered HTML. |
| `refresh()` | Reload the current page. |
| `back()` | `history.back()`. |
| `forward()` | `history.forward()`. |

### Page info

| Property | Description |
|---|---|
| `page_source` | Full `document.documentElement.outerHTML`. |
| `current_url` | `window.location.href`. |
| `title` | `document.title`. |

### JavaScript execution

| Method | Description |
|---|---|
| `execute_script(js)` | Execute JS, return the last expression's value. |
| `execute_async_script(js, timeout=30)` | Execute JS that calls `arguments[0](value)` when done. |

### Screenshots & window

| Method | Description |
|---|---|
| `screenshot(path)` | Save PNG to *path* (reloads URL in headless view). |
| `show()` | Show the browser window. |
| `hide()` | Hide the browser window. |
| `quit()` / `close()` | Shutdown the server and clean up. |

## Element helpers

All helpers accept a **CSS selector**.

| Method | Description |
|---|---|
| `find_element(css)` | Return first match as `dict` (`tag`, `text`, `html`, `attributes`), or `None`. |
| `find_elements(css)` | Return **all** matches as a list of dicts. |
| `click(css)` | Click the first matching element. |
| `send_keys(css, text)` | Set `.value` and dispatch an `input` event. |
| `clear(css)` | Clear `.value` and dispatch `input`. |
| `submit(css)` | Call `.submit()` on the element. |
| `select_option(css, value)` | Select `<option>` by **value** in a `<select>`. |
| `select_option_by_text(css, text)` | Select `<option>` by **visible text**. |
| `get_attribute(css, attr)` | Read a specific HTML attribute. |
| `get_property(css, prop)` | Read a DOM property (e.g. `value`, `checked`). |
| `element_text(css)` | Shorthand for `textContent`. |
| `element_html(css)` | Shorthand for `outerHTML`. |
| `scroll_to(css)` | `scrollIntoView({block:'center'})`. |
| `is_displayed(css)` | Return `True` if element is visible. |
| `is_enabled(css)` | Return `True` if element is not disabled. |

## Implicit wait

Set a default timeout that applies to **all** `find_element` calls:

```python
driver.implicitly_wait(10)  # seconds
el = driver.find_element("#slow-loading")  # polls up to 10s
```

## Wait helpers

All wait helpers raise `WebDriverError` on timeout.

| Method | Description |
|---|---|
| `wait_for_element(css, timeout=10)` | Poll until element exists. |
| `wait_for_text(css, text, timeout=10)` | Poll until element contains *text*. |
| `wait_for_url(part, timeout=10)` | Poll until `current_url` contains *part*. |
| `wait_for_title(part, timeout=10)` | Poll until `title` contains *part*. |

## Mouse / pointer events

| Method | Description |
|---|---|
| `click(css)` | Click the first matching element. |
| `hover(css)` / `move_to_element(css)` | Dispatch `mouseenter` + `mouseover`. |
| `double_click(css)` | Dispatch `dblclick`. |
| `context_click(css)` | Dispatch `contextmenu` (right-click). |
| `mouse_down(css)` | Dispatch `mousedown`. |
| `mouse_up(css)` | Dispatch `mouseup`. |
| `drag_and_drop(src_css, dst_css)` | Simulate `dragstart` → `drop` → `dragend`. |

All mouse helpers accept a **CSS selector**.

## Cookie helpers

| Method | Description |
|---|---|
| `get_cookies()` | List of dicts (`name`, `value`, `domain`, `path`). |
| `add_cookie(name, value, domain=None, path="/")` | Set a cookie. |
| `delete_cookie(name)` | Expire a cookie. |
| `delete_all_cookies()` | Expire every cookie for the current domain. |

## Frame / alert helpers

| Method | Description |
|---|---|
| `switch_to_frame(css)` | Future element queries are scoped inside the `<iframe>`. |
| `switch_to_default_content()` | Reset scope to the top-level document. |
| `alert_accept()` | Return the last `window.alert` message text (WebKit dialogs are auto-dismissed). |
| `alert_dismiss()` | Alias for `alert_accept()`. |

## Context manager

`WebDriver` is a context manager — use `with` for automatic cleanup:

```python
with WebDriver() as driver:
    driver.get("https://example.com")
```

Or manage manually:

```python
driver = WebDriver()
try:
    driver.get("https://example.com")
finally:
    driver.quit()
```

An `atexit` handler also ensures the server is shut down if the process exits
without an explicit `quit()`.

## Examples

See the `examples/` directory for runnable scripts:

| Script | What it demonstrates |
|---|---|
| `smoke_test.py` | Basic get, find, screenshot, assertions. |
| `basic_navigation.py` | `get`, `wait_for_element`, `wait_for_text`, `refresh`, `screenshot`. |
| `form_interaction.py` | `send_keys`, `select_option`, `click`, `wait_for_url`. |
| `js_evaluation.py` | `execute_script`, `execute_async_script`, collecting data via JS. |
| `cookie_example.py` | `get_cookies`, `add_cookie`, `delete_cookie`. |
| `mouse_events.py` | `hover`, `double_click`, `context_click`, `mouse_down`/`up`, `implicitly_wait`. |

## Differences from Selenium

- **No native WebDriver protocol** — everything goes through the wwwjs TCP socket.
- **Screenshots reload the page** in a headless WebView, so interactive state is lost.
- **Frames are client-side scoped** — `switch_to_frame` prepends the iframe selector; the server still sees the whole DOM.
- **Alerts are auto-dismissed** by WebKit; `alert_accept` only retrieves the last message text.
- **Mouse events are JS-based** — `hover`, `drag_and_drop`, etc. dispatch DOM events; they do not move the native cursor.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `run.sh not found` | Build the Java project first: `./run.sh --help` |
| `Server exited early` | Check Maven output: `mvn clean package -q` |
| `Timeout waiting for element` | Increase `wait_ms` or `timeout`; some SPAs need longer. |
| `JSException: SyntaxError` | Wrap multi-statement scripts in an IIFE: `return (function(){ ... })();` |

## Architecture

```
  Your Python script
         |
    wwwjs_driver.py  <-- TCP JSON line protocol
         |
    ./run.sh --server
         |
    wwwjs.java socket server
         |
    HeadlessWebRender.java (JavaFX WebEngine)
```

The driver is a thin wrapper: every method serializes a JSON command, sends it
over TCP, and parses the JSON response.  No Java changes are required.
