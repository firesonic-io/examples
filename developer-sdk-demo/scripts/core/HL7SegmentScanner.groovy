package core

import ca.uhn.hl7v2.model.Group
import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.model.Segment
import ca.uhn.hl7v2.model.Structure

/**
 * Utility for scanning a parsed HAPI HL7 message and returning all real segment
 * occurrences in message order.
 *
 * This class is intended for customer Groovy scripts.
 *
 * Why not just use Terser paths?
 *
 * Terser is excellent for direct known paths such as:
 *
 *   /PID-3-1
 *   /PV1-19-1
 *   /MSH-7
 *
 * But repeated/nested structures are harder. For example, in ORU_R01 messages,
 * OBR and OBX segments are usually inside nested groups. Paths such as:
 *
 *   /OBR[0]
 *   /PATIENT_RESULT/ORDER_OBSERVATION[0]/OBR
 *
 * may not resolve reliably depending on the HAPI message structure.
 *
 * HL7SegmentScanner avoids that problem by walking the parsed HAPI Group tree
 * recursively and returning a flat list of actual Segment objects.
 *
 * Typical use cases:
 *
 * 1. Iterate all OBX segments:
 *
 *    for (HL7SegmentRef ref : HL7SegmentScanner.scan(msg)) {
 *        if (ref.name == "OBX") {
 *            String value = HL7Helper.safeGet(ref.segment, 5)
 *        }
 *    }
 *
 * 2. Normalize all OBR segments:
 *
 *    HL7SegmentScanner.scan(msg)
 *        .findAll { it.name == "OBR" }
 *        .each { ref ->
 *            String code = HL7Helper.safeGet(ref.segment, 4, 1)
 *        }
 *
 * 3. Clear selected DG1 segments:
 *
 *    HL7SegmentScanner.scan(msg).each { ref ->
 *        if (ref.name == "DG1" &&
 *            HL7Helper.safeGet(ref.segment, 3, 1) == "B34.2") {
 *            ref.segment.clear()
 *        }
 *    }
 *
 * Notes:
 *
 * - The scanner does not modify the message.
 * - The returned list is a snapshot.
 * - If a segment is cleared later, Java mappers should still skip empty segments.
 * - Repetition is counted per segment name, starting from 0.
 */
final class HL7SegmentScanner {

	private HL7SegmentScanner() {
	}

	/**
	 * Scans all segments in the given HAPI message.
	 *
	 * @param message parsed HAPI HL7 message
	 * @return flat list of segment references in traversal/message order
	 */
	static List<HL7SegmentRef> scan(Message message) {

		List<HL7SegmentRef> result = []

		if (!(message instanceof Group)) {
			return result
		}

		Map<String, Integer> repetitionCounters = [:].withDefault { 0 }

		traverseGroup((Group) message, result, repetitionCounters, 0)

		return result
	}

	/**
	 * Returns all segment references with the requested segment name.
	 *
	 * Example:
	 *
	 * List<HL7SegmentRef> obxSegments = HL7SegmentScanner.byName(msg, "OBX")
	 */
	static List<HL7SegmentRef> byName(Message message, String segmentName) {

		if (!segmentName) {
			return []
		}

		return scan(message).findAll { HL7SegmentRef ref ->
			ref.name == segmentName
		}
	}

	/**
	 * Applies a closure to every segment with the requested name.
	 *
	 * Example:
	 *
	 * HL7SegmentScanner.eachSegment(msg, "OBX") { Segment obx ->
	 *     String value = HL7Helper.safeGet(obx, 5)
	 * }
	 */
	static void eachSegment(Message message, String segmentName, Closure closure) {

		if (message == null || !segmentName || closure == null) {
			return
		}

		byName(message, segmentName).each { HL7SegmentRef ref ->
			closure.call(ref.segment)
		}
	}

	private static int traverseGroup(
			Group group,
			List<HL7SegmentRef> result,
			Map<String, Integer> repetitionCounters,
			int index) {

		if (group == null) {
			return index
		}

		group.names.each { String structureName ->

			Structure[] structures

			try {
				structures = group.getAll(structureName)
			} catch (Exception ignored) {
				structures = null
			}

			if (structures == null) {
				return
			}

			structures.each { Structure structure ->

				if (structure instanceof Segment) {
					Segment segment = (Segment) structure
					String segmentName = segment.name

					int repetition = repetitionCounters[segmentName]
					repetitionCounters[segmentName] = repetition + 1

					result << new HL7SegmentRef(
							index,
							segmentName,
							segment,
							group,
							repetition)

					index++
				} else if (structure instanceof Group) {
					index = traverseGroup(
							(Group) structure,
							result,
							repetitionCounters,
							index)
				}
			}
		}

		return index
	}
}