#!/usr/bin/env python3
"""
wwwjs_driver.py — Minimal Selenium-like driver for wwwjs JavaFX WebView server.

Auto-starts the wwwjs socket server on first use and shuts it down on quit().
All communication is over TCP JSON lines (one JSON object per line).

Example:
    from wwwjs_driver import WebDriver

    with WebDriver() as driver:
        driver.get("https://example.com")
        print(driver.page_source)
        el = driver.find_element("h1")
        print(el["text"])
        driver.screenshot("/tmp/page.png")
"""

import atexit
import json
import os
import socket
import subprocess
import sys
import time


class WebDriverError(Exception):
    """Raised when the server returns an error or the driver state is invalid."""
    pass


class WebDriver:
    """
    Lightweight Selenium-style driver backed by the wwwjs JavaFX socket server.
    """

    def __init__(
        self,
        server_port=None,
        cookie_file=None,
        wait_ms=3000,
        timeout=30,
    ):
        """
        :param server_port:  Existing server port to connect to. If None, auto-starts
                             ./run.sh --server in the repo root.
        :param cookie_file:  Path to cookie JSON file (load on start, save on close).
        :param wait_ms:      Default milliseconds to wait after page load before extraction.
        :param timeout:      Page-load timeout in seconds.
        """
        self._host = "127.0.0.1"
        self._port = server_port
        self._cookie_file = cookie_file
        self._wait_ms = wait_ms
        self._timeout = timeout
        self._implicit_wait = 0
        self._proc = None
        self._closed = False

        if self._port is None:
            self._start_server()
        else:
            self._wait_for_server()

        atexit.register(self._atexit_cleanup)

    # ------------------------------------------------------------------
    # Server lifecycle
    # ------------------------------------------------------------------

    def _find_run_sh(self):
        """Locate run.sh next to this script or in the current working directory."""
        script_dir = os.path.dirname(os.path.abspath(__file__))
        candidates = [
            os.path.join(script_dir, "run.sh"),
            os.path.join(os.getcwd(), "run.sh"),
        ]
        for p in candidates:
            if os.path.isfile(p):
                return os.path.abspath(p)
        raise WebDriverError("run.sh not found. Please ensure wwwjs is built.")

    def _start_server(self):
        run_sh = self._find_run_sh()
        cmd = [run_sh, "--server", "--port", "0"]
        if self._cookie_file:
            cmd += ["--cookie-file", self._cookie_file]

        self._proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )

        port = None
        deadline = time.time() + 60  # Maven compile + jar build can take a while
        while time.time() < deadline:
            line = self._proc.stdout.readline()
            if not line:
                if self._proc.poll() is not None:
                    err = self._proc.stderr.read()
                    raise WebDriverError(f"Server exited early. stderr:\n{err}")
                time.sleep(0.1)
                continue
            line = line.strip()
            if line.startswith("SERVER_PORT="):
                try:
                    port = int(line.split("=", 1)[1])
                except ValueError:
                    pass
                break

        if port is None:
            self._proc.terminate()
            raise WebDriverError("Failed to get SERVER_PORT from server startup")

        self._port = port
        self._wait_for_server()

    def _wait_for_server(self):
        deadline = time.time() + 10
        while time.time() < deadline:
            try:
                with socket.create_connection((self._host, self._port), timeout=1):
                    return
            except (socket.timeout, ConnectionRefusedError, OSError):
                time.sleep(0.2)
        raise WebDriverError(f"Server not accepting connections on port {self._port}")

    def _send(self, payload):
        if self._closed:
            raise WebDriverError("Driver is closed")

        line = json.dumps(payload, ensure_ascii=False) + "\n"
        data = line.encode("utf-8")

        sock_timeout = max(self._timeout + 35, 45)
        with socket.create_connection((self._host, self._port), timeout=sock_timeout) as sock:
            sock.sendall(data)
            response = b""
            while b"\n" not in response:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                response += chunk

        try:
            resp = json.loads(response.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise WebDriverError(f"Invalid JSON from server: {response!r}") from exc

        if resp.get("status") != "ok":
            raise WebDriverError(resp.get("data", resp))
        return resp

    # ------------------------------------------------------------------
    # Core navigation / interaction
    # ------------------------------------------------------------------

    def get(self, url):
        """Navigate to *url* in the persistent browser window and return the rendered HTML."""
        payload = {
            "url": url,
            "browser": True,
            "wait": self._wait_ms,
            "timeout": self._timeout,
        }
        if self._cookie_file:
            payload["cookie_file"] = self._cookie_file
        return self._send(payload).get("data")

    def execute_script(self, script):
        """Execute JavaScript on the current page and return the last expression's value."""
        payload = {"script": script, "wait": 0}
        return self._send(payload).get("script_result")

    @property
    def page_source(self):
        """Current page outerHTML (no reload)."""
        return self.execute_script("return document.documentElement.outerHTML")

    @property
    def current_url(self):
        """Current window.location.href."""
        return self.execute_script("return window.location.href")

    @property
    def title(self):
        """Current document.title."""
        return self.execute_script("return document.title")

    def screenshot(self, filepath):
        """
        Save a PNG screenshot to *filepath*.

        .. note::
            The server-side screenshot command loads the URL in a **headless**
            WebView, so it will reflect the page after a fresh load rather than
            any interactive state you may have built up in the browser window.
        """
        url = self.current_url
        if not url:
            raise WebDriverError("No URL loaded. Call get() first.")
        payload = {
            "url": url,
            "screenshot": os.path.abspath(filepath),
            "wait": self._wait_ms,
            "timeout": self._timeout,
        }
        return self._send(payload).get("data")

    def show(self):
        """Show the browser window."""
        return self._send({"show": True}).get("data")

    def hide(self):
        """Hide the browser window."""
        return self._send({"hide": True}).get("data")

    def quit(self):
        """Shutdown the server and release resources."""
        if self._closed:
            return
        self._closed = True
        try:
            self._send({"shutdown": True})
        except Exception:
            pass
        if self._proc is not None:
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait()
        self._proc = None

    def close(self):
        """Alias for quit()."""
        self.quit()

    def _atexit_cleanup(self):
        if not self._closed and self._proc is not None:
            self.quit()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.quit()
        return False

    # ------------------------------------------------------------------
    # Element helpers (pure JS wrappers — no changes to Java side needed)
    # ------------------------------------------------------------------

    @property
    def implicit_wait(self):
        """Current implicit wait timeout in seconds."""
        return self._implicit_wait

    def implicitly_wait(self, seconds):
        """
        Set the default implicit wait for element lookups.

        When > 0, ``find_element`` will poll until the element appears
        or the timeout expires.
        """
        self._implicit_wait = max(0, float(seconds))

    def find_element(self, css_selector):
        """
        Return the first element matching *css_selector* as a dict, or *None*.

        If :meth:`implicitly_wait` was set, this method polls until the
        element appears or the timeout expires.

        Dict keys: ``tag``, ``text``, ``html``, ``attributes`` (dict).
        """
        deadline = time.time() + self._implicit_wait
        poll = min(0.5, self._implicit_wait / 10) if self._implicit_wait else 0

        while True:
            script = f"""
            return (function() {{
                var el = document.querySelector({json.dumps(css_selector)});
                if (!el) return null;
                return JSON.stringify({{
                    tag: el.tagName,
                    text: el.textContent,
                    html: el.outerHTML,
                    attributes: Array.from(el.attributes).reduce(function(acc, a) {{
                        acc[a.name] = a.value;
                        return acc;
                    }}, {{}})
                }});
            }})();
            """
            raw = self.execute_script(script)
            if raw is not None and raw != "null":
                return json.loads(raw)
            if time.time() >= deadline:
                return None
            if poll:
                time.sleep(poll)

    def find_elements(self, css_selector):
        """
        Return **all** elements matching *css_selector* as a list of dicts.
        """
        script = f"""
        return (function() {{
            var els = document.querySelectorAll({json.dumps(css_selector)});
            var out = [];
            for (var i = 0; i < els.length; i++) {{
                var el = els[i];
                out.push({{
                    tag: el.tagName,
                    text: el.textContent,
                    html: el.outerHTML,
                    attributes: Array.from(el.attributes).reduce(function(acc, a) {{
                        acc[a.name] = a.value;
                        return acc;
                    }}, {{}})
                }});
            }}
            return JSON.stringify(out);
        }})();
        """
        raw = self.execute_script(script)
        if raw is None or raw == "null":
            return []
        return json.loads(raw)

    def click(self, css_selector):
        """Click the first element matching *css_selector*."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.click();
            return 'clicked';
        }})();
        """
        return self.execute_script(script)

    def send_keys(self, css_selector, text):
        """
        Set ``.value`` on the first element matching *css_selector* and
        dispatch an ``input`` event (useful for form fields).
        """
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.value = {json.dumps(text)};
            el.dispatchEvent(new Event('input', {{ bubbles: true }}));
            return 'sent';
        }})();
        """
        return self.execute_script(script)

    def element_text(self, css_selector):
        """Return ``textContent`` of the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return null;
            return el.textContent;
        }})();
        """
        return self.execute_script(script)

    def element_html(self, css_selector):
        """Return ``outerHTML`` of the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return null;
            return el.outerHTML;
        }})();
        """
        return self.execute_script(script)

    # ------------------------------------------------------------------
    # Wait helpers (client-side polling)
    # ------------------------------------------------------------------

    def wait_for_element(self, css_selector, timeout=10, poll_interval=0.2):
        """
        Poll until an element matching *css_selector* exists.

        :raises WebDriverError: if the element does not appear within *timeout* seconds.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            el = self.find_element(css_selector)
            if el is not None:
                return el
            time.sleep(poll_interval)
        raise WebDriverError(f"Timeout waiting for element: {css_selector}")

    def wait_for_text(self, css_selector, text, timeout=10, poll_interval=0.2):
        """
        Poll until *text* is found inside the element matching *css_selector*.

        :raises WebDriverError: if the text does not appear within *timeout* seconds.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            el = self.find_element(css_selector)
            if el is not None and text in (el.get("text") or ""):
                return el
            time.sleep(poll_interval)
        raise WebDriverError(f"Timeout waiting for text '{text}' in {css_selector}")

    def wait_for_url(self, url_part, timeout=10, poll_interval=0.2):
        """
        Poll until ``current_url`` contains *url_part*.

        :raises WebDriverError: if the URL does not match within *timeout* seconds.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if url_part in self.current_url:
                return self.current_url
            time.sleep(poll_interval)
        raise WebDriverError(f"Timeout waiting for URL containing: {url_part}")

    def wait_for_title(self, title_part, timeout=10, poll_interval=0.2):
        """
        Poll until ``title`` contains *title_part*.

        :raises WebDriverError: if the title does not match within *timeout* seconds.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            if title_part in self.title:
                return self.title
            time.sleep(poll_interval)
        raise WebDriverError(f"Timeout waiting for title containing: {title_part}")

    # ------------------------------------------------------------------
    # Navigation helpers
    # ------------------------------------------------------------------

    def refresh(self):
        """Reload the current page."""
        return self.execute_script("return (function(){ location.reload(); return 'refreshed'; })();")

    def back(self):
        """Go back in browser history."""
        return self.execute_script("return (function(){ history.back(); return 'back'; })();")

    def forward(self):
        """Go forward in browser history."""
        return self.execute_script("return (function(){ history.forward(); return 'forward'; })();")

    # ------------------------------------------------------------------
    # Form helpers
    # ------------------------------------------------------------------

    def clear(self, css_selector):
        """Clear the ``.value`` of the first element matching *css_selector*."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.value = '';
            el.dispatchEvent(new Event('input', {{ bubbles: true }}));
            return 'cleared';
        }})();
        """
        return self.execute_script(script)

    def submit(self, css_selector):
        """Trigger ``submit()`` on the first form element matching *css_selector*."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.submit();
            return 'submitted';
        }})();
        """
        return self.execute_script(script)

    def select_option(self, css_selector, value):
        """
        Select an ``<option>`` by **value** inside the first ``<select>``
        matching *css_selector*.
        """
        script = f"""
        return (function() {{
            var sel = document.querySelector({json.dumps(css_selector)});
            if (!sel) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            if (sel.tagName !== 'SELECT') throw new Error('Element is not a SELECT');
            var opts = sel.options;
            for (var i = 0; i < opts.length; i++) {{
                if (opts[i].value === {json.dumps(value)}) {{
                    sel.selectedIndex = i;
                    sel.dispatchEvent(new Event('change', {{ bubbles: true }}));
                    return 'selected';
                }}
            }}
            throw new Error('Option value not found: ' + {json.dumps(value)});
        }})();
        """
        return self.execute_script(script)

    def select_option_by_text(self, css_selector, text):
        """
        Select an ``<option>`` by **visible text** inside the first ``<select>``
        matching *css_selector*.
        """
        script = f"""
        return (function() {{
            var sel = document.querySelector({json.dumps(css_selector)});
            if (!sel) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            if (sel.tagName !== 'SELECT') throw new Error('Element is not a SELECT');
            var opts = sel.options;
            for (var i = 0; i < opts.length; i++) {{
                if (opts[i].text === {json.dumps(text)}) {{
                    sel.selectedIndex = i;
                    sel.dispatchEvent(new Event('change', {{ bubbles: true }}));
                    return 'selected';
                }}
            }}
            throw new Error('Option text not found: ' + {json.dumps(text)});
        }})();
        """
        return self.execute_script(script)

    # ------------------------------------------------------------------
    # Element state helpers
    # ------------------------------------------------------------------

    def get_attribute(self, css_selector, attribute):
        """Return a specific HTML attribute of the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return null;
            return el.getAttribute({json.dumps(attribute)});
        }})();
        """
        return self.execute_script(script)

    def get_property(self, css_selector, prop):
        """Return a specific DOM property (e.g. ``value``, ``checked``) of the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return null;
            return el[{json.dumps(prop)}];
        }})();
        """
        return self.execute_script(script)

    def scroll_to(self, css_selector):
        """Scroll the first matching element into view."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.scrollIntoView({{ behavior: 'smooth', block: 'center' }});
            return 'scrolled';
        }})();
        """
        return self.execute_script(script)

    def is_displayed(self, css_selector):
        """Return ``True`` if the element is visible."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return false;
            return !!(el.offsetParent || el.getClientRects().length);
        }})();
        """
        return self.execute_script(script)

    def is_enabled(self, css_selector):
        """Return ``True`` if the element is not disabled."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) return false;
            return !el.disabled;
        }})();
        """
        return self.execute_script(script)

    # ------------------------------------------------------------------
    # Mouse / pointer events
    # ------------------------------------------------------------------

    def hover(self, css_selector):
        """
        Dispatch ``mouseenter`` and ``mouseover`` events on the first element
        matching *css_selector*.
        """
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.dispatchEvent(new MouseEvent('mouseenter', {{ bubbles: true }}));
            el.dispatchEvent(new MouseEvent('mouseover', {{ bubbles: true }}));
            return 'hovered';
        }})();
        """
        return self.execute_script(script)

    def mouse_down(self, css_selector):
        """Dispatch ``mousedown`` on the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.dispatchEvent(new MouseEvent('mousedown', {{ bubbles: true }}));
            return 'mouseDown';
        }})();
        """
        return self.execute_script(script)

    def mouse_up(self, css_selector):
        """Dispatch ``mouseup`` on the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.dispatchEvent(new MouseEvent('mouseup', {{ bubbles: true }}));
            return 'mouseUp';
        }})();
        """
        return self.execute_script(script)

    def context_click(self, css_selector):
        """Dispatch ``contextmenu`` (right-click) on the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.dispatchEvent(new MouseEvent('contextmenu', {{ bubbles: true }}));
            return 'contextClicked';
        }})();
        """
        return self.execute_script(script)

    def double_click(self, css_selector):
        """Dispatch ``dblclick`` on the first matching element."""
        script = f"""
        return (function() {{
            var el = document.querySelector({json.dumps(css_selector)});
            if (!el) throw new Error('Element not found: ' + {json.dumps(css_selector)});
            el.dispatchEvent(new MouseEvent('dblclick', {{ bubbles: true }}));
            return 'doubleClicked';
        }})();
        """
        return self.execute_script(script)

    def move_to_element(self, css_selector):
        """
        Alias for :meth:`hover`.  Dispatches mouse-enter/over events.
        """
        return self.hover(css_selector)

    def drag_and_drop(self, source_css, target_css):
        """
        Simulate a drag-and-drop by dispatching ``dragstart`` on the source
        element and ``drop`` + ``dragend`` on the target element.

        .. note::
            Many modern UIs use custom DnD libraries.  If this does not work,
            fall back to :meth:`execute_script` with library-specific events.
        """
        script = f"""
        return (function() {{
            var src = document.querySelector({json.dumps(source_css)});
            var dst = document.querySelector({json.dumps(target_css)});
            if (!src) throw new Error('Source not found: ' + {json.dumps(source_css)});
            if (!dst) throw new Error('Target not found: ' + {json.dumps(target_css)});

            var dt = new DataTransfer();
            var dragStart = new DragEvent('dragstart',  {{
                bubbles: true, cancelable: true, dataTransfer: dt
            }});
            src.dispatchEvent(dragStart);

            var dragOver = new DragEvent('dragover', {{
                bubbles: true, cancelable: true, dataTransfer: dt
            }});
            dst.dispatchEvent(dragOver);

            var drop = new DragEvent('drop', {{
                bubbles: true, cancelable: true, dataTransfer: dt
            }});
            dst.dispatchEvent(drop);

            var dragEnd = new DragEvent('dragend', {{
                bubbles: true, cancelable: true, dataTransfer: dt
            }});
            src.dispatchEvent(dragEnd);

            return 'dropped';
        }})();
        """
        return self.execute_script(script)

    # ------------------------------------------------------------------
    # Cookie helpers
    # ------------------------------------------------------------------

    def get_cookies(self):
        """Return all cookies as a list of dicts (``name``, ``value``, ``domain``, ``path``)."""
        script = """
        return (function() {
            var domain = window.location.hostname;
            var pairs = document.cookie.split(';');
            var out = [];
            for (var i = 0; i < pairs.length; i++) {
                var pair = pairs[i].trim();
                if (!pair) continue;
                var eq = pair.indexOf('=');
                if (eq > 0) {
                    out.push({
                        name: pair.substring(0, eq).trim(),
                        value: pair.substring(eq + 1),
                        domain: domain,
                        path: '/'
                    });
                }
            }
            return JSON.stringify(out);
        })();
        """
        raw = self.execute_script(script)
        return json.loads(raw) if raw else []

    def add_cookie(self, name, value, domain=None, path="/"):
        """
        Set a cookie.  If *domain* is omitted it defaults to the current host.
        """
        domain = domain or self.execute_script("return window.location.hostname")
        d = domain.lstrip(".")
        script = f"""
        return (function() {{
            document.cookie = {json.dumps(name)} + '=' + {json.dumps(value)} +
                '; domain=.' + {json.dumps(d)} + '; path=' + {json.dumps(path)};
            return 'added';
        }})();
        """
        return self.execute_script(script)

    def delete_cookie(self, name):
        """Delete a cookie by setting its expiry in the past."""
        domain = self.execute_script("return window.location.hostname")
        script = f"""
        return (function() {{
            document.cookie = {json.dumps(name)} + '=; domain=.' +
                {json.dumps(domain)} + '; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
            return 'deleted';
        }})();
        """
        return self.execute_script(script)

    def delete_all_cookies(self):
        """Delete every cookie visible to the current domain."""
        script = """
        return (function() {
            var domain = window.location.hostname;
            var pairs = document.cookie.split(';');
            for (var i = 0; i < pairs.length; i++) {
                var pair = pairs[i].trim();
                if (!pair) continue;
                var eq = pair.indexOf('=');
                if (eq > 0) {
                    var name = pair.substring(0, eq).trim();
                    document.cookie = name + '=; domain=.' + domain +
                        '; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT';
                }
            }
            return 'cleared';
        })();
        """
        return self.execute_script(script)

    # ------------------------------------------------------------------
    # Frame / alert helpers
    # ------------------------------------------------------------------

    def switch_to_frame(self, css_selector):
        """
        Point the internal ``_frame_root`` at an ``<iframe>`` so that subsequent
        element queries operate inside that frame.

        .. note::
            This is a **client-side** abstraction.  The underlying WebEngine still
            sees the whole document; we simply prepend the iframe selector to every
            query.
        """
        if not self.find_element(css_selector):
            raise WebDriverError(f"Frame not found: {css_selector}")
        self._frame_root = css_selector
        return "switched"

    def switch_to_default_content(self):
        """Reset frame context to the top-level document."""
        self._frame_root = None
        return "switched"

    def _scoped_selector(self, css_selector):
        """Prepend the active frame root when one is set."""
        if getattr(self, "_frame_root", None):
            return f"{self._frame_root} {css_selector}"
        return css_selector

    def alert_accept(self):
        """Attempt to accept the current ``window.alert``.  Returns message text."""
        script = """
        return (function() {
            var last = window.__lastAlertMsg || null;
            window.__lastAlertMsg = null;
            return last;
        })();
        """
        return self.execute_script(script)

    def alert_dismiss(self):
        """Alias for ``alert_accept()`` — WebKit dialogs are auto-dismissed on load."""
        return self.alert_accept()

    # ------------------------------------------------------------------
    # Advanced script execution
    # ------------------------------------------------------------------

    def execute_async_script(self, script, timeout=30):
        '''
        Execute JavaScript that calls a callback.  The script receives a
        ``arguments[0]`` function; calling it with a value completes the future.

        Example::

            result = driver.execute_async_script("""
                var callback = arguments[0];
                setTimeout(function() { callback('done'); }, 500);
            """)
        '''
        token = f"__async_result_{int(time.time() * 1000)}"
        wrapped = f"""
        return (function() {{
            var cb = function(val) {{
                window[{json.dumps(token)}] = JSON.stringify({{ done: true, value: val }});
            }};
            ({script})(cb);
            return 'pending';
        }})();
        """
        self.execute_script(wrapped)

        deadline = time.time() + timeout
        while time.time() < deadline:
            raw = self.execute_script(f"return window[{json.dumps(token)}] || null;")
            if raw and raw != "null":
                self.execute_script(f"delete window[{json.dumps(token)}];")
                data = json.loads(raw)
                return data.get("value")
            time.sleep(0.1)
        raise WebDriverError("Timeout waiting for async script callback")


if __name__ == "__main__":
    print("wwwjs_driver.py is a library. Run examples/ scripts or import WebDriver from your code.")
