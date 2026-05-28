package com.example.koslenium_driver;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

public class HeadlessWebRender {

	private static WebView webView;
	private static WebEngine engine;
	private static boolean initialized = false;
	private static CountDownLatch initLatch;

	// Persistent browser-mode fields
	private static Stage browserStage;
	private static WebView browserView;
	private static WebEngine browserEngine;
	private static boolean browserReady = false;

	public static synchronized void init() throws Exception {
		if (initialized) return;
		initLatch = new CountDownLatch(1);

		Thread fxThread = new Thread(() -> Application.launch(App.class));
		fxThread.setDaemon(true);
		fxThread.start();

		initLatch.await(15, TimeUnit.SECONDS);
		if (engine == null) {
			throw new RuntimeException(
				"JavaFX WebEngine failed to initialize.\n" +
				"HINT: Install xvfb for headless environments: sudo apt install xvfb\n" +
				"Or ensure DISPLAY is set (currently: " + System.getProperty("DISPLAY", "not set") + ")"
			);
		}
		initialized = true;
	}

	/**
	 * Create and show the persistent browser window (no page loaded yet).
	 * Blocks until the JavaFX thread creates the window and sets browserReady=true.
	 */
	public static void showBrowserWindow(CountDownLatch shutdownLatch, String cookieFile) throws Exception {
		java.io.File cf = (cookieFile != null && !cookieFile.isEmpty())
			? new java.io.File(cookieFile) : null;
		java.io.File cfFinal = cf;
		CountDownLatch ready = new CountDownLatch(1);
		Platform.runLater(() -> {
			browserView = new WebView();
			browserView.setPrefWidth(1280);
			browserView.setPrefHeight(720);
			browserEngine = browserView.getEngine();
			browserStage = new Stage();
			browserStage.setScene(new Scene(browserView));
			browserStage.centerOnScreen();
			if (shutdownLatch != null) {
				browserStage.setOnCloseRequest(e -> {
					if (cfFinal != null) {
						saveCookiesFromJs(browserEngine, cfFinal.getPath());
					}
					shutdownLatch.countDown();
				});
			}
			browserStage.setTitle("koslenium_driver");
			browserStage.show();
			browserReady = true;
			ready.countDown();
		});
		ready.await(10, TimeUnit.SECONDS);
		if (!browserReady) {
			throw new RuntimeException("Failed to create browser window (timeout)");
		}
	}

	// --- Server-mode dispatch: JSON command in, JSON response out ---

	public static String fetch(String jsonCommand, CountDownLatch shutdownLatch) {
		try {
			JSONObject cmd = new JSONObject(jsonCommand);

			if (cmd.optBoolean("shutdown", false)) {
				Platform.runLater(() -> {
					WebEngine saveEngine = (browserEngine != null) ? browserEngine : engine;
					saveCookiesFromJs(saveEngine, cmd.optString("cookie_file", null));
					Platform.exit();
				});
				return new JSONObject().put("status", "ok").put("data", "shutting down").toString();
			}

			if (cmd.optBoolean("show", false)) {
				Platform.runLater(() -> {
					if (browserReady && browserStage != null) {
						browserStage.show();
					}
				});
				return new JSONObject().put("status", "ok").put("data", "window shown").toString();
			}

			if (cmd.optBoolean("hide", false)) {
				Platform.runLater(() -> {
					if (browserReady && browserStage != null) {
						browserStage.hide();
					}
				});
				return new JSONObject().put("status", "ok").put("data", "window hidden").toString();
			}

			String url = cmd.optString("url", null);
			String script = cmd.optString("script", null);
			long waitMs = cmd.optLong("wait", 3000);
			String selector = cmd.optString("selector", null);
			boolean browser = cmd.optBoolean("browser", false);
			boolean textOnly = cmd.optBoolean("text", false);
			boolean linksOnly = cmd.optBoolean("links", false);
			boolean sourceOnly = cmd.optBoolean("source", false);
			String screenshotPath = cmd.optString("screenshot", null);
			String cookieFile = cmd.optString("cookie_file", null);
			int timeoutSec = cmd.optInt("timeout", 30);

			// Ensure JavaFX is running
			init();

			// Script-only command (no URL): execute on current page
			if (url == null && script != null && !script.isEmpty()) {
				String scriptResult = eval(script, waitMs);
				return new JSONObject()
					.put("status", "ok")
					.put("data", "script executed")
					.put("script_result", scriptResult)
					.toString();
			}

			String body;

			if (screenshotPath != null) {
				screenshot(url, screenshotPath, waitMs, timeoutSec);
				return new JSONObject()
					.put("status", "ok")
					.put("data", "Screenshot saved: " + screenshotPath)
					.toString();
			}

			if (browser) {
				body = renderBrowser(url, waitMs, selector, timeoutSec, cookieFile, shutdownLatch);
			} else {
				body = render(url, waitMs, selector, timeoutSec, cookieFile);
			}

			// Format output
			String output;
			if (sourceOnly) {
				output = body;
			} else if (textOnly) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				PrintStream orig = System.out;
				System.setOut(new PrintStream(buf));
				KosleniumDriver.printText(body);
				System.setOut(orig);
				output = buf.toString();
			} else if (linksOnly) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				PrintStream orig = System.out;
				System.setOut(new PrintStream(buf));
				KosleniumDriver.printLinks(body);
				System.setOut(orig);
				output = buf.toString();
			} else {
				String captcha = KosleniumDriver.detectCaptcha(body);
				if (captcha != null) {
					output = "WARNING: Captcha/bot-detection detected: " + captcha + "\n\n" + body;
				} else {
					output = body;
				}
			}

