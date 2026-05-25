# wwwjs — JavaFX WebView JS rendering CLI

## Structure

- `src/main/java/com/example/wwwjs/wwwjs.java` — CLI app (entry point)
- `src/main/java/com/example/wwwjs/HeadlessWebRender.java` — JavaFX WebView engine (headless + interactive + screenshot)
- `src/test/java/com/example/wwwjs/wwwjsTest.java` — JUnit 5 tests (21)
- `pom.xml` — Java 21, JavaFX 21, maven-shade-plugin
- `config.json` — defaults
- `run.sh` — builds + runs with JavaFX module path

## Build & run

```sh
./run.sh                     # builds if needed, then runs
./run.sh [options] <url>     # pass flags
mvn test                     # run tests
mvn clean package -q         # build fat jar
```

## Flags

| Flag | Description |
|---|---|
| `--ua <str>` | User-Agent header |
| `-c <path>` | Config file path (default: ./config.json) |
| `--wait <ms>` | JS wait time (default: 3000) |
| `--selector <css>` | Wait for CSS selector before extracting |
| `--browser` | Open visible window for manual interaction (captcha solving) |
| `--links` | Extract anchor links from rendered page |
| `--text` | Strip HTML, show readable text |
| `--source` | Show raw HTML (bypasses captcha check) |
| `--cookie-file <path>` | Cookie store file (saves cookies in --browser mode) |
| `--screenshot <file>` | Save rendered page as PNG |
| `-o <file>` | Save output to file |
| `-h` / `--help` | Show help |

## Cookie sharing with www

Cookies saved by wwwjs (`--browser --cookie-file cookies.json`) can be reused by www:

```sh
# Solve captcha in browser, save cookies
./run.sh --browser --cookie-file ~/google_cookies.json "https://google.com"

# Then reuse cookies with www
java -jar ../www/target/www-1.0-SNAPSHOT.jar --cookie-file ~/google_cookies.json --links "https://google.com/search?q=test"
```
