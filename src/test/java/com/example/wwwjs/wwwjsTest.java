package com.example.wwwjs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class wwwjsTest {

	@TempDir
	Path tempDir;

	// --- Captcha detection ---

	@Test
	public void testDetectCaptchaFindsGoogleBypass() {
		String html = "<html>If you're having trouble accessing Google Search, please click here</html>";
		assertNotNull(wwwjs.detectCaptcha(html));
	}

	@Test
	public void testDetectCaptchaFindsCaptcha() {
		String html = "<html>Please complete the captcha to continue</html>";
		assertNotNull(wwwjs.detectCaptcha(html));
	}

	@Test
	public void testDetectCaptchaPassesCleanPage() {
		String html = "<html><body><h1>Welcome</h1><p>Content.</p></body></html>";
		assertNull(wwwjs.detectCaptcha(html));
	}

	@Test
	public void testDetectCaptchaFindsSpanishRedirect() {
		String html = "<html>Haz clic aquí si no se te redirecciona</html>";
		assertNotNull(wwwjs.detectCaptcha(html));
	}

	// --- Link extraction ---

	@Test
	public void testPrintLinksExtractsAnchors() {
		String html = "<html><a href=\"https://example.com\">Example</a><a href=\"/relative\">Rel</a></html>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream orig = System.out;
		System.setOut(new PrintStream(out));
		try {
			wwwjs.printLinks(html);
			String s = out.toString();
			assertTrue(s.contains("Example"));
			assertTrue(s.contains("https://example.com"));
			assertTrue(s.contains("(relative) /relative"));
		} finally {
			System.setOut(orig);
		}
	}

	@Test
	public void testPrintLinksNoAnchors() {
		String html = "<html><p>No links</p></html>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream orig = System.out;
		System.setOut(new PrintStream(out));
		try {
			wwwjs.printLinks(html);
			assertTrue(out.toString().contains("no links found"));
		} finally {
			System.setOut(orig);
		}
	}

	@Test
	public void testCleanUrlGoogleRedirect() {
		String html = "<a href=\"/url?q=https%3A%2F%2Fgithub.com%2Fm5it&sa=U&ved=...\">GitHub</a>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream orig = System.out;
		System.setOut(new PrintStream(out));
		try {
			wwwjs.printLinks(html);
			assertTrue(out.toString().contains("https://github.com/m5it"));
		} finally {
			System.setOut(orig);
		}
	}

	// --- Text extraction ---

	@Test
	public void testPrintTextStripHtml() {
		String html = "<html><body><h1>Title</h1><p>Content</p></body></html>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream orig = System.out;
		System.setOut(new PrintStream(out));
		try {
			wwwjs.printText(html);
			String s = out.toString();
			assertTrue(s.contains("Title"));
			assertTrue(s.contains("Content"));
		} finally {
			System.setOut(orig);
		}
	}

	@Test
	public void testPrintTextStripsScripts() {
		String html = "<html><script>alert('xss');</script><body>Real text</body></html>";
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		PrintStream orig = System.out;
		System.setOut(new PrintStream(out));
		try {
			wwwjs.printText(html);
			String s = out.toString();
			assertFalse(s.contains("alert"));
			assertTrue(s.contains("Real text"));
		} finally {
			System.setOut(orig);
		}
	}

	// --- Config loading ---

	@Test
	public void testLoadConfigValue() throws IOException {
		Path cfg = tempDir.resolve("config.json");
		Files.writeString(cfg, "{\"selector\": \"#main\"}");
		String val = wwwjs.loadConfigValue(cfg.toString(), "selector", "default");
		assertEquals("#main", val);
	}

	@Test
	public void testLoadConfigValueDefault() {
		String val = wwwjs.loadConfigValue("nonexistent.json", "selector", "default");
		assertEquals("default", val);
	}

	@Test
	public void testLoadConfigLong() throws IOException {
		Path cfg = tempDir.resolve("config.json");
		Files.writeString(cfg, "{\"waitMs\": 5000}");
		long val = wwwjs.loadConfigLong(cfg.toString(), "waitMs", 1000);
		assertEquals(5000, val);
	}

	@Test
	public void testLoadConfigLongDefault() {
		long val = wwwjs.loadConfigLong("nonexistent.json", "waitMs", 1000);
		assertEquals(1000, val);
	}

	@Test
	public void testLoadTimeout() throws IOException {
		Path cfg = tempDir.resolve("config.json");
		Files.writeString(cfg, "{\"timeout\": 15}");
		int t = wwwjs.loadTimeout(cfg.toString());
		assertEquals(15, t);
	}

	@Test
	public void testLoadTimeoutDefault() {
		int t = wwwjs.loadTimeout("nonexistent.json");
		assertEquals(wwwjs.DEFAULT_TIMEOUT, t);
	}

	@Test
	public void testLoadUserAgentFromConfig() throws IOException {
		Path cfg = tempDir.resolve("config.json");
		Files.writeString(cfg, "{\"userAgent\": \"MyBot/1.0\"}");
		String ua = wwwjs.loadUserAgent(cfg.toString(), null);
		assertEquals("MyBot/1.0", ua);
	}

	@Test
	public void testLoadUserAgentCliOverride() {
		String ua = wwwjs.loadUserAgent("nonexistent.json", "CLI/2.0");
		assertEquals("CLI/2.0", ua);
	}

	@Test
	public void testLoadUserAgentDefault() {
		String ua = wwwjs.loadUserAgent("nonexistent.json", null);
		assertEquals(wwwjs.DEFAULT_UA, ua);
	}

	// --- cleanUrl ---

	@Test
	public void testCleanUrlGoogle() {
		String cleaned = wwwjs.cleanUrl("/url?q=https%3A%2F%2Fexample.com&sa=U&ved=2ahUKEwj");
		assertEquals("https://example.com", cleaned);
	}

	@Test
	public void testCleanUrlRelative() {
		String cleaned = wwwjs.cleanUrl("/some/path");
		assertTrue(cleaned.startsWith("(relative)"));
	}

	@Test
	public void testCleanUrlProtocolRelative() {
		String cleaned = wwwjs.cleanUrl("//example.com/path");
		assertEquals("https://example.com/path", cleaned);
	}
}
