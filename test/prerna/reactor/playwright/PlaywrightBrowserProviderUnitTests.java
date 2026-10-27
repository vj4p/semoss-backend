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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import prerna.sablecc2.om.execptions.SemossPixelException;

/**
 * Playwright.create() starts a Node driver process. A launch that fails after it
 * must close that driver, or every failed attempt leaves a process behind.
 */
class PlaywrightBrowserProviderUnitTests {

	private static Playwright playwrightWhoseLaunchFails() {
		Playwright playwright = mock(Playwright.class);
		BrowserType chromium = mock(BrowserType.class);
		when(playwright.chromium()).thenReturn(chromium);
		when(chromium.launch(any(BrowserType.LaunchOptions.class)))
				.thenThrow(new PlaywrightException("Host system is missing dependencies to run browsers."));
		return playwright;
	}

	@Test
	void aFailedLaunchClosesTheDriverEveryTime() {
		Playwright playwright = playwrightWhoseLaunchFails();
		try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
			factory.when(Playwright::create).thenReturn(playwright);

			assertThrows(SemossPixelException.class, PlaywrightBrowserProvider::getBrowser);
			assertThrows(SemossPixelException.class, PlaywrightBrowserProvider::getBrowser);

			verify(playwright, times(2)).close();
		}
	}

	@Test
	void theLaunchErrorIsReportedEvenIfTheDriverWillNotClose() {
		Playwright playwright = playwrightWhoseLaunchFails();
		doThrow(new PlaywrightException("driver already gone")).when(playwright).close();
		try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
			factory.when(Playwright::create).thenReturn(playwright);

			SemossPixelException error = assertThrows(SemossPixelException.class,
					PlaywrightBrowserProvider::getBrowser);

			assertTrue(error.getMessage().contains("Could not start a browser"), error.getMessage());
			assertTrue(error.getMessage().contains("missing dependencies"), error.getMessage());
		}
	}

	@Test
	void nothingIsClosedWhenTheDriverCouldNotStart() {
		Playwright neverStarted = mock(Playwright.class);
		try (MockedStatic<Playwright> factory = mockStatic(Playwright.class)) {
			factory.when(Playwright::create).thenThrow(new PlaywrightException("no driver"));

			assertThrows(SemossPixelException.class, PlaywrightBrowserProvider::getBrowser);

			verify(neverStarted, never()).close();
		}
	}
}