			return new JSONObject().put("status", "ok").put("data", output).toString();

		} catch (Exception e) {
			try {
				return new JSONObject()
					.put("status", "error")
					.put("data", "Error: " + e.getMessage())
					.toString();
			} catch (Exception ex) {
				return "{\"status\":\"error\",\"data\":\"Internal error\"}";
			}
		}
	}

	// --- Persistent browser mode (single window, reused across requests) ---

	public static String renderBrowser(String url, long waitMs, String waitSelector,
										int timeoutSec, String cookieFile,
										CountDownLatch shutdownLatch) throws Exception {
		init();

		CompletableFuture<String> future = new CompletableFuture<>();
		java.io.File cf = (cookieFile != null && !cookieFile.isEmpty())
			? new java.io.File(cookieFile) : null;
		java.io.File cfFinal = cf;

		Platform.runLater(() -> {
			if (!browserReady) {
				browserView = new WebView();
				browserView.setPrefWidth(1280);
				browserView.setPrefHeight(720);
				browserEngine = browserView.getEngine();

				browserStage = new Stage();
				browserStage.setScene(new Scene(browserView));
				browserStage.centerOnScreen();

				if (shutdownLatch != null) {
					browserStage.setOnCloseRequest(e -> {
						if (cfFinal != null) {
							saveCookiesFromJs(browserEngine, cfFinal.getPath());
						}
						shutdownLatch.countDown();
					});
				}

				browserReady = true;
			}

			browserStage.setTitle("koslenium_driver - " + url);
			browserStage.show();

			AtomicBoolean cookieStageDone = new AtomicBoolean(false);

			browserEngine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
				@Override
				public void changed(ObservableValue<? extends Worker.State> obs,
									Worker.State old, Worker.State state) {
					if (state == Worker.State.SUCCEEDED) {
						if (!cookieStageDone.getAndSet(true)) {
							if (cfFinal != null && cfFinal.exists()) {
								try {
									String content = new String(Files.readAllBytes(cfFinal.toPath()));
									JSONArray arr = new JSONArray(content);
									if (arr.length() > 0) {
										String cookieJs = buildCookieInjectScript(arr);
										browserEngine.executeScript(cookieJs);
										String currentUrl = (String) browserEngine.executeScript(
											"window.location.href"
										);
										browserEngine.load(currentUrl);
										return;
									}
								} catch (Exception e) {
									System.err.println("Cookie error: " + e.getMessage());
								}
							}
						}
						browserEngine.getLoadWorker().stateProperty().removeListener(this);
						javafx.animation.Timeline timeline = new javafx.animation.Timeline(
							new javafx.animation.KeyFrame(Duration.millis(waitMs), ev -> {
								String html = (String) browserEngine.executeScript(
									"document.documentElement.outerHTML"
								);
								if (cfFinal != null) {
									saveCookiesFromJs(browserEngine, cfFinal.getPath());
								}
								future.complete(html);
							})
						);
						timeline.setCycleCount(1);
						timeline.play();
					} else if (state == Worker.State.FAILED) {
						browserEngine.getLoadWorker().stateProperty().removeListener(this);
						future.completeExceptionally(
							new RuntimeException("Page load failed: " + browserEngine.getLoadWorker().getMessage())
						);
					}
				}
			});
			browserEngine.load(url);
		});

		return future.get(Math.max(timeoutSec, 10) + 30, TimeUnit.SECONDS);
	}

	// --- Headless rendering (no window) ---

	public static String render(String url, long waitMs, String waitSelector, int timeoutSec) throws Exception {
		return render(url, waitMs, waitSelector, timeoutSec, null);
	}

	public static String render(String url, long waitMs, String waitSelector, int timeoutSec, String cookieFile)
			throws Exception {
		init();

		CompletableFuture<String> future = new CompletableFuture<>();
		java.io.File cf = (cookieFile != null && !cookieFile.isEmpty())
			? new java.io.File(cookieFile) : null;
		java.io.File cfFinal = cf;

		Platform.runLater(() -> {
			AtomicBoolean cookieStageDone = new AtomicBoolean(false);

			engine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
				@Override
				public void changed(ObservableValue<? extends Worker.State> obs,
									Worker.State old, Worker.State state) {
					if (state == Worker.State.SUCCEEDED) {
						if (!cookieStageDone.getAndSet(true)) {
							if (cfFinal != null && cfFinal.exists()) {
								try {
									String content = new String(Files.readAllBytes(cfFinal.toPath()));
									JSONArray arr = new JSONArray(content);
									if (arr.length() > 0) {
										String cookieJs = buildCookieInjectScript(arr);
										engine.executeScript(cookieJs);
										String currentUrl = (String) engine.executeScript(
											"window.location.href"
										);
										engine.load(currentUrl);
										return;
									}
								} catch (Exception e) {
									System.err.println("Cookie error: " + e.getMessage());
								}
							}
						}
						engine.getLoadWorker().stateProperty().removeListener(this);
						javafx.animation.Timeline timeline = new javafx.animation.Timeline(
							new javafx.animation.KeyFrame(Duration.millis(waitMs), ev -> {
								String html = (String) engine.executeScript(
									"document.documentElement.outerHTML"
								);
								if (cfFinal != null) {
									saveCookiesFromJs(engine, cfFinal.getPath());
								}
								future.complete(html);
							})
						);
						timeline.setCycleCount(1);
						timeline.play();
					} else if (state == Worker.State.FAILED) {
						engine.getLoadWorker().stateProperty().removeListener(this);
						future.completeExceptionally(
							new RuntimeException("Page load failed: " + engine.getLoadWorker().getMessage())
						);
					}
				}
			});
			engine.load(url);
		});

		return future.get(timeoutSec + 30, TimeUnit.SECONDS);
	}

	// --- Interactive mode (visible window, manual captcha resolution) ---

	public static String renderInteractive(String url, String cookieFile, int timeoutSec) throws Exception {
		init();

		CompletableFuture<String> future = new CompletableFuture<>();
		AtomicBoolean cookieStageDone = new AtomicBoolean(false);
		java.io.File cf = (cookieFile != null) ? new java.io.File(cookieFile) : null;

		Platform.runLater(() -> {
			WebView wv = new WebView();
			wv.setPrefWidth(1280);
			wv.setPrefHeight(720);
			WebEngine eng = wv.getEngine();

			Stage stage = new Stage();
			stage.setScene(new Scene(wv));
			stage.setTitle("koslenium_driver - " + url);
			stage.centerOnScreen();

			eng.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
				@Override
				public void changed(ObservableValue<? extends Worker.State> obs,
									Worker.State old, Worker.State state) {
					try {
						if (state == Worker.State.SUCCEEDED) {
							if (!cookieStageDone.getAndSet(true)) {
								if (cf != null && cf.exists()) {
									try {
										String content = new String(Files.readAllBytes(cf.toPath()));
										JSONArray arr = new JSONArray(content);
										if (arr.length() > 0) {
											stage.setTitle("koslenium_driver - " + url + " (restoring cookies...)");
											String cookieJs = buildCookieInjectScript(arr);
											eng.executeScript(cookieJs);
											String currentUrl = (String) eng.executeScript(
												"window.location.href"
											);
											eng.load(currentUrl);
											return;
										}
									} catch (Exception e) {
										System.err.println("Cookie error: " + e.getMessage());
									}
								}
							}
							stage.setTitle("koslenium_driver - " + url);
							stage.show();
						} else if (state == Worker.State.FAILED) {
							stage.setTitle("koslenium_driver - " + url + " (load failed)");
							stage.show();
						}
					} catch (Exception e) {
						stage.setTitle("koslenium_driver - " + url + " (error: " + e.getMessage() + ")");
						stage.show();
					}
				}
			});

			stage.setOnCloseRequest(e -> {
				try {
					String html = (String) eng.executeScript("document.documentElement.outerHTML");
					if (cf != null) {
						saveCookiesFromJs(eng, cf.getPath());
					}
					future.complete(html);
				} catch (Exception ex) {
					future.completeExceptionally(ex);
				}
			});

			stage.show();
			eng.load(url);
		});

		return future.get(Math.max(timeoutSec, 10) + 30, TimeUnit.SECONDS);
	}

	// --- Cookie injection via JavaScript ---

	static String buildCookieInjectScript(JSONArray cookies) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < cookies.length(); i++) {
			JSONObject obj = cookies.getJSONObject(i);
			String name = obj.optString("name", "");
			String value = obj.optString("value", "");
			String path = obj.optString("path", "/");
			String domain = obj.has("domain") ? obj.optString("domain", "") : "";
			if (name.isEmpty()) continue;
			String escapedValue = value.replace("\\", "\\\\").replace("'", "\\'");
			sb.append("document.cookie='").append(name).append("=").append(escapedValue);
			sb.append("; path=").append(path);
			if (!domain.isEmpty()) {
				String d = domain.startsWith(".") ? domain.substring(1) : domain;
				sb.append("; domain=.").append(d);
			}
			sb.append("';\n");
		}
		return sb.toString();
	}

	static void saveCookiesFromJs(WebEngine eng, String cookieFile) {
		if (cookieFile == null || cookieFile.isEmpty()) return;
		try {
			String domain = (String) eng.executeScript("window.location.hostname");
			String raw = (String) eng.executeScript("document.cookie");
			if (raw == null || raw.isEmpty()) return;
			JSONArray arr = new JSONArray();
			String[] pairs = raw.split(";\\s*");
			for (String pair : pairs) {
				int eq = pair.indexOf('=');
				if (eq > 0) {
					String name = pair.substring(0, eq).trim();
					String value = eq < pair.length() - 1 ? pair.substring(eq + 1) : "";
					JSONObject obj = new JSONObject();
					obj.put("name", name);
					obj.put("value", value);
					obj.put("domain", domain);
					obj.put("path", "/");
					obj.put("maxAge", -1);
					obj.put("secure", false);
					obj.put("httpOnly", false);
					arr.put(obj);
				}
			}
			if (arr.length() > 0) {
				Files.writeString(Paths.get(cookieFile), arr.toString(2));
			}
		} catch (Exception e) { /* ignore */ }
	}

	// --- Screenshot ---

	public static void screenshot(String url, String filePath, long waitMs, int timeoutSec) throws Exception {
		init();

		CompletableFuture<Void> future = new CompletableFuture<>();

		Platform.runLater(() -> {
			engine.getLoadWorker().stateProperty().addListener(new javafx.beans.value.ChangeListener<>() {
				@Override
				public void changed(javafx.beans.value.ObservableValue<? extends Worker.State> obs,
									Worker.State old, Worker.State state) {
					if (state == Worker.State.SUCCEEDED) {
						engine.getLoadWorker().stateProperty().removeListener(this);
						javafx.animation.Timeline timeline = new javafx.animation.Timeline(
							new javafx.animation.KeyFrame(Duration.millis(waitMs), ev -> {
								try {
									WritableImage snapshot = webView.snapshot(null, null);
									ImageIO.write(SwingFXUtils.fromFXImage(snapshot, null), "png", new File(filePath));
									future.complete(null);
								} catch (Exception e) {
									future.completeExceptionally(e);
								}
							})
						);
						timeline.setCycleCount(1);
						timeline.play();
					} else if (state == Worker.State.FAILED) {
						engine.getLoadWorker().stateProperty().removeListener(this);
						future.completeExceptionally(
							new RuntimeException("Page load failed: " + engine.getLoadWorker().getMessage())
						);
					}
				}
			});
			engine.load(url);
		});

		future.get(timeoutSec + 30, TimeUnit.SECONDS);
	}

	// --- Execute JavaScript on current page (no URL load) ---

	public static String eval(String script, long waitMs) throws Exception {
		init();
		CompletableFuture<String> future = new CompletableFuture<>();
		Platform.runLater(() -> {
			WebEngine target = (browserEngine != null) ? browserEngine : engine;
			try {
				if (waitMs > 0) {
					Thread.sleep(waitMs);
				}
				// executeScript returns the value of the last expression.
				// If user wrote "return expr", wrap in an IIFE to make it valid.
				String cleanScript = script.trim();
				String codeToEval;
				if (cleanScript.startsWith("return ")) {
					codeToEval = "(function(){" + cleanScript + "})()";
				} else {
					codeToEval = cleanScript;
				}
				Object result = target.executeScript(codeToEval);
				future.complete(result != null ? result.toString() : "null");
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future.get(30, TimeUnit.SECONDS);
	}

	public static void shutdown() {
		Platform.exit();
	}

	public static class App extends Application {
		@Override
		public void start(Stage stage) {
			webView = new WebView();
			webView.setPrefWidth(1280);
			webView.setPrefHeight(720);
			engine = webView.getEngine();
			stage.setScene(new Scene(webView));
			initLatch.countDown();
		}
	}
}
