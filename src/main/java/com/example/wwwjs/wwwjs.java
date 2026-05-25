package com.example.wwwjs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

public class wwwjs {

	static final String DEFAULT_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0";
	static final long DEFAULT_WAIT_MS = 3000;
	static final int DEFAULT_TIMEOUT = 30;

	public static void main(String[] args) throws Exception {
		// Force GTK platform (not Monocle) — GPU-accelerated with software fallback
		System.setProperty("glass.platform", "gtk");
		System.setProperty("prism.order", "sw");
		System.setProperty("prism.text", "t2k");
		System.setProperty("javafx.platform", "desktop");

		String url = null;
		String configPath = "config.json";
		String uaOverride = null;
		long waitMs = DEFAULT_WAIT_MS;
		String waitSelector = null;
		boolean linksOnly = false;
		boolean textOnly = false;
		boolean sourceOnly = false;
		boolean browserMode = false;
		String screenshotPath = null;
		String cookieFile = null;
		String outputFile = null;
		boolean serverMode = false;
		int serverPort = 0;

		int i = 0;
		while (i < args.length) {
			switch (args[i]) {
				case "--ua":
				case "--user-agent":
					if (i + 1 < args.length) uaOverride = args[++i];
					else { System.err.println("Error: --ua requires a value"); System.exit(1); }
					break;
				case "-c":
				case "--config":
					if (i + 1 < args.length) configPath = args[++i];
					else { System.err.println("Error: --config requires a path"); System.exit(1); }
					break;
				case "--wait":
					if (i + 1 < args.length) {
						try { waitMs = Long.parseLong(args[++i]); }
						catch (NumberFormatException e) {
							System.err.println("Error: --wait requires a number (milliseconds)");
							System.exit(1);
						}
					} else { System.err.println("Error: --wait requires a value"); System.exit(1); }
					break;
				case "--selector":
					if (i + 1 < args.length) waitSelector = args[++i];
					else { System.err.println("Error: --selector requires a CSS selector"); System.exit(1); }
					break;
				case "--links":
					linksOnly = true;
					break;
				case "--text":
					textOnly = true;
					break;
				case "--browser":
					browserMode = true;
					break;
				case "--source":
					sourceOnly = true;
					break;
				case "--cookie-file":
					if (i + 1 < args.length) cookieFile = args[++i];
					else { System.err.println("Error: --cookie-file requires a path"); System.exit(1); }
					break;
				case "--screenshot":
					if (i + 1 < args.length) screenshotPath = args[++i];
					else { System.err.println("Error: --screenshot requires a file path"); System.exit(1); }
					break;
				case "-o":
					if (i + 1 < args.length) outputFile = args[++i];
					else { System.err.println("Error: -o requires a file path"); System.exit(1); }
					break;
				case "--server":
					serverMode = true;
					break;
				case "--port":
					if (i + 1 < args.length) {
						try { serverPort = Integer.parseInt(args[++i]); }
						catch (NumberFormatException e) {
							System.err.println("Error: --port requires a number");
							System.exit(1);
						}
					} else { System.err.println("Error: --port requires a value"); System.exit(1); }
					break;
				case "-h":
				case "--help":
					printUsage();
					return;
				default:
					if (args[i].startsWith("-")) {
						System.err.println("Unknown option: " + args[i]);
						printUsage();
						System.exit(1);
					}
					url = args[i];
					break;
			}
			i++;
		}

		// Load config
		String userAgent = loadUserAgent(configPath, uaOverride);
		if (uaOverride != null) userAgent = uaOverride;
		long configWait = loadConfigLong(configPath, "waitMs", DEFAULT_WAIT_MS);
		if (waitMs == DEFAULT_WAIT_MS && configWait != DEFAULT_WAIT_MS) waitMs = configWait;
		if (waitSelector == null) waitSelector = loadConfigValue(configPath, "selector", null);
		if (cookieFile == null) cookieFile = loadConfigValue(configPath, "cookieFile", null);

		int timeoutSec = loadTimeout(configPath);

		// --- Server mode: start socket server and loop ---

		if (serverMode) {
			runServer(serverPort, browserMode, cookieFile, timeoutSec);
			return;
		}

		// --- One-shot mode (original behavior) ---

		if (url == null) {
			System.err.println("Error: URL is required");
			printUsage();
			System.exit(1);
		}

		// Screenshot mode
		if (screenshotPath != null) {
			System.err.println("Rendering page for screenshot...");
			HeadlessWebRender.screenshot(url, screenshotPath, waitMs, timeoutSec);
			System.out.println("Screenshot saved: " + screenshotPath);
			return;
		}

		// Render page
		String body;
		if (browserMode) {
			System.err.println("Opening browser window (close it when done) ...");
			if (cookieFile != null && Files.exists(Paths.get(cookieFile))) {
				System.err.println("Loaded cookies from: " + cookieFile);
			}
			body = HeadlessWebRender.renderInteractive(url, cookieFile, timeoutSec);
			if (cookieFile != null) {
				System.err.println("Cookies saved to: " + cookieFile);
			}
		} else {
			System.err.println("Rendering page (WebView) ...");
			body = HeadlessWebRender.render(url, waitMs, waitSelector, timeoutSec, cookieFile);
		}

		// Captcha / bot-detection (skip in --browser or --source mode)
		boolean captchaBypass = sourceOnly || browserMode;
		if (!captchaBypass) {
			String captchaMatch = detectCaptcha(body);
			if (captchaMatch != null) {
				System.err.println("Warning: Target returned a bot-detection or captcha page (matched: \"" + captchaMatch + "\")");
				System.err.println("Use --browser to open a visible window and solve it manually, or --source to view raw HTML.");
				System.exit(1);
			}
		}

		// Output modes: --browser defaults to raw HTML (like www) unless explicit flag given
		boolean explicitOutput = linksOnly || textOnly || sourceOnly;
		String output;
		if ((browserMode && !explicitOutput) || sourceOnly) {
			output = body;
		} else if (linksOnly) {
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			java.io.PrintStream origOut = System.out;
			System.setOut(new java.io.PrintStream(buf));
			printLinks(body);
			System.setOut(origOut);
			output = buf.toString();
		} else if (textOnly) {
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			java.io.PrintStream origOut = System.out;
			System.setOut(new java.io.PrintStream(buf));
			printText(body);
			System.setOut(origOut);
			output = buf.toString();
		} else {
			output = body;
		}

		if (outputFile != null) {
			Files.writeString(Paths.get(outputFile), output);
		} else {
			System.out.print(output);
		}
	}

