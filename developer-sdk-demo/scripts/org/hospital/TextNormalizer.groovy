package org.hospital

/**
 * Normalizes legacy HL7 text that is commonly sent in ALL CAPS.
 *
 * Examples:
 *
 *   MILLER         -> Miller
 *   ROBERT         -> Robert
 *   ANNA-MARIE     -> Anna-Marie
 *   O'NEIL         -> O'Neil
 *   125 OAK STREET -> 125 Oak Street
 *   SPRINGFIELD    -> Springfield
 *
 * Existing mixed-case values are preserved.
 */
final class TextNormalizer {

	private TextNormalizer() {
	}

	/**
	 * Normalizes a person-name value.
	 */
	static String name(String value) {
		return normalizeWords(value)
	}

	/**
	 * Normalizes an address line.
	 */
	static String addressLine(String value) {
		return normalizeWords(value)
	}

	/**
	 * Normalizes a city or other simple text value.
	 */
	static String text(String value) {
		return normalizeWords(value)
	}

	private static String normalizeWords(String value) {

		String cleaned = clean(value)

		if (cleaned == null) {
			return null
		}

		if (!shouldNormalize(cleaned)) {
			return cleaned
		}

		return cleaned
				.split(/\s+/)
				.collect { String token ->
					normalizeToken(token)
				}
				.join(' ')
	}

	/**
	 * Handles:
	 *
	 *   MILLER     -> Miller
	 *   ANNA-MARIE -> Anna-Marie
	 *   O'NEIL     -> O'Neil
	 */
	private static String normalizeToken(String token) {

		if (token == null || token.isBlank()) {
			return token
		}

		if (token ==~ /\d+/) {
			return token
		}

		return token
				.split(/(?=[-'])|(?<=[-'])/, -1)
				.collect { String part ->

					if (part == '-' || part == "'") {
						return part
					}

					return capitalize(part)
				}
				.join('')
	}

	private static String capitalize(String value) {

		if (value == null || value.isEmpty()) {
			return value
		}

		String lower = value.toLowerCase(Locale.ROOT)

		if (lower.length() == 1) {
			return lower.toUpperCase(Locale.ROOT)
		}

		return lower.substring(0, 1).toUpperCase(Locale.ROOT) +
				lower.substring(1)
	}

	/**
	 * Only normalizes values where all alphabetic characters are uppercase.
	 *
	 * Already-correct mixed-case values such as:
	 *
	 *   McDonald
	 *   New York
	 *
	 * are preserved.
	 */
	private static boolean shouldNormalize(String value) {

		boolean hasLetter = false

		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i)

			if (!Character.isLetter(ch)) {
				continue
			}

			hasLetter = true

			if (Character.isLowerCase(ch)) {
				return false
			}
		}

		return hasLetter
	}

	private static String clean(String value) {

		if (value == null) {
			return null
		}

		String cleaned = value
				.trim()
				.replaceAll(/\s+/, ' ')

		return cleaned.isEmpty() ? null : cleaned
	}
}