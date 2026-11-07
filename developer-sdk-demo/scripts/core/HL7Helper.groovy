package core

import ca.uhn.hl7v2.model.*
import ca.uhn.hl7v2.util.Terser
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * General-purpose HL7 v2 helper API for customer Groovy scripts.
 *
 * This class is intentionally small, defensive, and null-tolerant. It is used
 * by preConvert/postConvert scripts to read, normalize, clear, and copy values
 * from parsed HAPI HL7 messages without exposing customer scripts to too much
 * HAPI internals.
 *
 * Main responsibilities:
 *
 * 1. Terser path helpers
 *    - get(...), set(...), clear(...), copy(...), isEmpty(...)
 *    - Best for known paths such as /PID-3-1, /PV1-19-1, /MSH-7.
 *
 * 2. Segment object helpers
 *    - safeGet(...), setFieldEncoded(...), setFieldComponent(...)
 *    - Best when scripts iterate real Segment objects using HL7SegmentScanner.
 *
 * 3. Segment clearing helpers
 *    - clearRepeatedSegments(...), removeSegments(...).
 *    - These clear segment content. They do not guarantee physical removal from
 *      HAPI's internal message metadata. Runtime mappers must skip
 *      Segment.isEmpty().
 *
 * 4. Date/time helpers
 *    - readTS(...), readDT(...), parseDate(...), resolveEventDateTime(...).
 *    - These convert common HL7 TS/DTM/DT values into FHIR/HAPI-friendly types.
 *
 * Important implementation notes for developers:
 *
 * - For repeated or nested segments, do not guess HAPI group paths. Use
 *   HL7SegmentScanner.byName(message, "OBX") or removeSegments(...).
 *
 * - Do not use setFieldEncoded(...) to write encoded composite values that
 *   contain HL7 separators such as '^'. On generic HAPI fields this may be
 *   escaped as \S\. For component writes, prefer Terser component paths:
 *
 *       HL7Helper.set(msg, "/OBR-3-1", "3510540058")
 *       HL7Helper.set(msg, "/OBR-3-2", "Labor")
 *
 * - This helper avoids throwing exceptions in customer scripts. Most methods
 *   return null/false or do nothing when a path cannot be resolved.
 */
final class HL7Helper {

	private HL7Helper() {
	}