	// --- Socket server ---

	static void runServer(int port, boolean browserMode, String cookieFile, int timeoutSec) throws Exception {
		HeadlessWebRender.init();

		CountDownLatch shutdownLatch = new CountDownLatch(1);

		// If browser mode, make an initial blank load so the window appears
		if (browserMode) {
			System.err.println("Starting browser server mode (window will appear)...");
			// Trigger browser window creation by loading a blank page
			HeadlessWebRender.renderBrowser("about:blank", 0, null, 5, cookieFile, shutdownLatch);
		}

		try (ServerSocket ss = new ServerSocket(port)) {
			System.out.println("SERVER_PORT=" + ss.getLocalPort());
			System.err.println("wwwjs server listening on port " + ss.getLocalPort());

			while (true) {
				// Check for shutdown (e.g., browser window closed)
				if (shutdownLatch.getCount() == 0) {
					System.err.println("Browser window closed, shutting down...");
					break;
				}

				// Accept with timeout so we can check shutdownLatch periodically
				ss.setSoTimeout(1000);
				Socket s;
				try {
					s = ss.accept();
				} catch (java.net.SocketTimeoutException e) {
					continue;
				}

				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
					PrintWriter writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);

					String jsonCmd = reader.readLine();
					if (jsonCmd == null || jsonCmd.isEmpty()) {
						writer.println("{\"status\":\"error\",\"data\":\"Empty command\"}");
						s.close();
						continue;
					}

					// Check for server-level shutdown
					JSONObject cmd = new JSONObject(jsonCmd);
					if (cmd.optBoolean("shutdown", false) || cmd.optBoolean("quit", false)) {
						writer.println("{\"status\":\"ok\",\"data\":\"shutting down\"}");
						s.close();
						break;
					}

					String result = HeadlessWebRender.fetch(jsonCmd, shutdownLatch);
					writer.println(result);
				} catch (Exception e) {
					System.err.println("Server request error: " + e.getMessage());
					PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
					w.println("{\"status\":\"error\",\"data\":\"Server error: " + e.getMessage() + "\"}");
				} finally {
					try { s.close(); } catch (Exception ignored) {}
				}
			}
		}

		HeadlessWebRender.shutdown();
		System.err.println("wwwjs server stopped.");
	}

	// --- Output formatting ---

	static void printLinks(String html) {
		Pattern pattern = Pattern.compile(
			"<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
			Pattern.CASE_INSENSITIVE
		);
		Matcher matcher = pattern.matcher(html);
		int count = 0;
		while (matcher.find()) {
			String rawUrl = matcher.group(1).trim();
			String anchorText = matcher.group(2).replaceAll("<[^>]+>", "").trim();
			if (anchorText.isEmpty()) anchorText = "(no text)";
			String url = cleanUrl(rawUrl);
			if (!url.isEmpty()) {
				System.out.println("[" + (count++) + "] " + anchorText + " - " + url);
			}
		}
		if (count == 0) {
			System.out.println("(no links found)");
		}
	}

	static String cleanUrl(String raw) {
		if (raw.startsWith("/url?q=")) {
			try {
				String q = raw.substring(7);
				int amp = q.indexOf('&');
				if (amp != -1) q = q.substring(0, amp);
				return URLDecoder.decode(q, "UTF-8");
			} catch (Exception e) {
				return raw;
			}
		}
		if (raw.startsWith("//")) raw = "https:" + raw;
		if (raw.startsWith("/")) raw = "(relative) " + raw;
		return raw;
	}

	static void printText(String html) {
		String text = html
			.replaceAll("<script[^>]*>[\\s\\S]*?</script>", "")
			.replaceAll("<style[^>]*>[\\s\\S]*?</style>", "")
			.replaceAll("<[^>]+>", " ")
			.replaceAll("\\s+", " ")
			.replaceAll("&amp;", "&")
			.replaceAll("&lt;", "<")
			.replaceAll("&gt;", ">")
			.replaceAll("&quot;", "\"")
			.replaceAll("&#\\d+;", " ")
			.trim();
		if (!text.isEmpty()) {
			System.out.println(text);
		} else {
			System.out.println("(no text extracted)");
		}
	}

	static String detectCaptcha(String body) {
		String lower = body.toLowerCase();
		String[][] patterns = {
			{"captcha", "captcha"},
			{"unusual traffic", "unusual traffic"},
			{"please complete the security check", "security check"},
			{"please verify you are a human", "verify human"},
			{"our systems have detected unusual", "detected unusual"},
			{"having trouble accessing", "having trouble accessing"},
			{"haz clic aqu" + '\u00ed' + " si no se te redirecciona", "redirection page"},
			{"before you continue", "before you continue"},
			{"enable javascript", "enable javascript"},
			{"cf-challenge", "cf-challenge"},
			{"attention required", "attention required"},
			{"please click here", "please click here"},
		};
		for (String[] pair : patterns) {
			if (lower.contains(pair[0])) {
				return pair[1];
			}
		}
		return null;
	}

	// --- Config loading ---

	static String loadUserAgent(String configPath, String uaOverride) {
		if (uaOverride != null && !uaOverride.isEmpty()) return uaOverride;
		try {
			Path p = Paths.get(configPath);
			if (Files.exists(p)) {
				JSONObject obj = new JSONObject(new String(Files.readAllBytes(p)));
				if (obj.has("userAgent") && !obj.isNull("userAgent"))
					return obj.getString("userAgent");
			}
		} catch (Exception e) { /* ignore */ }
		Path jarDir = getJarDir();
		if (jarDir != null) {
			try {
				Path p = jarDir.resolve("config.json");
				if (Files.exists(p)) {
					JSONObject obj = new JSONObject(new String(Files.readAllBytes(p)));
					if (obj.has("userAgent") && !obj.isNull("userAgent"))
						return obj.getString("userAgent");
				}
			} catch (Exception e) { /* ignore */ }
		}
		return DEFAULT_UA;
	}

	static String loadConfigValue(String configPath, String key, String defaultValue) {
		try {
			Path p = Paths.get(configPath);
			if (Files.exists(p)) {
				JSONObject obj = new JSONObject(new String(Files.readAllBytes(p)));
				if (obj.has(key) && !obj.isNull(key))
					return obj.getString(key);
			}
		} catch (Exception e) { /* ignore */ }
		return defaultValue;
	}

	static long loadConfigLong(String configPath, String key, long defaultValue) {
		try {
			Path p = Paths.get(configPath);
			if (Files.exists(p)) {
				JSONObject obj = new JSONObject(new String(Files.readAllBytes(p)));
				if (obj.has(key))
					return obj.optLong(key, defaultValue);
			}
		} catch (Exception e) { /* ignore */ }
		return defaultValue;
	}

	static int loadTimeout(String configPath) {
		try {
			Path p = Paths.get(configPath);
			if (Files.exists(p)) {
				return new JSONObject(new String(Files.readAllBytes(p)))
					.optInt("timeout", DEFAULT_TIMEOUT);
			}
		} catch (Exception e) { /* ignore */ }
		return DEFAULT_TIMEOUT;
	}

	static Path getJarDir() {
		try {
			Path jarPath = Paths.get(
				wwwjs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
			);
			return jarPath.getParent();
		} catch (Exception e) { return null; }
	}

	static void printUsage() {
		System.out.println("Usage: java -jar wwwjs.jar [options] <url>");
		System.out.println();
		System.out.println("Options:");
		System.out.println("  --ua, --user-agent <string>  Set custom User-Agent header");
		System.out.println("  -c, --config <path>          Path to config.json (default: ./config.json)");
		System.out.println("  --wait <ms>                  Extra wait time for JS rendering (default: 3000)");
		System.out.println("  --selector <css>             Wait for this CSS selector before extracting");
		System.out.println("  --browser                    Open visible browser window for manual interaction");
		System.out.println("  --links                      Extract all links from rendered page");
		System.out.println("  --text                       Strip HTML and show readable text");
		System.out.println("  --source                     Show raw HTML (bypasses captcha check)");
		System.out.println("  --cookie-file <path>         Cookie store file (saves cookies in browser mode)");
		System.out.println("  --screenshot <file>          Save rendered page as PNG screenshot");
		System.out.println("  -o <file>                    Save output to file (instead of stdout)");
		System.out.println("  --server                     Run in server mode (socket server, one-shot by default)");
		System.out.println("  --port <num>                 Server port (default: 0 = random, printed to stdout)");
		System.out.println("  -h, --help                   Show this help");
		System.out.println();
		System.out.println("Server mode:");
		System.out.println("  First stdout line: SERVER_PORT=<port>");
		System.out.println("  Send JSON commands over TCP socket, one per connection:");
		System.out.println("    {\"url\":\"...\", \"text\":true, \"wait\":2000}");
		System.out.println("    {\"show\":true} / {\"hide\":true}  (show/hide browser window)");
		System.out.println("    {\"shutdown\":true}              (stop server)");
		System.out.println("  Response JSON:");
		System.out.println("    {\"status\":\"ok\",\"data\":\"...\"}");
		System.out.println();
		System.out.println("Config file (config.json):");
		System.out.println("  {");
		System.out.println("    \"userAgent\": \"...\",");
		System.out.println("    \"waitMs\": 3000,");
		System.out.println("    \"selector\": \"#main\",");
		System.out.println("    \"cookieFile\": \"cookies.json\",");
		System.out.println("    \"timeout\": 30");
		System.out.println("  }");
	}
}
