# koslenium_driver — JavaFX WebView JS rendering CLI

## Structure

- `src/main/java/com/example/koslenium_driver/KosleniumDriver.java` — CLI entry point; also implements socket server mode (`--server`)
- `src/main/java/com/example/koslenium_driver/HeadlessWebRender.java` — JavaFX WebView engine (headless, interactive browser, screenshot, socket dispatch)
- `src/test/java/com/example/koslenium_driver/KosleniumDriverTest.java` — JUnit 5 unit tests (21); pure Java, no JavaFX/WebView needed
- `pom.xml` — Java 21, JavaFX 21, maven-shade-plugin fat jar
- `config.json` — default `userAgent`, `waitMs`, `selector`, `cookieFile`, `timeout`
- `run.sh` — preferred launcher; auto-detects headless envs and wraps with `xvfb-run`

## Build & run

```sh
./run.sh                     # preferred: builds if needed, handles JavaFX modules & xvfb
./run.sh [options] <url>     # one-shot fetch
mvn test                     # run unit tests (no display/JavaFX needed)
mvn clean package -q         # build fat jar
```

## Headless / JavaFX quirks

- `run.sh` automatically uses `xvfb-run` when `DISPLAY` is unset. Do not manually install/configure xvfb unless the script fails.
- If running the fat jar directly (not via `run.sh`), you must set `--module-path` to JavaFX jars and add `--add-modules javafx.controls,javafx.web`. `run.sh` constructs this from `~/.m2/repository/org/openjfx`.
- `KosleniumDriver.java` hardcodes JavaFX platform properties at startup:
  ```java
  System.setProperty("glass.platform", "gtk");
  System.setProperty("prism.order", "sw");
  System.setProperty("prism.text", "t2k");
  System.setProperty("javafx.platform", "desktop");
  ```
  Do not override these unless specifically testing another renderer.

## Server mode

- `./run.sh --server --port 9876` starts a TCP socket server. First stdout line is `SERVER_PORT=<port>`.
- One JSON command per TCP connection. See `README.md` for full protocol.
- Server mode keeps the JVM + JavaFX WebEngine alive across requests; use `--browser` with `--server` for a persistent interactive window.
- `./run.sh --server --port 9876 --browser` creates the browser window immediately before accepting connections.

## Cookie sharing with www

Cookies saved by koslenium_driver (`--browser --cookie-file cookies.json`) can be reused by the `www` CLI:

```sh
# Solve captcha in browser, save cookies
./run.sh --browser --cookie-file ~/google_cookies.json "https://google.com"

# Then reuse cookies with www (non-JS, fast)
java -jar ../www/target/www-1.0-SNAPSHOT.jar --cookie-file ~/google_cookies.json --links "https://google.com/search?q=test"
```

## Flags

| Flag | Description |
|---|---|
| `--ua <str>` | User-Agent header |
| `-c <path>` | Config file path (default: `./config.json`) |
| `--wait <ms>` | JS wait time (default: 3000) |
| `--selector <css>` | Wait for CSS selector before extracting |
| `--browser` | Open visible window for manual interaction (captcha solving) |
| `--links` | Extract anchor links from rendered page |
| `--text` | Strip HTML, show readable text |
| `--source` | Show raw HTML (bypasses captcha check) |
| `--cookie-file <path>` | Cookie store file (saves cookies in `--browser` mode) |
| `--screenshot <file>` | Save rendered page as PNG |
| `-o <file>` | Save output to file |
| `--server` | Run in persistent socket-server mode |
| `--port <num>` | Server port (default: 0 = random, printed to stdout) |
| `-h` / `--help` | Show help |
