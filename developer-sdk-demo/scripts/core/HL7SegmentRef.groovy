package core

import ca.uhn.hl7v2.model.Group
import ca.uhn.hl7v2.model.Segment

/**
 * Represents one concrete HL7 segment occurrence found inside a parsed HAPI HL7 message.
 *
 * This class is used together with {@link HL7SegmentScanner}.
 *
 * Why this exists:
 * HAPI HL7 messages are hierarchical. For example, an ORU_R01 message contains
 * OBR and OBX segments inside nested ORDER_OBSERVATION groups. Direct Terser paths
 * such as "/OBR[0]" are not always reliable across message structures.
 *
 * HL7SegmentRef gives scripts a simple, flat view of the real parsed segments:
 *
 * - index      : absolute position in the scanned message
 * - name       : segment name, for example "PID", "PV1", "OBR", "OBX"
 * - segment    : actual HAPI Segment object
 * - group      : owning HAPI Group
 * - repetition : running repetition number for this segment name, starting from 0
 *
 * Example:
 *
 * for (HL7SegmentRef ref : HL7SegmentScanner.scan(msg)) {
 *     if (ref.name == "OBX") {
 *         String code = HL7Helper.safeGet(ref.segment, 3, 1)
 *     }
 * }
 */
final class HL7SegmentRef {

	final int index
	final String name
	final Segment segment
	final Group group
	final int repetition

	HL7SegmentRef(
	int index,
	String name,
	Segment segment,
	Group group,
	int repetition) {

		this.index = index
		this.name = name
		this.segment = segment
		this.group = group
		this.repetition = repetition
	}

	/**
	 * Java-style accessor for compatibility with code written like entry.name().
	 */
	String name() {
		return name
	}

	/**
	 * Java-style accessor for compatibility with code written like entry.segment().
	 */
	Segment segment() {
		return segment
	}

	/**
	 * Java-style accessor for compatibility with code written like entry.group().
	 */
	Group group() {
		return group
	}

	/**
	 * Java-style accessor for compatibility with code written like entry.repetition().
	 */
	int repetition() {
		return repetition
	}

	@Override
	String toString() {
		return "[${index}] ${name}(${repetition})"
	}
}