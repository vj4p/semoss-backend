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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps secret values in the social properties out of admin read responses.
 *
 * <p>
 * Secret-named keys are returned as {@link #MARKER} when they hold a value, and
 * an empty string when they do not, so a screen can show "set" or "not set"
 * without ever receiving the secret. A write that sends {@link #MARKER} back
 * means "unchanged" and must not overwrite the stored value.
 */
public final class SocialPropertySecrets {

	/** Stand-in returned for a secret that has a value and expected back unchanged */
	public static final String MARKER = "__SECRET_SET__";

	private static final Pattern SECRET_KEY = Pattern
			.compile("(?i).*(secret|password|passwd|credential|private_?key|api_?key).*|(?i)(.*_)?maps_api");

	// attribute names rather than values, despite containing "password"
	private static final Pattern NOT_SECRET = Pattern.compile("(?i).*use_custom_context_for_pwd.*");

	private SocialPropertySecrets() {

	}

	/**
	 * @param key a property key, with or without its provider prefix
	 * @return whether the value stored under the key is a secret
	 */
	public static boolean isSecretKey(String key) {
		return key != null && SECRET_KEY.matcher(key).matches() && !NOT_SECRET.matcher(key).matches();
	}

	/**
	 * @return the value to send to the browser for the key
	 */
	public static String maskValue(String key, String value) {
		if (!isSecretKey(key)) {
			return value;
		}
		return value == null || value.isEmpty() ? "" : MARKER;
	}

	/**
	 * @return the modifications without any entry that sends the marker back
	 */
	public static Map<String, String> dropUnchanged(Map<String, String> mods) {
		Map<String, String> changed = new HashMap<>();
		for (Map.Entry<String, String> entry : mods.entrySet()) {
			if (!MARKER.equals(entry.getValue())) {
				changed.put(entry.getKey(), entry.getValue());
			}
		}
		return changed;
	}

	/**
	 * Replaces the value of every secret entry in a properties file with the
	 * marker. Continuation lines of a masked entry are dropped with it.
	 */
	public static String maskFileContents(String contents) {
		StringBuilder out = new StringBuilder();
		for (Entry entry : parse(contents)) {
			if (entry.key != null && isSecretKey(entry.key) && !entry.value().isEmpty()) {
				out.append(entry.key).append('=').append(MARKER).append(entry.eol);
			} else {
				out.append(entry.raw).append(entry.eol);
			}
		}
		return out.toString();
	}

	/**
	 * Puts the stored text back for each entry in the edited file that still holds
	 * the marker, so saving a masked file never writes the marker.
	 */
	public static String restoreMarkedValues(String edited, String current) {
		Map<String, String> currentRaw = new HashMap<>();
		for (Entry entry : parse(current)) {
			if (entry.key != null) {
				currentRaw.put(entry.key, entry.raw);
			}
		}
		StringBuilder out = new StringBuilder();
		for (Entry entry : parse(edited)) {
			if (entry.key != null && MARKER.equals(entry.value())) {
				String original = currentRaw.get(entry.key);
				if (original != null) {
					out.append(original).append(entry.eol);
				}
				// a marker with nothing stored has no value to restore, so the entry is dropped
			} else {
				out.append(entry.raw).append(entry.eol);
			}
		}
		return out.toString();
	}

	/** One logical line: a property with its continuation lines, or a comment or blank line. */
	private static final class Entry {
		private final String raw;
		private final String eol;
		private final String key;

		private Entry(String raw, String eol, String key) {
			this.raw = raw;
			this.eol = eol;
			this.key = key;
		}

		/** The first physical line's value, trimmed; continuation lines are not considered. */
		private String value() {
			String first = raw.split("\\r?\\n", 2)[0];
			return first.substring(split(first)[2]).trim();
		}
	}

	/**
	 * Splits a properties line the way {@link java.util.Properties} does: the key
	 * ends at the first unescaped '=', ':' or whitespace, and the value starts
	 * after whitespace and at most one '=' or ':'. A line with only a key, which
	 * the default social.properties uses, has an empty value.
	 *
	 * @return the index where the key starts, where it ends and where the value
	 *         starts
	 */
	private static int[] split(String line) {
		int n = line.length();
		int i = 0;
		while (i < n && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		int keyStart = i;
		while (i < n) {
			char c = line.charAt(i);
			if (c == '\\') {
				i += 2;
			} else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
				break;
			} else {
				i++;
			}
		}
		i = Math.min(i, n);
		int keyEnd = i;
		while (i < n && Character.isWhitespace(line.charAt(i))) {
			i++;
		}
		if (i < n && (line.charAt(i) == '=' || line.charAt(i) == ':')) {
			i++;
			while (i < n && Character.isWhitespace(line.charAt(i))) {
				i++;
			}
		}
		return new int[] { keyStart, keyEnd, i };
	}

	private static List<Entry> parse(String contents) {
		List<Entry> entries = new ArrayList<>();
		if (contents == null || contents.isEmpty()) {
			return entries;
		}
		String[] lines = contents.split("(?<=\n)");
		int i = 0;
		while (i < lines.length) {
			StringBuilder raw = new StringBuilder();
			String eol = "";
			boolean more;
			do {
				String line = lines[i++];
				String body = line;
				eol = "";
				if (body.endsWith("\r\n")) {
					eol = "\r\n";
				} else if (body.endsWith("\n")) {
					eol = "\n";
				}
				body = body.substring(0, body.length() - eol.length());
				raw.append(raw.length() == 0 ? "" : "\n").append(body);
				more = endsWithOpenContinuation(body) && i < lines.length && !isCommentOrBlank(body);
			} while (more);
			String text = raw.toString();
			String key = null;
			if (!isCommentOrBlank(text)) {
				String first = text.split("\n", 2)[0];
				int[] parts = split(first);
				key = first.substring(parts[0], parts[1]);
			}
			entries.add(new Entry(text, eol, key));
		}
		return entries;
	}

	private static boolean isCommentOrBlank(String line) {
		String t = line.trim();
		return t.isEmpty() || t.startsWith("#") || t.startsWith("!");
	}

	private static boolean endsWithOpenContinuation(String line) {
		int slashes = 0;
		for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
			slashes++;
		}
		return slashes % 2 == 1;
	}
}
