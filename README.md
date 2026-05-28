# koslenium_driver — JavaFX WebView JS rendering CLI

Renders web pages with full JavaScript execution using JavaFX WebKit (WebView). Supports headless rendering, interactive browser mode (captcha solving), screenshots, and a persistent socket-server mode.

## Build & run

```sh
./run.sh                     # build if needed, then run
./run.sh [options] <url>     # one-shot fetch
mvn test                     # run tests (21)
mvn clean package -q         # build fat jar
```

## Flags

| Flag | Description |
|---|---|
| `--ua <str>` / `--user-agent <str>` | Custom User-Agent header |
| `-c <path>` / `--config <path>` | Config file path (default: `./config.json`) |
| `--wait <ms>` | Extra wait time for JS rendering (default: 3000) |
| `--selector <css>` | Wait for CSS selector before extracting content |
| `--browser` | Open visible window for manual interaction (captcha solving) |
| `--links` | Extract anchor links from rendered page |
| `--text` | Strip HTML, return readable text |
| `--source` | Return raw HTML (bypasses captcha detection) |
| `--cookie-file <path>` | Cookie store file (load on start, save on close) |
| `--screenshot <file>` | Save rendered page as PNG screenshot |
| `-o <file>` | Write output to file instead of stdout |
| `--server` | Run in persistent socket-server mode (see below) |
| `--port <num>` | Server port (default: `0` = random, printed to stdout) |
| `-h` / `--help` | Show help |

## Server mode

In server mode (`--server`), koslenium_driver starts a TCP socket server and keeps the JVM + JavaFX WebEngine alive across requests. The Python tool (`tool_WWWJS.py`) uses this automatically — the first call starts the server, subsequent calls send commands over the socket.

### Starting the server

```sh
./run.sh --server --port 9876
# stdout: SERVER_PORT=9876
# stderr: koslenium_driver server listening on port 9876
```

If `--port` is omitted, a random port is assigned and printed as `SERVER_PORT=<port>` on stdout's first line.

### Browser mode in server

```sh
./run.sh --server --port 9876 --browser
```

Opens a persistent browser window. All subsequent fetch commands load URLs in the same window. Closing the window shuts down the server.

### Socket protocol

One JSON command per TCP connection. Connect, send one line, receive one line, close.

#### Command format

```json
{
  "url": "https://example.com",
  "text": true,
  "links": false,
  "source": false,
  "wait": 3000,
  "selector": "#main",
  "browser": false,
  "screenshot": null,
  "cookie_file": null,
  "timeout": 30
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `url` | string | **(required)** | URL to fetch |
| `text` | boolean | `false` | Strip HTML, return readable text |
| `links` | boolean | `false` | Extract anchor links |
| `source` | boolean | `false` | Return raw HTML, bypass captcha check |
| `wait` | number | `3000` | JS render wait time in ms |
| `selector` | string or null | `null` | CSS selector to wait for before extraction |
| `browser` | boolean | `false` | Load in browser window (requires `--browser` on startup) |
| `screenshot` | string or null | `null` | File path to save a PNG screenshot |
| `cookie_file` | string or null | `null` | Path to cookie store file |
| `timeout` | number | `30` | Page load timeout in seconds |

#### Control commands

```json
{ "hide": true }         // Hide the browser window
{ "show": true }         // Show the browser window
{ "shutdown": true }     // Stop the server
```

#### Response format

```json
{
  "status": "ok",
  "data": "Example Domain\nThis domain is for use in documentation..."
}
```

On error:
```json
{
  "status": "error",
  "data": "Error: page load failed: Connection refused"
}
```

### Examples

```sh
# Fetch a page as text
echo '{"url":"https://example.com","text":true,"wait":500}' | nc localhost 9876

# Fetch links
echo '{"url":"https://example.com","links":true}' | nc localhost 9876

# Show/hide browser window
echo '{"show":true}' | nc localhost 9876
echo '{"hide":true}' | nc localhost 9876

# Take a screenshot
echo '{"url":"https://example.com","screenshot":"/tmp/page.png"}' | nc localhost 9876

# Shutdown
echo '{"shutdown":true}' | nc localhost 9876
```

## Cookie sharing with www

Cookies saved by koslenium_driver (`--browser --cookie-file cookies.json`) can be reused by the simple www HTTP client:

```sh
# Solve captcha in browser, save cookies
./run.sh --browser --cookie-file ~/google_cookies.json "https://google.com"

# Then reuse cookies with www (non-JS, fast)
java -jar ../www/target/www-1.0-SNAPSHOT.jar --cookie-file ~/google_cookies.json --links "https://google.com/search?q=test"
```

## Python integration

The `tools/tool_WWWJS.py` wrapper manages the server lifecycle transparently:

- **First call** — starts the server in background, parses `SERVER_PORT=<port>`, saves port to `/tmp/koslenium_driver.port`
- **Subsequent calls** — connects to the running server via TCP socket, sends JSON command, reads JSON response
- **Fallback** — if the server is unavailable (crashed, not started), falls back to one-shot mode
- **Cleanup** — `atexit` handler terminates the server process
- **Bypass** — to force one-shot mode, call the tool with `server=false`

## Config file (`config.json`)

```json
{
  "userAgent": "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0",
  "waitMs": 3000,
  "selector": "",
  "cookieFile": "cookies.json",
  "timeout": 30
}
```

## Requirements

- Java 21+
- JavaFX 21 (Maven downloads automatically via `pom.xml`)
- `DISPLAY` environment variable or `xvfb` for headless rendering