	/**
	 * Safely gets the first segment with the given name from the message.
	 */
	static Segment getSegment(Message message, String segmentName) {
		if (message == null || isBlank(segmentName)) {
			return null
		}

		try {
			return (Segment) message.get(segmentName.trim())
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Returns true when a segment exists by HAPI Terser segment path.
	 *
	 * Supports both customer-friendly bracket syntax and Terser repetition syntax:
	 * - /DG1[0]
	 * - /DG1(0)
	 * - /PV1
	 */
	static boolean hasSegment(Message message, String segmentPath) {
		if (message == null || isBlank(segmentPath)) {
			return false
		}

		try {
			String terserPath = normalizeSegmentPathForTerser(segmentPath)
			return new Terser(message).getSegment(terserPath) != null
		} catch (Exception ignored) {
			return false
		}
	}

	/**
	 * Clears a field path or a segment path.
	 *
	 * Examples:
	 * - /PV1-20  -> clears field
	 * - /IN1     -> clears segment content
	 * - /DG1[0]  -> clears one segment repetition content
	 */
	static void clear(Message message, String path) {
		if (message == null || isBlank(path)) {
			return
		}

		if (isSegmentPath(path)) {
			clearSegment(message, path)
			return
		}

		clearField(message, path)
	}

	/**
	 * Clears a Terser field/component path by setting it to an empty value.
	 */
	static void clearField(Message message, String path) {
		set(message, path, '')
	}

	/**
	 * Clears one segment occurrence using Terser.getSegment(...).clear().
	 *
	 * Note: the segment may still appear in HAPI metadata after clearing.
	 */
	static void clearSegment(Message message, String segmentPath) {
		if (message == null || isBlank(segmentPath)) {
			return
		}

		try {
			String terserPath = normalizeSegmentPathForTerser(segmentPath)
			Structure segment = new Terser(message).getSegment(terserPath)
			segment?.clear()
		} catch (Exception ignored) {
		}
	}

	/**
	 * Clears all segments with the given name using the script-side scanner.
	 *
	 * This is safer than looping through /SEG[0], /SEG[1] Terser paths because
	 * ORU and other messages often contain nested repeating groups.
	 *
	 * Example:
	 * HL7Helper.clearRepeatedSegments(msg, "DG1")
	 */
	static void clearRepeatedSegments(Message message, String segmentName) {
		if (message == null || isBlank(segmentName)) {
			return
		}

		HL7SegmentScanner.byName(message, segmentName.trim()).each { HL7SegmentRef ref ->
			try {
				ref.segment?.clear()
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Clears all segments with the given name that match a predicate.
	 *
	 * Example:
	 * HL7Helper.removeSegments(msg, "DG1") { dg1 ->
	 *     HL7Helper.safeGet(dg1, 3, 1) == "B34.2"
	 * }
	 *
	 * This clears matching segment content; it does not physically detach the
	 * segment from HAPI's internal structure.
	 */
	static void removeSegments(Message message, String segmentName, Closure<Boolean> predicate) {
		if (message == null || isBlank(segmentName) || predicate == null) {
			return
		}

		HL7SegmentScanner.byName(message, segmentName.trim()).each { HL7SegmentRef ref ->
			Segment segment = ref.segment
			if (segment == null || segment.isEmpty()) {
				return
			}

			boolean remove = false
			try {
				remove = Boolean.TRUE == predicate.call(segment)
			} catch (Exception ignored) {
			}

			if (remove) {
				try {
					segment.clear()
				} catch (Exception ignored) {
				}
			}
		}
	}

	/**
	 * Reads a Terser path and returns a trimmed string or null.
	 */
	static String get(Message message, String path) {
		if (message == null || isBlank(path)) {
			return null
		}

		try {
			return normalize(new Terser(message).get(path))
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Sets a Terser path to the given value.
	 *
	 * Prefer this method for component writes such as /OBR-3-2 because it avoids
	 * HAPI GenericPrimitive escaping problems.
	 */
	static void set(Message message, String path, Object value) {
		if (message == null || isBlank(path)) {
			return
		}

		try {
			new Terser(message).set(path, value != null ? String.valueOf(value) : '')
		} catch (Exception ignored) {
		}
	}

	/**
	 * Returns true when a Terser path has no value.
	 */
	static boolean isEmpty(Message message, String path) {
		return get(message, path) == null
	}

	/**
	 * Copies a value from one Terser path to another.
	 */
	static void copy(Message message, String fromPath, String toPath) {
		String value = get(message, fromPath)
		if (value != null) {
			set(message, toPath, value)
		}
	}

	/**
	 * Returns true if value is a valid integer or decimal number.
	 */
	static boolean isNumeric(String value) {
		if (isBlank(value)) {
			return false
		}
		return value.trim() ==~ /[-+]?\d+(\.\d+)?/
	}

	/**
	 * Writes a simple field value into the first repetition of a segment field.
	 *
	 * Safe use cases:
	 * - OBX-2 = "NM"
	 * - OBX-11 = "F"
	 * - OBR-25 = "F"
	 *
	 * Do not use this method for encoded composite values containing '^'. HAPI may
	 * escape the caret as \S\. Use Terser component setters instead.
	 */
	static void setFieldEncoded(Segment segment, int field, String value) {
		if (segment == null || field <= 0) {
			return
		}

		try {
			Type type = segment.getField(field, 0)
			if (type == null) {
				return
			}

			if (type instanceof Varies) {
				type = ((Varies) type).getData()
				if (type == null) {
					return
				}
			}

			String v = value ?: ''

			if (type instanceof Primitive) {
				((Primitive) type).setValue(v)
				return
			}

			type.parse(v)
		} catch (Exception ignored) {
		}
	}

	/**
	 * Sets one component of a typed Composite field.
	 *
	 * If the field is not a typed Composite, this method intentionally does not
	 * try to synthesize an encoded value. For generic fields use Terser component
	 * paths or raw HL7 text normalization.
	 */
	static void setFieldComponent(Segment segment, int field, int component, String value) {
		if (segment == null || field <= 0 || component <= 0) {
			return
		}

		try {
			Type type = segment.getField(field, 0)

			if (type instanceof Varies) {
				type = ((Varies) type).getData()
			}

			if (!(type instanceof Composite)) {
				return
			}

			Composite composite = (Composite) type
			Type[] components = composite.getComponents()

			if (components == null || components.length < component) {
				return
			}

			Type componentType = components[component - 1]
			if (componentType instanceof Varies) {
				componentType = ((Varies) componentType).getData()
			}

			String v = value ?: ''

			if (componentType instanceof Primitive) {
				((Primitive) componentType).setValue(v)
				return
			}

			componentType?.parse(v)
		} catch (Exception ignored) {
		}
	}

	/**
	 * Safely gets a field value from a segment.
	 */
	static String safeGet(Segment segment, int field) {
		return safeGet(segment, field, 0, 0, 0)
	}

	/**
	 * Safely gets a component value from a segment field.
	 */
	static String safeGet(Segment segment, int field, int component) {
		return safeGet(segment, field, 0, component, 0)
	}

	/**
	 * Safely gets a field/component/subcomponent value from a segment.
	 *
	 * Repetition is zero-based. Component and subcomponent are one-based.
	 */
	static String safeGet(
			Segment segment,
			int field,
			int repetition,
			int component,
			int subComponent) {

		if (segment == null || field <= 0 || repetition < 0) {
			return null
		}

		try {
			Type type = segment.getField(field, repetition)
			return safeReadType(type, component, subComponent)
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Internal low-level Type reader.
	 *
	 * Handles Primitive, Composite and Varies safely. For generic primitive
	 * values that still contain separators, such as A^B, it performs a simple
	 * encoded component fallback.
	 */
	private static String safeReadType(Type type, int component, int subComponent) {
		if (type == null) {
			return null
		}

		try {
			if (type instanceof Varies) {
				type = ((Varies) type).getData()
				if (type == null) {
					return null
				}
			}

			if (type instanceof Primitive) {
				String value = normalize(((Primitive) type).getValue())

				if (component <= 0) {
					return value
				}

				// GenericPrimitive can contain an encoded value such as "A^B".
				// This fallback helps customer scripts read simple components even
				// when HAPI did not type the field as Composite.
				if (value != null && value.contains('^')) {
					return readEncodedComponent(value, component, subComponent)
				}

				if (component == 1 && subComponent <= 0) {
					return value
				}

				return null
			}

			if (!(type instanceof Composite)) {
				return component <= 0 ? normalize(type.encode()) : null
			}

			Composite composite = (Composite) type
			Type[] components = composite.getComponents()

			if (components == null || component <= 0 || component > components.length) {
				return null
			}

			Type componentType = components[component - 1]
			if (componentType instanceof Varies) {
				componentType = ((Varies) componentType).getData()
			}

			if (componentType == null) {
				return null
			}

			if (subComponent <= 0) {
				if (componentType instanceof Primitive) {
					return normalize(((Primitive) componentType).getValue())
				}
				return normalize(componentType.encode())
			}

			if (!(componentType instanceof Composite)) {
				return null
			}

			Composite subComposite = (Composite) componentType
			Type[] subComponents = subComposite.getComponents()

			if (subComponents == null || subComponent <= 0 || subComponent > subComponents.length) {
				return null
			}

			Type subType = subComponents[subComponent - 1]
			if (subType instanceof Varies) {
				subType = ((Varies) subType).getData()
			}

			if (subType instanceof Primitive) {
				return normalize(((Primitive) subType).getValue())
			}

			return normalize(subType?.encode())
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Reads component/subcomponent values from a simple encoded string.
	 *
	 * This is a fallback for generic primitive values where HAPI did not expose
	 * the field as a typed Composite.
	 */
	private static String readEncodedComponent(String encoded, int component, int subComponent) {
		if (isBlank(encoded) || component <= 0) {
			return null
		}

		String[] components = encoded.split(/\^/, -1)
		if (components.length < component) {
			return null
		}

		String selected = components[component - 1]
		if (subComponent <= 0) {
			return normalize(selected)
		}

		String[] subComponents = selected.split(/&/, -1)
		if (subComponents.length < subComponent) {
			return null
		}

		return normalize(subComponents[subComponent - 1])
	}

	/**
	 * Returns MSH-9.1, the HL7 message code.
	 *
	 * Example: ADT, ORU, ORM.
	 */
	static String getMessageCode(Message message) {
		Segment msh = getSegment(message, 'MSH')
		return msh != null ? safeGet(msh, 9, 1) : null
	}

	/**
	 * Returns MSH-9.2, the HL7 trigger event.
	 *
	 * Example: A01, A08, R01, O01.
	 */
	static String getTriggerEvent(Message message) {
		Segment msh = getSegment(message, 'MSH')
		return msh != null ? safeGet(msh, 9, 2) : null
	}

	/**
	 * Returns MSH-10, the message control ID.
	 *
	 * This is often useful for deterministic fallback identifiers and logging.
	 */
	static String getMessageControlId(Message message) {
		Segment msh = getSegment(message, 'MSH')
		return msh != null ? safeGet(msh, 10) : null
	}

	/**
	 * Reads an HL7 TS/DTM value and converts it to FHIR DateTimeType.
	 *
	 * Supported examples:
	 * - 2024
	 * - 202403
	 * - 20240308
	 * - 2024030810
	 * - 202403081031
	 * - 20240308103101
	 * - 20240308103101+0200
	 *
	 * Reduced precision is preserved where possible.
	 */
	static DateTimeType readTS(String raw) {
		if (isBlank(raw)) {
			return null
		}

		String value = raw.trim()

		try {
			String timezonePart = null

			if (value.matches('.*([+-]\\d{4}|Z)$')) {
				if (value.endsWith('Z')) {
					timezonePart = 'Z'
					value = value.substring(0, value.length() - 1)
				} else {
					timezonePart = value.substring(value.length() - 5)
					value = value.substring(0, value.length() - 5)
				}
			}

			String isoValue

			switch (value.length()) {
				case 4:
					isoValue = value
					break
				case 6:
					isoValue = value.substring(0, 4) + '-' + value.substring(4, 6)
					break
				case 8:
					isoValue = value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8)
					break
				case 10:
					isoValue = value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8) +
					'T' + value.substring(8, 10) + ':00:00'
					break
				case 12:
					isoValue = value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8) +
					'T' + value.substring(8, 10) + ':' + value.substring(10, 12) + ':00'
					break
				case 14:
					isoValue = value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8) +
					'T' + value.substring(8, 10) + ':' + value.substring(10, 12) + ':' + value.substring(12, 14)
					break
				default:
					if (value.matches('\\d{14}\\.\\d+')) {
						isoValue = value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8) +
								'T' + value.substring(8, 10) + ':' + value.substring(10, 12) + ':' +
								value.substring(12, 14) + value.substring(14)
					} else {
						return null
					}
			}

			if (timezonePart != null) {
				if ('Z'.equals(timezonePart)) {
					isoValue = isoValue + 'Z'
				} else {
					isoValue = isoValue + timezonePart.substring(0, 3) + ':' + timezonePart.substring(3)
				}
			}

			return new DateTimeType(isoValue)
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Reads a segment field as HL7 TS/DTM and returns FHIR DateTimeType.
	 */
	static DateTimeType getDTM(Segment segment, int field) {
		return readTS(safeGet(segment, field))
	}

	/**
	 * Reads a segment field component as HL7 TS/DTM and returns FHIR DateTimeType.
	 */
	static DateTimeType getDTM(Segment segment, int field, int component) {
		return readTS(safeGet(segment, field, component))
	}

	/**
	 * Reads an HL7 DT value and converts it to FHIR DateType.
	 *
	 * Supported precision:
	 * - yyyy
	 * - yyyyMM
	 * - yyyyMMdd
	 */
	static DateType readDT(String raw) {
		if (isBlank(raw)) {
			return null
		}

		String value = raw.trim()

		try {
			if (value.length() == 8) {
				return new DateType(value.substring(0, 4) + '-' + value.substring(4, 6) + '-' + value.substring(6, 8))
			}
			if (value.length() == 6) {
				return new DateType(value.substring(0, 4) + '-' + value.substring(4, 6))
			}
			if (value.length() == 4) {
				return new DateType(value)
			}
			return null
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Parses common HL7 date/time values into java.util.Date.
	 *
	 * Use this only when a Java Date is explicitly required. For FHIR dateTime
	 * fields, prefer readTS(...) and set the FHIR element type directly so that
	 * the original timezone offset can be preserved.
	 */
	static Date parseDate(String raw) {
		if (isBlank(raw)) {
			return null
		}

		String value = raw.trim()

		try {
			if (value.matches('.*([+-]\\d{4}|Z)$')) {
				String normalized = normalizeOffsetDateTime(value)
				OffsetDateTime offsetDateTime = OffsetDateTime.parse(normalized)
				return Date.from(offsetDateTime.toInstant())
			}

			ZoneId zoneId = ZoneId.systemDefault()

			if (value.matches('\\d{8}')) {
				LocalDate localDate = LocalDate.parse(value, DateTimeFormatter.ofPattern('yyyyMMdd'))
				return Date.from(localDate.atStartOfDay(zoneId).toInstant())
			}

			if (value.matches('\\d{12}')) {
				LocalDateTime localDateTime = LocalDateTime.parse(value, DateTimeFormatter.ofPattern('yyyyMMddHHmm'))
				return Date.from(localDateTime.atZone(zoneId).toInstant())
			}

			if (value.matches('\\d{14}')) {
				LocalDateTime localDateTime = LocalDateTime.parse(value, DateTimeFormatter.ofPattern('yyyyMMddHHmmss'))
				return Date.from(localDateTime.atZone(zoneId).toInstant())
			}

			if (value.matches('\\d{6}')) {
				LocalDate localDate = LocalDate.parse(value + '01', DateTimeFormatter.ofPattern('yyyyMMdd'))
				return Date.from(localDate.atStartOfDay(zoneId).toInstant())
			}

			return null
		} catch (Exception ignored) {
			return null
		}
	}

	/**
	 * Resolves event timestamp using EVN-2 first, then MSH-7.
	 */
	static DateTimeType resolveEventDateTime(Message message) {
		if (message == null) {
			return null
		}

		Segment evn = getSegment(message, 'EVN')
		DateTimeType eventDateTime = getDTM(evn, 2)
		if (eventDateTime != null) {
			return eventDateTime
		}

		Segment msh = getSegment(message, 'MSH')
		return getDTM(msh, 7)
	}

	/**
	 * Workflow-friendly alias for transfer logic.
	 */
	static DateTimeType resolveTransferDateTime(Message message) {
		return resolveEventDateTime(message)
	}

	/**
	 * Returns true when the supplied path points to a top-level segment rather
	 * than a field/component path.
	 *
	 * Accepted examples:
	 * - /PV1
	 * - /DG1[0]
	 * - /DG1(0)
	 */
	private static boolean isSegmentPath(String path) {
		return path?.trim() ==~ /^\/[A-Z0-9]{2,3}(?:\[(\d+)\]|\((\d+)\))?$/
	}

	/**
	 * Converts customer-friendly repetition syntax /DG1[0] to HAPI Terser
	 * repetition syntax /DG1(0).
	 */
	private static String normalizeSegmentPathForTerser(String path) {
		return path?.trim()?.replaceAll(/\[([0-9]+)]/, '($1)')
	}

	/**
	 * Internal blank check used to keep public methods null-tolerant.
	 */
	private static boolean isBlank(Object value) {
		return value == null || String.valueOf(value).trim().isEmpty()
	}

	/**
	 * Trims a string and converts blank values to null.
	 */
	private static String normalize(String value) {
		if (value == null) {
			return null
		}
		String trimmed = value.trim()
		return trimmed.isEmpty() ? null : trimmed
	}

	/**
	 * Converts compact HL7 timestamp with offset to ISO-8601 offset timestamp.
	 *
	 * Example:
	 * 20240308103101+0200 -> 2024-03-08T10:31:01+02:00
	 */
	private static String normalizeOffsetDateTime(String hl7DateTime) {
		if (hl7DateTime.endsWith('Z')) {
			if (hl7DateTime.length() == 15) {
				return hl7DateTime.substring(0, 4) + '-' +
						hl7DateTime.substring(4, 6) + '-' +
						hl7DateTime.substring(6, 8) + 'T' +
						hl7DateTime.substring(8, 10) + ':' +
						hl7DateTime.substring(10, 12) + ':' +
						hl7DateTime.substring(12, 14) + 'Z'
			}
		}

		if (hl7DateTime.matches('\\d{14}[+-]\\d{4}')) {
			return hl7DateTime.substring(0, 4) + '-' +
					hl7DateTime.substring(4, 6) + '-' +
					hl7DateTime.substring(6, 8) + 'T' +
					hl7DateTime.substring(8, 10) + ':' +
					hl7DateTime.substring(10, 12) + ':' +
					hl7DateTime.substring(12, 14) +
					hl7DateTime.substring(14, 17) + ':' +
					hl7DateTime.substring(17, 19)
		}

		return hl7DateTime
	}
}
