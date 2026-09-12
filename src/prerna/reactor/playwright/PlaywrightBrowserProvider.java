/*******************************************************************************
 * Copyright 2015 Defense Health Agency (DHA)
 *
 * If your use of this software does not include any GPLv2 components:
 * 	Licensed under the Apache License, Version 2.0 (the "License");
 * 	you may not use this file except in compliance with the License.
 * 	You may obtain a copy of the License at
 *
 * 	  http://www.apache.org/licenses/LICENSE-2.0
 *
 * 	Unless required by applicable law or agreed to in writing, software
 * 	distributed under the License is distributed on an "AS IS" BASIS,
 * 	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * 	See the License for the specific language governing permissions and
 * 	limitations under the License.
 * ----------------------------------------------------------------------------
 * If your use of this software includes any GPLv2 components:
 * 	This program is free software; you can redistribute it and/or
 * 	modify it under the terms of the GNU General Public License
 * 	as published by the Free Software Foundation; either version 2
 * 	of the License, or (at your option) any later version.
 *
 * 	This program is distributed in the hope that it will be useful,
 * 	but WITHOUT ANY WARRANTY; without even the implied warranty of
 * 	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * 	GNU General Public License for more details.
 *******************************************************************************/
package prerna.reactor.playwright;

import java.io.File;
import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import prerna.sablecc2.om.execptions.SemossPixelException;
import prerna.util.Utility;

/**
 * The single shared headless browser every Playwright reactor drives.
 *
 * <h3>Which browser it launches</h3>
 * <p>
 * Playwright normally insists on its own bundled Chromium, downloaded at runtime
 * into {@code ~/.cache/ms-playwright}. That does not work in the shipped container:
 * Tomcat runs as uid 1001 with {@code HOME=/root}, which it cannot write, and a
 * container with no egress cannot download anything at all. The image also carries
 * {@code google-chrome-stable} already — installed by the Dockerfile years ago and
 * never reachable, because nothing told Playwright to use a system browser.
 *
 * <p>
 * So the browser is now configurable, env var first then DIHelper property, matching
 * {@code NodeUtils}:
 *
 * <ul>
 * <li>{@code PLAYWRIGHT_CHANNEL} — a system browser channel, e.g. {@code chrome},
 * {@code chrome-beta}, {@code msedge}. This is the cheap option: it uses an
 * already-installed browser and downloads nothing.</li>
 * <li>{@code PLAYWRIGHT_EXECUTABLE_PATH} — an explicit binary, for an image whose
 * browser is somewhere Playwright would not look.</li>
 * <li>Neither set — Playwright's bundled Chromium, which requires
 * {@code PLAYWRIGHT_BROWSERS_PATH} to point somewhere the runtime user can read and
 * a prior {@code playwright install chromium}.</li>
 * </ul>
 *
 * <p>
 * Channel wins over executable path if both are set, since a channel is the
 * higher-level statement of intent.
 *
 * <h3>Why the failure is translated</h3>
 * <p>
 * A missing browser surfaced as a raw Playwright error about an executable path,
 * several frames deep, on whatever reactor happened to call first. Since this is a
 * deployment problem with a specific fix rather than a bug in the calling code, it is
 * caught here and re-thrown saying which setting to change.
 */
public final class PlaywrightBrowserProvider {

	private static final Logger classLogger = LogManager.getLogger(PlaywrightBrowserProvider.class);

	/** System browser channel, e.g. {@code chrome}. Preferred over an explicit path. */
	public static final String PLAYWRIGHT_CHANNEL = "PLAYWRIGHT_CHANNEL";

	/** Explicit browser binary, used when no channel is configured. */
	public static final String PLAYWRIGHT_EXECUTABLE_PATH = "PLAYWRIGHT_EXECUTABLE_PATH";

	private static volatile Playwright playwright;
	private static volatile Browser browser;

	private PlaywrightBrowserProvider() {

	}

	public static Browser getBrowser() {
		Browser localBrowser = browser;
		if (localBrowser == null) {
			synchronized (PlaywrightBrowserProvider.class) {
				if (browser == null) {
					browser = launchBrowser();
				}
				localBrowser = browser;
			}
		}
		return localBrowser;
	}

	private static Browser launchBrowser() {
		BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);

		String channel = resolveSetting(PLAYWRIGHT_CHANNEL);
		String executablePath = resolveSetting(PLAYWRIGHT_EXECUTABLE_PATH);

		String describedAs;
		if (channel != null) {
			options.setChannel(channel);
			describedAs = "system channel '" + channel + "'";
		} else if (executablePath != null) {
			// Checked up front so a typo reads as a typo rather than as a Playwright
			// internal error about a path the caller never sees.
			if (!new File(executablePath).canExecute()) {
				throw new SemossPixelException(PLAYWRIGHT_EXECUTABLE_PATH + " is set to '" + executablePath
						+ "', which is not an executable file this process can run.");
			}
			options.setExecutablePath(Paths.get(executablePath));
			describedAs = "executable '" + executablePath + "'";
		} else {
			describedAs = "Playwright's bundled Chromium";
		}

		try {
			Playwright created = Playwright.create();
			Browser launched = created.chromium().launch(options);
			playwright = created;
			classLogger.info("Playwright browser launched using {}", describedAs);
			return launched;
		} catch (Exception e) {
			classLogger.error("Failed to launch a Playwright browser using {}", describedAs, e);
			throw new SemossPixelException("Could not start a browser (" + describedAs
					+ "). Install a browser in the container and set " + PLAYWRIGHT_CHANNEL + " (e.g. 'chrome') or "
					+ PLAYWRIGHT_EXECUTABLE_PATH + ". Detailed error = " + e.getMessage(), e);
		}
	}

	/** Env var first, then DIHelper property — the convention {@code NodeUtils} uses. */
	private static String resolveSetting(String key) {
		String value = System.getenv(key);
		if (value == null || value.trim().isEmpty()) {
			value = Utility.getDIHelperProperty(key);
		}
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	static void shutdown() {
		try {
			if (browser != null) {
				browser.close();
			}
		} finally {
			if (playwright != null) {
				playwright.close();
			}
		}
	}
}
