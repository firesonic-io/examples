package core.transfer

import core.HL7Helper
import io.firesonic.exception.FSException
import io.firesonic.fhir.FhirClient
import io.firesonic.script.api.MessageEnvelope
import org.hl7.fhir.r4.model.*

/**
 * Default ADT^A02 (Patient Transfer) workflow.
 *
 * Overview:
 * This workflow demonstrates a simple and extensible approach to handling
 * patient transfers (A02) using Encounter.location history.
 *
 * Core behavior:
 * - Finds the existing Encounter by identifier (prefer PV1-19 Visit Number)
 * - Takes the newly mapped Encounter from the Bundle
 * - Compares location rows by Reference.reference only
 * - Preserves unchanged locations
 * - Closes removed locations (status=completed, period.end set)
 * - Adds new locations (status=active, period.start set)
 *
 * Identity rule:
 * - Location identity is based ONLY on Reference.reference
 * - No semantic comparison is performed
 * - No Location resource resolution is performed
 *
 * Example:
 * Old:
 *   WARD01 (#1)
 *   BED01  (#2)
 *
 * New:
 *   WARD01 (#1)
 *   BED02  (#3)
 *
 * Result:
 *   WARD01 stays active
 *   BED01 is completed
 *   BED02 is activated
 *
 * Design principles:
 * - Keep logic simple and predictable
 * - Avoid assumptions about Location modeling
 * - Work with both:
 *     - contained references (#...)
 *     - external references (Location/{uuid})
 *
 * Extensibility:
 * Developers are expected to customize this workflow as needed:
 * - Replace contained locations with real UUID references
 * - Implement semantic location comparison
 * - Introduce sub-encounters or partOf relationships
 * - Add deduplication or validation rules
 *
 * This class is intended as a reference implementation and starting point,
 * not a universal solution.
 */
class PatientTransferWorkflow {

	private final FhirClient fhir

	PatientTransferWorkflow(FhirClient fhir) {
		this.fhir = fhir
	}

	/**
	 * Applies the default A02 transfer merge logic to the transaction Bundle.
	 *
	 * Flow:
	 * 1. Find mapped Encounter in Bundle
	 * 2. Select best Encounter identifier (prefer VN)
	 * 3. Find existing Encounter on the FHIR server
	 * 4. Resolve transfer timestamp from HL7
	 * 5. Merge location history
	 * 6. Replace mapped Encounter in the Bundle
	 *
	 * @param envelope current message envelope
	 * @param bundle transaction Bundle produced by the Java mapper
	 * @return updated Bundle with merged Encounter
	 */
	Bundle applyTransferMerge(MessageEnvelope envelope, Bundle bundle) {
		Encounter mappedEncounter = findEncounter(bundle)
		if (mappedEncounter == null) {
			throw new FSException("[ADT^A02] No Encounter found in bundle")
		}

		Identifier encounterIdentifier = selectBestIdentifier(mappedEncounter)
		if (!encounterIdentifier?.getValue()) {
			throw new FSException("[ADT^A02] Encounter.identifier missing value (PV1-19 expected)")
		}

		String identifierToken = buildIdentifierToken(encounterIdentifier)

		Encounter existingEncounter = fhir.findEncounterByIdentifier(encounterIdentifier)
				.orElseThrow {
					new FSException("[ADT^A02] Existing Encounter not found for identifier=" + identifierToken)
				}

		DateTimeType transferDateTime = HL7Helper.resolveTransferDateTime(envelope.getHl7Message())

		Encounter mergedEncounter = mergeEncounter(existingEncounter, mappedEncounter, transferDateTime)
		replaceEncounter(bundle, mappedEncounter, mergedEncounter)

		return bundle
	}

