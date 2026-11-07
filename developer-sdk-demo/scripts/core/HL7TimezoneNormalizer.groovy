package core

import ca.uhn.hl7v2.model.*

/**
 * Generic HL7 TS/DTM timezone normalizer.
 *
 * Adds a configured default timezone to HL7 date-time fields when:
 * - the value has time precision
 * - the value does not already contain a timezone
 *
 * Original HL7 precision is preserved.
 *
 * Examples:
 * 20210824             -> unchanged
 * 2021082410           -> 2021082410+0200
 * 202108241004         -> 202108241004+0200
 * 20210824100427       -> 20210824100427+0200
 * 20210824100427+0100  -> unchanged
 */
final class HL7TimezoneNormalizer {

	private HL7TimezoneNormalizer() {
	}

	/**
	 * Applies timezone normalization to exact Terser paths.
	 *
	 * Example:
	 * applyTimezone(msg, "+0200", ["/MSH-7", "/EVN-2-1", "/PV1-44-1"])
	 */
	static void applyTimezone(Message message, String defaultTimezone, Collection<String> paths) {

		if (message == null || paths == null || paths.isEmpty()) {
			return
		}

		String timezone = normalizeTimezone(defaultTimezone)
		if (!isValidTimezone(timezone)) {
			return
		}

		paths.each { String path ->
			applyTimezone(message, path, timezone)
		}
	}

	/**
	 * Applies timezone normalization to one exact Terser path.
	 */
	static void applyTimezone(Message message, String path, String defaultTimezone) {

		if (message == null || !path) {
			return
		}

		String timezone = normalizeTimezone(defaultTimezone)
		if (!isValidTimezone(timezone)) {
			return
		}

		String value = HL7Helper.get(message, path)
		if (!shouldApplyTimezone(value)) {
			return
		}

		HL7Helper.set(message, path, value.trim() + timezone)
	}

	/**
	 * Applies timezone normalization to all occurrences of a segment.
	 *
	 * This method uses SegmentCursor instead of Terser paths because ORU messages
	 * usually contain OBR/OBX/ORC segments inside repeating nested groups.
	 *
	 * Example:
	 * applyTimezoneRepeated(msg, "OBR", [6, 7, 8, 14, 22], "+0200")
	 */
	static void applyTimezoneRepeated(
			Message message,
			String segmentName,
			Collection<Integer> fields,
			String defaultTimezone) {

		if (message == null || !segmentName || fields == null || fields.isEmpty()) {
			return
		}

		String timezone = normalizeTimezone(defaultTimezone)
		if (!isValidTimezone(timezone)) {
			return
		}

		for (HL7SegmentRef entry : HL7SegmentScanner.scan(message)) {
			if (entry == null || segmentName != entry.name) {
				continue
			}

			Segment segment = entry.segment
			if (segment == null || segment.isEmpty()) {
				continue
			}

			fields.each { Integer field ->
				applyTimezone(segment, field, timezone)
			}
		}
	}

	/**
	 * Applies timezone normalization to a field of a concrete segment instance.
	 *
	 * Only the first field repetition is changed. This is correct for common TS/DTM
	 * fields such as ORC-9, OBR-7, OBR-22, OBX-14.
	 */
	static void applyTimezone(Segment segment, int field, String defaultTimezone) {

		if (segment == null || field <= 0) {
			return
		}

		String timezone = normalizeTimezone(defaultTimezone)
		if (!isValidTimezone(timezone)) {
			return
		}

		String value = HL7Helper.safeGet(segment, field)
		if (!shouldApplyTimezone(value)) {
			return
		}

		setFirstRepetitionValue(segment, field, value.trim() + timezone)
	}

	private static boolean shouldApplyTimezone(String value) {

		if (!value) {
			return false
		}

		String v = value.trim()

		return hasTimePrecision(v) && !hasTimezone(v)
	}

	/**
	 * Updates the first repetition of a segment field.
	 *
	 * Handles both primitive TS/DTM fields and composite TS fields where the
	 * timestamp is stored in component 1.
	 */
	private static void setFirstRepetitionValue(Segment segment, int field, String value) {

		try {
			Type type = segment.getField(field, 0)

			if (type instanceof Varies) {
				type = ((Varies) type).getData()
			}

			if (type instanceof Primitive) {
				((Primitive) type).setValue(value)
				return
			}

			if (type instanceof Composite) {
				setCompositeComponentValue((Composite) type, 1, value)
			}
		} catch (Exception ignored) {
		}
	}

	private static void setCompositeComponentValue(Composite composite, int componentNumber, String value) {

		if (composite == null || componentNumber <= 0) {
			return
		}

		try {
			Type[] components = composite.getComponents()
			if (components == null || components.length < componentNumber) {
				return
			}

			Type component = components[componentNumber - 1]

			if (component instanceof Varies) {
				component = ((Varies) component).getData()
			}

			if (component instanceof Primitive) {
				((Primitive) component).setValue(value)
			}
		} catch (Exception ignored) {
		}
	}

	/**
	 * Returns true when the value already ends with an HL7 timezone.
	 */
	static boolean hasTimezone(String value) {
		return value != null && value.trim() ==~ /.*([+-]\d{4}|[Zz])$/
	}

	/**
	 * Returns true when HL7 TS/DTM has time precision.
	 *
	 * Date-only values are intentionally ignored.
	 */
	static boolean hasTimePrecision(String value) {

		if (!value) {
			return false
		}

		String v = value.trim()

		int dot = v.indexOf(".")
		if (dot >= 0) {
			v = v.substring(0, dot)
		}

		return v ==~ /\d{10,14}/
	}

	/**
	 * Normalizes timezone to HL7 TS timezone format.
	 *
	 * +02:00 -> +0200
	 * +0200  -> +0200
	 */
	static String normalizeTimezone(String timezone) {

		String tz = timezone?.trim()
		if (!tz) {
			return null
		}

		if (tz ==~ /[+-]\d{2}:\d{2}/) {
			return tz.replace(":", "")
		}

		return tz
	}

	/**
	 * Accepts HL7 timezone offsets and UTC marker.
	 */
	static boolean isValidTimezone(String timezone) {
		return timezone != null && timezone ==~ /([+-]\d{4}|[Zz])/
	}
}