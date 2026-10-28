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
package prerna.collaboration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ChatBrainClassifierParseUnitTests {

	@Test
	void readsJsonThatSitsInsideProse() {
		Map<String, Object> answer = ChatBrainClassifier
				.parse("Here you go: {\"fyi\": 1, \"automated\": 1, \"urgency\": 0} hope that helps");
		assertEquals(1, ((Number) answer.get("fyi")).intValue());
		assertEquals(0, ((Number) answer.get("urgency")).intValue());
	}

	@Test
	void ignoresAThinkBlockBeforeTheAnswer() {
		Map<String, Object> answer = ChatBrainClassifier
				.parse("<think>maybe {not json}</think>{\"fyi\": 0, \"automated\": 0, \"urgency\": 2}");
		assertEquals(2, ((Number) answer.get("urgency")).intValue());
	}

	@Test
	void aBlankReplyNamesTheTokenLimitNotTheFormat() {
		for (String blank : new String[] { null, "", "   \n" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> ChatBrainClassifier.parse(blank));
			assertTrue(e.getMessage().contains("returned no text"), e.getMessage());
			assertTrue(e.getMessage().contains("max tokens"), e.getMessage());
			assertFalse(e.getMessage().contains("did not return JSON"), e.getMessage());
		}
	}

	@Test
	void textWithNoJsonIsStillReportedAsNotJson() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> ChatBrainClassifier.parse("I think this is a newsletter."));
		assertEquals("The classifier model did not return JSON", e.getMessage());
	}
}
