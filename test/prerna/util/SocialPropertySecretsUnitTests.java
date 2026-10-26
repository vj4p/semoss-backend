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
package prerna.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SocialPropertySecretsUnitTests {

	@Test
	void secretKeysAreRecognisedAcrossProviders() {
		for (String key : new String[] { "secret_key", "google_secret_key", "graphapi_secret_key",
				"graphapi_application_credentials", "master_credentials", "adminpassword", "smtp_password",
				"google_maps_api", "maps_api" }) {
			assertTrue(SocialPropertySecrets.isSecretKey(key), key);
		}
	}

	@Test
	void ordinaryKeysAreNotSecrets() {
		for (String key : new String[] { "client_id", "redirect_uri", "scope", "token_url", "login",
				"user_require_dynamic_token", "access_keys_allowed", "key_username",
				"use_custom_context_for_pwd_password" }) {
			assertFalse(SocialPropertySecrets.isSecretKey(key), key);
		}
	}

	@Test
	void maskValueShowsSetOrNotSetOnly() {
		assertEquals(SocialPropertySecrets.MARKER, SocialPropertySecrets.maskValue("secret_key", "hunter2"));
		assertEquals("", SocialPropertySecrets.maskValue("secret_key", ""));
		assertEquals("", SocialPropertySecrets.maskValue("secret_key", null));
		assertEquals("abc", SocialPropertySecrets.maskValue("client_id", "abc"));
	}

	@Test
	void dropUnchangedKeepsOnlyRealEdits() {
		Map<String, String> mods = new HashMap<>();
		mods.put("secret_key", SocialPropertySecrets.MARKER);
		mods.put("client_id", "new");
		Map<String, String> changed = SocialPropertySecrets.dropUnchanged(mods);
		assertEquals(Map.of("client_id", "new"), changed);
	}

	@Test
	void maskFileContentsHidesSecretsAndKeepsEverythingElse() {
		String file = "# header\ngoogle_client_id=abc\ngoogle_secret_key = hunter2\ngoogle_login=true\n";
		String masked = SocialPropertySecrets.maskFileContents(file);
		assertFalse(masked.contains("hunter2"));
		assertEquals("# header\ngoogle_client_id=abc\ngoogle_secret_key=" + SocialPropertySecrets.MARKER
				+ "\ngoogle_login=true\n", masked);
	}

	@Test
	void maskFileContentsDropsContinuationLinesOfASecret() {
		String file = "a_secret_key=line1\\\n  line2\nb=1\n";
		String masked = SocialPropertySecrets.maskFileContents(file);
		assertFalse(masked.contains("line1"));
		assertFalse(masked.contains("line2"));
		assertEquals("a_secret_key=" + SocialPropertySecrets.MARKER + "\nb=1\n", masked);
	}

	@Test
	void emptySecretsAreLeftEmptySoTheyReadAsNotSet() {
		assertEquals("google_secret_key=\n", SocialPropertySecrets.maskFileContents("google_secret_key=\n"));
	}

	@Test
	void savingAMaskedFileRestoresTheStoredSecret() {
		String current = "google_client_id=abc\ngoogle_secret_key=hunter2\n";
		String edited = SocialPropertySecrets.maskFileContents(current).replace("abc", "xyz");
		assertEquals("google_client_id=xyz\ngoogle_secret_key=hunter2\n",
				SocialPropertySecrets.restoreMarkedValues(edited, current));
	}

	@Test
	void savingAMaskedFileRestoresAMultiLineSecret() {
		String current = "a_secret_key=line1\\\n  line2\nb=1\n";
		String edited = SocialPropertySecrets.maskFileContents(current);
		assertEquals(current, SocialPropertySecrets.restoreMarkedValues(edited, current));
	}

	@Test
	void aReplacedSecretIsWrittenAsTyped() {
		String current = "google_secret_key=old\n";
		assertEquals("google_secret_key=new\n",
				SocialPropertySecrets.restoreMarkedValues("google_secret_key=new\n", current));
	}

	@Test
	void crlfLineEndingsSurviveTheRoundTrip() {
		String current = "a=1\r\nx_secret=s3\r\nb=2\r\n";
		String edited = SocialPropertySecrets.maskFileContents(current);
		assertFalse(edited.contains("s3"));
		assertEquals(current, SocialPropertySecrets.restoreMarkedValues(edited, current));
	}

	@Test
	void whitespaceSeparatedLinesAreMaskedLikeEqualsOnes() {
		String file = "google_client_id   abc\ngoogle_secret_key\thunter2\ngoogle_login false\n";
		String masked = SocialPropertySecrets.maskFileContents(file);
		assertFalse(masked.contains("hunter2"));
		assertEquals("google_client_id   abc\ngoogle_secret_key=" + SocialPropertySecrets.MARKER
				+ "\ngoogle_login false\n", masked);
		assertEquals(file, SocialPropertySecrets.restoreMarkedValues(masked, file));
	}

	@Test
	void keyOnlyLinesAreLeftAloneAndReadAsNotSet() {
		String file = "dropbox_client_id\ndropbox_secret_key\ndropbox_login false\n";
		assertEquals(file, SocialPropertySecrets.maskFileContents(file));
	}

	@Test
	void anEqualsOrColonAfterWhitespaceIsTheSeparator() {
		String file = "a_secret_key : hunter2\nb_secret_key = hunter3\n";
		String masked = SocialPropertySecrets.maskFileContents(file);
		assertFalse(masked.contains("hunter"));
		assertEquals(file, SocialPropertySecrets.restoreMarkedValues(masked, file));
	}
}
