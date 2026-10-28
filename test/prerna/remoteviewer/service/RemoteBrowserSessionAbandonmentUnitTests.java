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
package prerna.remoteviewer.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class RemoteBrowserSessionAbandonmentUnitTests {

	private static final Duration GRACE = Duration.ofSeconds(120);
	private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

	private static Instant ago(long seconds) {
		return NOW.minusSeconds(seconds);
	}

	@Test
	void aSessionWithAnOpenViewerIsNeverAbandoned() {
		assertFalse(RemoteBrowserSession.isAbandoned(true, null, ago(86_400), ago(86_400), NOW, GRACE));
	}

	@Test
	void aViewerThatLeftLongAgoAndLeftNothingGoingIsAbandoned() {
		assertTrue(RemoteBrowserSession.isAbandoned(false, ago(300), ago(3_600), ago(3_000), NOW, GRACE));
	}

	@Test
	void aViewerThatJustLeftMayComeBackWithinTheGracePeriod() {
		// a page reload closes the socket and opens a new one a moment later
		assertFalse(RemoteBrowserSession.isAbandoned(false, ago(5), ago(3_600), ago(3_000), NOW, GRACE));
	}

	@Test
	void activityAfterTheViewerLeftKeepsTheSessionAlive() {
		// something still drives it over REST: the grace period counts from that
		assertFalse(RemoteBrowserSession.isAbandoned(false, ago(600), ago(3_600), ago(30), NOW, GRACE));
		assertTrue(RemoteBrowserSession.isAbandoned(false, ago(600), ago(3_600), ago(200), NOW, GRACE));
	}

	@Test
	void aSessionWhoseViewerNeverConnectedCountsFromItsCreation() {
		assertFalse(RemoteBrowserSession.isAbandoned(false, null, ago(10), ago(10), NOW, GRACE));
		assertTrue(RemoteBrowserSession.isAbandoned(false, null, ago(500), ago(500), NOW, GRACE));
	}

	@Test
	void theExactGraceBoundaryIsNotYetAbandoned() {
		assertFalse(RemoteBrowserSession.isAbandoned(false, ago(120), ago(3_600), null, NOW, GRACE));
		assertTrue(RemoteBrowserSession.isAbandoned(false, ago(121), ago(3_600), null, NOW, GRACE));
	}
}