	/**
	 * Finds the first Encounter resource in the transaction Bundle.
	 *
	 * @param bundle transaction Bundle
	 * @return mapped Encounter or null
	 */
	private Encounter findEncounter(Bundle bundle) {
		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.getResource() instanceof Encounter) {
				return (Encounter) entry.getResource()
			}
		}
		return null
	}

	/**
	 * Selects the best identifier for Encounter lookup.
	 *
	 * Rule:
	 * - prefer VN (Visit Number)
	 * - otherwise use first identifier
	 *
	 * @param encounter mapped Encounter
	 * @return selected identifier or null
	 */
	private Identifier selectBestIdentifier(Encounter encounter) {

		List<Identifier> identifiers = encounter.getIdentifier()
		if (identifiers == null || identifiers.isEmpty()) {
			return null
		}

		for (Identifier identifier : identifiers) {
			if (identifier.getType() != null) {
				for (Coding coding : identifier.getType().getCoding()) {
					if ("http://terminology.hl7.org/CodeSystem/v2-0203".equals(coding.getSystem())
							&& "VN".equals(coding.getCode())) {
						return identifier
					}
				}
			}
		}

		return encounter.getIdentifierFirstRep()
	}

	/**
	 * Builds the FHIR identifier token used for Encounter lookup.
	 *
	 * Output:
	 * - "system|value" if system exists
	 * - "value" otherwise
	 *
	 * @param identifier Encounter identifier
	 * @return FHIR identifier token
	 */
	private String buildIdentifierToken(Identifier identifier) {
		if (identifier.getSystem() != null && !identifier.getSystem().trim().isEmpty()) {
			return identifier.getSystem() + '|' + identifier.getValue()
		}
		return identifier.getValue()
	}

	/**
	 * Merges existing Encounter state with the newly mapped Encounter.
	 *
	 * Location merge rule:
	 * - if an existing location key is present in the mapped Encounter -> keep it unchanged
	 * - if an existing location key is missing in the mapped Encounter -> close it
	 * - if a mapped location key is not present in the existing Encounter -> add it as ACTIVE
	 *
	 * This prevents unnecessary close/reopen of the same ward during a bed-only move.
	 *
	 * @param existingEncounter Encounter currently stored on the FHIR server
	 * @param mappedEncounter Encounter produced by the Java mapper
	 * @param transferDateTime transfer timestamp (EVN-2, fallback MSH-7)
	 * @return merged Encounter
	 */
	private Encounter mergeEncounter(
			Encounter existingEncounter,
			Encounter mappedEncounter,
			DateTimeType transferDateTime) {

		Encounter mergedEncounter = existingEncounter.copy()
		mergedEncounter.setStatus(Encounter.EncounterStatus.INPROGRESS)

		List<Encounter.EncounterLocationComponent> existingLocationCopies = copyLocations(existingEncounter.getLocation())
		List<Encounter.EncounterLocationComponent> mappedLocationCopies = copyLocations(mappedEncounter.getLocation())

		Map<String, Encounter.EncounterLocationComponent> existingByKey = indexLocationsByKey(existingLocationCopies)
		Map<String, Encounter.EncounterLocationComponent> mappedByKey = indexLocationsByKey(mappedLocationCopies)

		List<Encounter.EncounterLocationComponent> finalLocations = new ArrayList<>()

		// Keep unchanged rows, close removed rows.
		for (Map.Entry<String, Encounter.EncounterLocationComponent> entry : existingByKey.entrySet()) {
			String key = entry.getKey()
			Encounter.EncounterLocationComponent existingLocation = entry.getValue()

			if (mappedByKey.containsKey(key)) {
				// Same location still present in mapped Encounter.
				// Keep existing row unchanged. Do not close/reopen it.
				finalLocations.add(existingLocation)
			} else {
				// Existing location disappeared from mapped Encounter.
				// Close it as part of transfer.
				closeLocation(existingLocation, transferDateTime)
				finalLocations.add(existingLocation)
			}
		}

		// Add only truly new rows.
		for (Map.Entry<String, Encounter.EncounterLocationComponent> entry : mappedByKey.entrySet()) {
			String key = entry.getKey()
			Encounter.EncounterLocationComponent mappedLocation = entry.getValue()

			if (!existingByKey.containsKey(key)) {
				activateLocation(mappedLocation, transferDateTime)
				finalLocations.add(mappedLocation)
			}
		}

		mergedEncounter.setLocation(finalLocations)

		// Keep mapped contained resources as the default/demo behavior.
		// Developers may replace contained refs with real UUID refs before this workflow runs.
		mergedEncounter.setContained(
				mergeContained(existingEncounter.getContained(), mappedEncounter.getContained())
				)

		return mergedEncounter
	}

	/**
	 * Creates deep copies of Encounter.location rows.
	 *
	 * @param locations source location rows
	 * @return copied rows
	 */
	private List<Encounter.EncounterLocationComponent> copyLocations(
			List<Encounter.EncounterLocationComponent> locations) {

		List<Encounter.EncounterLocationComponent> copies = new ArrayList<>()

		if (locations == null) {
			return copies
		}

		for (Encounter.EncounterLocationComponent location : locations) {
			copies.add(location.copy())
		}

		return copies
	}

	/**
	 * Creates deep copies of contained resources.
	 *
	 * @param contained source contained resources
	 * @return copied contained resources
	 */
	private List<Resource> copyContained(List<Resource> contained) {
		List<Resource> copies = new ArrayList<>()

		if (contained == null) {
			return copies
		}

		for (Resource resource : contained) {
			copies.add(resource.copy())
		}

		return copies
	}

	/**
	 * Builds an index of location rows using a simple stable key.
	 *
	 * Key format:
	 * - reference + "|" + physicalType.code
	 *
	 * Why:
	 * - this is simple and demo-friendly
	 * - it lets us distinguish ward vs bed
	 * - it prevents reopening the same ward during a bed-only move
	 *
	 * Notes:
	 * - if the same key appears more than once, the last one wins
	 * - for a demo/default workflow this is acceptable
	 *
	 * @param locations Encounter.location rows
	 * @return map keyed by simple location key
	 */
	private Map<String, Encounter.EncounterLocationComponent> indexLocationsByKey(
			List<Encounter.EncounterLocationComponent> locations) {

		Map<String, Encounter.EncounterLocationComponent> result = new HashMap<>()

		for (Encounter.EncounterLocationComponent location : locations) {
			String key = buildLocationKey(location)
			result.put(key, location)
		}

		return result
	}

	/**
	 * Builds a simple comparison key for an Encounter.location row.
	 *
	 * Identity rule:
	 * - based only on Reference.reference
	 *
	 * This is intentional for the default workflow:
	 * - no Location resolution is performed
	 * - no semantic comparison is attempted
	 * - reference equality defines location identity
	 *
	 * Examples:
	 * - "#1"
	 * - "#2"
	 * - "Location/uuid-123"
	 *
	 * @param location Encounter.location row
	 * @return comparison key
	 */
	private String buildLocationKey(Encounter.EncounterLocationComponent location) {
		String reference = null

		if (location != null && location.getLocation() != null) {
			reference = normalizeString(location.getLocation().getReference())
		}

		if (reference == null) {
			return "NULL"
		}

		return reference
	}

	/**
	 * Closes an existing location row.
	 *
	 * Behavior:
	 * - set status = COMPLETED
	 * - ensure period exists
	 * - set period.end if transfer timestamp is available
	 *
	 * @param location location row to close
	 * @param transferDateTime transfer timestamp
	 */
	private void closeLocation(
			Encounter.EncounterLocationComponent location,
			DateTimeType transferDateTime) {

		location.setStatus(Encounter.EncounterLocationStatus.COMPLETED)

		Period period = location.getPeriod()
		if (period == null) {
			period = new Period()
			location.setPeriod(period)
		}

		if (transferDateTime != null) {
			period.setEndElement(transferDateTime.copy())
		}
	}

	/**
	 * Activates a newly added location row.
	 *
	 * Behavior:
	 * - set status = ACTIVE
	 * - ensure period exists
	 * - set period.start if transfer timestamp is available
	 *
	 * @param location location row to activate
	 * @param transferDateTime transfer timestamp
	 */
	private void activateLocation(
			Encounter.EncounterLocationComponent location,
			DateTimeType transferDateTime) {

		location.setStatus(Encounter.EncounterLocationStatus.ACTIVE)

		Period period = location.getPeriod()
		if (period == null) {
			period = new Period()
			location.setPeriod(period)
		}

		if (transferDateTime != null) {
			period.setStartElement(transferDateTime.copy())
		}
	}

	/**
	 * Normalizes a nullable string by trimming it.
	 *
	 * @param value source string
	 * @return trimmed string or null
	 */
	private String normalizeString(String value) {
		if (value == null) {
			return null
		}

		String trimmed = value.trim()
		return trimmed.isEmpty() ? null : trimmed
	}

	/**
	 * Replaces the mapped Encounter resource inside the transaction Bundle.
	 *
	 * The Bundle entry is preserved; only the resource instance is replaced.
	 *
	 * @param bundle transaction Bundle
	 * @param mappedEncounter original mapped Encounter
	 * @param mergedEncounter merged Encounter
	 */
	private void replaceEncounter(
			Bundle bundle,
			Encounter mappedEncounter,
			Encounter mergedEncounter) {

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.getResource() == mappedEncounter) {
				entry.setResource(mergedEncounter)
				return
			}
		}

		throw new FSException("[ADT^A02] Encounter entry not found in bundle")
	}

	/**
	 * Merges existing and mapped contained resources.
	 *
	 * Default demo behavior:
	 * - keep all existing contained resources
	 * - add mapped contained resources if their contained id is not already present
	 *
	 * This preserves old contained references such as #1, #2, #3 while still
	 * allowing newly mapped contained Locations to be added.
	 */
	private List<Resource> mergeContained(
			List<Resource> existingContained,
			List<Resource> mappedContained) {

		List<Resource> mergedContained = new ArrayList<>()

		if (existingContained != null) {
			for (Resource resource : existingContained) {
				mergedContained.add(resource.copy())
			}
		}

		if (mappedContained != null) {
			for (Resource resource : mappedContained) {
				if (!containsContainedId(mergedContained, resource)) {
					mergedContained.add(resource.copy())
				}
			}
		}

		return mergedContained
	}

	/**
	 * Checks whether the given contained resource id already exists in the list.
	 */
	private boolean containsContainedId(List<Resource> containedResources, Resource candidate) {
		String candidateId = candidate.getIdElement()?.getIdPart()
		if (candidateId == null || candidateId.isBlank()) {
			return false
		}

		for (Resource resource : containedResources) {
			String existingId = resource.getIdElement()?.getIdPart()
			if (candidateId.equals(existingId)) {
				return true
			}
		}

		return false
	}
}