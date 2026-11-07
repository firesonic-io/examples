package core.merge

import ca.uhn.fhir.rest.gclient.ReferenceClientParam
import core.HL7Helper
import groovy.util.logging.Slf4j
import io.firesonic.exception.FSException
import io.firesonic.fhir.FhirClient
import io.firesonic.script.api.MessageEnvelope
import io.firesonic.script.api.ScriptSupport
import org.hl7.fhir.r4.model.*
import org.hl7.fhir.r4.model.Bundle.HTTPVerb

import java.time.temporal.ChronoUnit

/**
 * Default ADT^A40 (Patient Merge) workflow.
 *
 * Overview:
 * This workflow implements HL7 v2 ADT^A40 (Patient Merge) behavior on top of
 * the mechanically mapped Bundle produced by the Java handler.
 *
 * It performs identity-level merge and relinking, using the FHIR server
 * as the source of truth for existing Patient resources.
 *
 * Core behavior:
 * - Finds survivor and deprecated Patient stubs in the Bundle
 * - Resolves existing Patients on the FHIR server by identifier
 * - Applies merge semantics (active flags + Patient.link direction)
 * - Builds and posts a small atomic merge transaction
 * - Searches for resources still referencing the deprecated Patient
 * - Sends relink bundles in batches with retries
 * - Returns an empty Bundle so the main app skips its normal final POST
 *
 * Identity rule:
 * - survivor   = PID-3 (Patient Identifier List)
 * - deprecated = MRG-1 (Prior Patient Identifier List)
 *
 * Design principles:
 * - Keep Java layer strictly mechanical (no business logic)
 * - Execute merge and relinking in Groovy workflow
 * - Keep relink scope configurable and extendable
 * - Use retries for transient failures (network / FHIR server)
 * - Keep merge transaction small and atomic
 * - Ensure relink operations are chunked and retry-safe
 *
 * Important behavior:
 * - This workflow performs identity merge only.
 * - It DOES NOT modify survivor Patient demographics (name, gender, etc.).
 *
 * Rationale:
 * - HL7 A40 defines identity consolidation, not demographic survivorship
 * - Demographic reconciliation rules are business-specific
 * - Survivorship rules are organization-specific
 *
 * If demographic updates are required:
 * - implement them in custom Groovy scripts (postConvert stage)
 * - e.g. last-message-wins, trust-based, or rule-based survivorship
 *
 * Failure semantics:
 * - If merge transaction fails -> workflow fails completely
 * - If merge succeeds but relinking fails -> workflow throws exception
 *   and message can be retried via admin console
 *
 * Idempotency:
 * - Merge semantics are safe to reapply
 * - Relink operations only affect resources still referencing deprecated Patient
 * - Reprocessing the same message is safe
 *
 * Extension convention:
 * - Java handler writes canonical extension URLs
 * - Groovy workflow locates extensions by URL suffix
 * - Current extension suffixes:
 *   - patient-merge-group-number
 *   - patient-merge-role
 *
 * Scope:
 * This is the default Patient Merge workflow provided by the SDK.
 *
 * It is suitable for most HL7 v2 ADT^A40 integration scenarios.
 *
 * Developers can customize or replace this workflow to implement
 * organization-specific merge and survivorship rules.
 */
@Slf4j(value = "LOG")
class PatientMergeWorkflow {

	/**
	 * Short extension names.
	 *
	 * Java handler writes canonical URLs.
	 * This workflow matches by:
	 * - exact short name
	 * - or URL suffix ending with "/shortName"
	 */
	private static final String EXT_MERGE_GROUP_NUMBER = "patient-merge-group-number"
	private static final String EXT_MERGE_ROLE = "patient-merge-role"

	private static final String ROLE_SURVIVOR = "survivor"
	private static final String ROLE_DEPRECATED = "deprecated"

	/**
	 * Default relink scope.
	 *
	 * pathKind:
	 * - SINGLE = the element is a single Reference and can be replaced directly
	 * - LIST   = the element is a repeating List<Reference> and needs list-safe logic
	 *
	 * Notes:
	 * - this is intentionally practical, not exhaustive
	 * - developers may extend or reduce it as needed
	 * - list-valued references need dedicated logic
	 */
	private static final List<RelinkRule> RELINK_RULES = [
		new RelinkRule("Encounter", "subject", "Encounter.subject", PathKind.SINGLE),
		new RelinkRule("Condition", "subject", "Condition.subject", PathKind.SINGLE),
		new RelinkRule("Observation", "subject", "Observation.subject", PathKind.SINGLE),
		new RelinkRule("DiagnosticReport", "subject", "DiagnosticReport.subject", PathKind.SINGLE),
		new RelinkRule("Procedure", "subject", "Procedure.subject", PathKind.SINGLE),
		new RelinkRule("ServiceRequest", "subject", "ServiceRequest.subject", PathKind.SINGLE),
		new RelinkRule("AllergyIntolerance", "patient", "AllergyIntolerance.patient", PathKind.SINGLE),
		new RelinkRule("Immunization", "patient", "Immunization.patient", PathKind.SINGLE),
		new RelinkRule("Coverage", "beneficiary", "Coverage.beneficiary", PathKind.SINGLE),
		new RelinkRule("DocumentReference", "subject", "DocumentReference.subject", PathKind.SINGLE),
		new RelinkRule("RelatedPerson", "patient", "RelatedPerson.patient", PathKind.SINGLE),
		new RelinkRule("ChargeItem", "subject", "ChargeItem.subject", PathKind.SINGLE),
		new RelinkRule("Specimen", "subject", "Specimen.subject", PathKind.SINGLE),
		new RelinkRule("MedicationRequest", "subject", "MedicationRequest.subject", PathKind.SINGLE),
		new RelinkRule("MedicationAdministration", "subject", "MedicationAdministration.subject", PathKind.SINGLE),
		// the path is tricky: Appointment.participant.actor
		new RelinkRule("Appointment", "actor", "Appointment.participant.actor", PathKind.PARTICIPANT_ACTOR),
		// repeating reference lists
		new RelinkRule("Account", "subject", "Account.subject", PathKind.LIST)
	]

	private static final List<Long> RETRY_DELAYS_MS = [1000L, 2000L, 5000L, 10000L]
	private static final int PATCH_BATCH_SIZE = 25
	private static final int PAGE_SIZE = 50
	private static final int MAX_PAGES = 1000

	private final FhirClient fhir
	private final ScriptSupport support

	PatientMergeWorkflow(FhirClient fhir, ScriptSupport support) {
		this.fhir = fhir
		this.support = support
	}

	/**
	 * Applies the A40 merge workflow.
	 *
	 * Flow:
	 * 1. Collect merge groups from mapped Bundle
	 * 2. For each group:
	 *    - resolve existing survivor/deprecated Patients
	 *    - build and POST merge transaction
	 *    - relink dependent resources with retries
	 * 3. Return empty Bundle so main app skips sender.send(...)
	 *
	 * Result:
	 * - on success: merge and relink are already executed here
	 * - on failure: throws FSException
	 */
	Bundle applyPatientMerge(MessageEnvelope envelope, Bundle bundle) {
		if (bundle == null) {
			throw new FSException("[ADT^A40] Bundle is null")
		}

		Map<Integer, MergeGroup> groups = collectMergeGroups(bundle)
		if (groups.isEmpty()) {
			throw new FSException("[ADT^A40] No merge groups found in bundle")
		}

		String messageControlId = HL7Helper.getMessageControlId(envelope?.getHl7Message())
		String mergeOperator = resolveMergeOperator(envelope)

		for (MergeGroup group : groups.values().sort { it.groupNumber }) {

			if (group.survivorStub == null) {
				throw new FSException("[ADT^A40] Missing survivor Patient for group=" + group.groupNumber)
			}

			if (group.deprecatedStub == null) {
				throw new FSException("[ADT^A40] Missing deprecated Patient for group=" + group.groupNumber)
			}

			Identifier survivorIdentifier = selectBestIdentifier(group.survivorStub)
			Identifier deprecatedIdentifier = selectBestIdentifier(group.deprecatedStub)

			if (!survivorIdentifier?.getValue()) {
				throw new FSException("[ADT^A40] Survivor identifier missing for group=" + group.groupNumber)
			}

			if (!deprecatedIdentifier?.getValue()) {
				throw new FSException("[ADT^A40] Deprecated identifier missing for group=" + group.groupNumber)
			}

			Patient existingSurvivor = findPatientOrThrow(survivorIdentifier, "[ADT^A40] Survivor Patient not found")
			Patient existingDeprecated = findPatientOrThrow(deprecatedIdentifier, "[ADT^A40] Deprecated Patient not found")

			String survivorRef = toRef(existingSurvivor)
			String deprecatedRef = toRef(existingDeprecated)

			/*
			 * Simple multi-group safety.
			 *
			 * Direct read by Patient/{id} is the safest way to get the current
			 * version before building a conditional PUT.
			 *
			 * This matters when one A40 message contains multiple merge groups with the
			 * same survivor Patient. Group 1 updates the survivor, then group 2 must use
			 * the latest survivor version for If-Match.
			 */
			existingSurvivor = readCurrentPatientByRef(survivorRef)
			existingDeprecated = readCurrentPatientByRef(deprecatedRef)
			survivorRef = toRef(existingSurvivor)
			deprecatedRef = toRef(existingDeprecated)

			if (survivorRef == deprecatedRef) {
				throw new FSException("[ADT^A40] Survivor and deprecated resolve to the same Patient: " + survivorRef)
			}

			Patient mergedSurvivor = existingSurvivor.copy()
			Patient mergedDeprecated = existingDeprecated.copy()

			applyMergeSemantics(mergedSurvivor, mergedDeprecated)

			Provenance mergedProvenance = mergeProvenance(
					group.provenance,
					messageControlId,
					mergeOperator,
					envelope,
					survivorRef,
					deprecatedRef
					)

			Bundle mergeBundle = buildMergeTransactionBundle(mergedSurvivor, mergedDeprecated, mergedProvenance)

			postMergeTransaction(group.groupNumber, survivorRef, deprecatedRef, mergeBundle)
			relinkAll(group.groupNumber, deprecatedRef, survivorRef)
		}

		return new Bundle()
	}

	/**
	 * Collects all A40 merge groups from the mapped Bundle.
	 */
	private Map<Integer, MergeGroup> collectMergeGroups(Bundle bundle) {
		Map<Integer, MergeGroup> groups = [:]

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource()
			if (resource == null) {
				continue
			}

			if (resource instanceof Patient) {
				Patient patient = (Patient) resource
				MergeGroup mergeGroup = resolveMergeGroup(groups, patient)

				String role = getRole(patient)

				if (ROLE_SURVIVOR == role) {
					mergeGroup.survivorStub = patient
				} else if (ROLE_DEPRECATED == role) {
					mergeGroup.deprecatedStub = patient
				}
			} else if (resource instanceof Provenance) {
				Provenance provenance = (Provenance) resource
				MergeGroup mergeGroup = resolveMergeGroup(groups, provenance)

				mergeGroup.provenance = provenance
			}
		}

		return groups
	}

	private MergeGroup resolveMergeGroup(Map<Integer, MergeGroup> groups, DomainResource resource) {
		Integer groupNumber = resolveMergeGroupNumber(resource)
		return groups.computeIfAbsent(groupNumber) { new MergeGroup(groupNumber) }
	}

	private Identifier selectBestIdentifier(Patient patient) {
		return patient?.hasIdentifier() ? patient.getIdentifierFirstRep() : null
	}

	private Patient findPatientOrThrow(Identifier identifier, String message) {
		return fhir.findPatientByIdentifier(identifier)
				.orElseThrow {
					new FSException(message + " for identifier=" + buildIdentifierToken(identifier))
				}
	}

	/**
	 * Reads the latest Patient version directly by Patient/{id}.
	 *
	 * Why:
	 * - identifier search can be index-backed and slightly stale on some servers
	 * - direct read returns the current resource version used for If-Match
	 * - this keeps sequential multi-group A40 processing simple and safe enough
	 */
	private Patient readCurrentPatientByRef(String patientRef) {
		if (patientRef == null || patientRef.isBlank()) {
			throw new FSException("[ADT^A40] Patient reference is required for direct read")
		}

		String id = patientRef.trim()
		if (id.startsWith("Patient/")) {
			id = id.substring("Patient/".length())
		}

		try {
			return fhir.original()
					.read()
					.resource(Patient.class)
					.withId(id)
					.execute()
		} catch (Exception ex) {
			throw new FSException("[ADT^A40] Failed to read current Patient version for ref=" + patientRef, ex)
		}
	}

	private String buildIdentifierToken(Identifier identifier) {
		if (identifier == null) {
			return "<missing>"
		}

		return identifier.hasSystem()
				? "${identifier.system}|${identifier.value}"
				: identifier.value
	}

	/**
	 * Applies the core merge semantics to real existing Patients.
	 */
	private void applyMergeSemantics(Patient survivor, Patient deprecated) {
		String survivorRef = toRef(survivor)
		String deprecatedRef = toRef(deprecated)

		survivor.setActive(true)
		deprecated.setActive(false)

		upsertLink(deprecated, survivorRef, Patient.LinkType.REPLACEDBY)
		removeOppositeLink(deprecated, survivorRef, Patient.LinkType.REPLACES)

		upsertLink(survivor, deprecatedRef, Patient.LinkType.REPLACES)
		removeOppositeLink(survivor, deprecatedRef, Patient.LinkType.REPLACEDBY)
	}

	/**
	 * Builds the final Provenance for the merge.
	 *
	 * Java mapper already creates the mechanical Provenance skeleton:
	 * - recorded timestamp
	 * - activity text
	 * - optional patient-merge-group-number extension for multi-group messages
	 * - identifier-based placeholder targets
	 *
	 * This workflow only enriches execution details and replaces placeholder
	 * targets with real Patient references.
	 */
	private Provenance mergeProvenance(
			Provenance original,
			String messageControlId,
			String mergeOperator,
			MessageEnvelope envelope,
			String survivorRef,
			String deprecatedRef) {

		Provenance merged = original != null ? original.copy() : new Provenance()

		/*
		 * Do not overwrite recorded/activity/extensions here.
		 * Those are mechanical mapping outputs from the Java handler.
		 */
		if (!merged.hasActivity()) {
			merged.setActivity(new CodeableConcept().setText("patient-merge"))
		}

		merged.getTarget().clear()
		merged.addTarget(new Reference(survivorRef))
		merged.addTarget(new Reference(deprecatedRef))

		merged.getAgent().clear()
		if (mergeOperator != null && !mergeOperator.isBlank()) {
			merged.addAgent()
					.addRole(new CodeableConcept().addCoding(
					new Coding()
					.setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
					.setCode("AUT")
					.setDisplay("author (originator)")))
					.setWho(new Reference().setDisplay(mergeOperator))
		}

		merged.getEntity().clear()
		if (messageControlId != null && !messageControlId.isBlank()) {
			Identifier msgId = new Identifier()
					.setValue(messageControlId)

			String system = buildMessageIdentifierSystem(envelope)
			if (system != null) {
				msgId.setSystem(system)
			}
			merged.addEntity()
					.setRole(Provenance.ProvenanceEntityRole.SOURCE)
					.setWhat(new Reference().setIdentifier(msgId))
		}

		return merged
	}

	/**
	 * Builds the small atomic merge transaction.
	 *
	 * Robustness:
	 * - Patient PUT entries use optimistic locking via If-Match
	 */
	private Bundle buildMergeTransactionBundle(Patient survivor, Patient deprecated, Provenance provenance) {
		Bundle bundle = createBundle(Bundle.BundleType.TRANSACTION)

		Bundle.BundleEntryRequestComponent survivorReq = new Bundle.BundleEntryRequestComponent()
				.setMethod(HTTPVerb.PUT)
				.setUrl(toRef(survivor))

		String survivorVersion = survivor?.getMeta()?.getVersionId()
		if (survivorVersion != null && !survivorVersion.isBlank()) {
			survivorReq.setIfMatch('W/"' + survivorVersion + '"')
		}

		bundle.addEntry()
				.setResource(survivor)
				.setRequest(survivorReq)

		Bundle.BundleEntryRequestComponent deprecatedReq = new Bundle.BundleEntryRequestComponent()
				.setMethod(HTTPVerb.PUT)
				.setUrl(toRef(deprecated))

		String deprecatedVersion = deprecated?.getMeta()?.getVersionId()
		if (deprecatedVersion != null && !deprecatedVersion.isBlank()) {
			deprecatedReq.setIfMatch('W/"' + deprecatedVersion + '"')
		}

		bundle.addEntry()
				.setResource(deprecated)
				.setRequest(deprecatedReq)

		bundle.addEntry()
				.setResource(provenance)
				.setRequest(new Bundle.BundleEntryRequestComponent()
				.setMethod(HTTPVerb.POST)
				.setUrl('/Provenance'))

		return bundle
	}

	private void postMergeTransaction(int groupNumber, String survivorRef, String deprecatedRef, Bundle mergeBundle) {
		support.runWithRetries("[ADT^A40] Merge transaction group=" + groupNumber, RETRY_DELAYS_MS) {
			LOG.info("[ADT^A40] Posting merge transaction group={} survivor={} deprecated={}",
					groupNumber, survivorRef, deprecatedRef)

			fhir.postBundle(mergeBundle)
		}
	}

	/**
	 * Relinks all configured dependent resource types.
	 *
	 * SINGLE paths:
	 * - search ids/meta only
	 * - send PATCH bundles
	 *
	 * LIST paths:
	 * - fetch full resources
	 * - safely rewrite list in memory
	 * - send conditional PUT bundles
	 */
	private void relinkAll(int groupNumber, String deprecatedRef, String survivorRef) {
		for (RelinkRule rule : RELINK_RULES) {

			if (rule.pathKind == PathKind.SINGLE) {
				List<PatchTarget> targets = findPatchTargetsByRule(rule, deprecatedRef)
				if (targets.isEmpty()) {
					continue
				}

				List<List<PatchTarget>> chunks = support.chunk(targets, PATCH_BATCH_SIZE)

				for (int i = 0; i < chunks.size(); i++) {
					List<PatchTarget> chunk = chunks.get(i)
					Bundle patchBundle = buildPatchBatch(rule, chunk, survivorRef)

					try {
						support.runWithRetries(
								"[ADT^A40] Relink " + rule.resourceType + " batch " + (i + 1) + "/" + chunks.size() + " group=" + groupNumber,
								RETRY_DELAYS_MS) {
									LOG.info("[ADT^A40] Posting relink PATCH batch group={} resourceType={} batch={}/{} size={}",
											groupNumber, rule.resourceType, i + 1, chunks.size(), chunk.size())


									fhir.postBundle(patchBundle)
								}
					} catch (Exception ex) {
						throw new FSException(
						"[ADT^A40] Merge succeeded, but relink failed for group=" + groupNumber
						+ " resourceType=" + rule.resourceType
						+ " batch=" + (i + 1) + "/" + chunks.size()
						+ " survivor=" + survivorRef
						+ " deprecated=" + deprecatedRef
						+ " error=" + ex.getMessage(),
						ex)
					}
				}
				continue
			}

			if (rule.pathKind == PathKind.LIST || rule.pathKind == PathKind.PARTICIPANT_ACTOR) {
				List<Resource> resources = findFullResourcesByRule(rule, deprecatedRef)
				if (resources.isEmpty()) {
					continue
				}

				List<List<Resource>> chunks = support.chunk(resources, PATCH_BATCH_SIZE)

				for (int i = 0; i < chunks.size(); i++) {
					List<Resource> chunk = chunks.get(i)
					Bundle putBundle = buildListUpdateBatch(rule, chunk, deprecatedRef, survivorRef)

					if (!putBundle.hasEntry()) {
						continue
					}

					try {
						support.runWithRetries(
								"[ADT^A40] Relink " + rule.resourceType + " list batch " + (i + 1) + "/" + chunks.size() + " group=" + groupNumber,
								RETRY_DELAYS_MS) {
									LOG.info("[ADT^A40] Posting relink LIST batch group={} resourceType={} batch={}/{} size={}",
											groupNumber, rule.resourceType, i + 1, chunks.size(), putBundle.getEntry().size())

									fhir.postBundle(putBundle)
								}
					} catch (Exception ex) {
						throw new FSException(
						"[ADT^A40] Merge succeeded, but list relink failed for group=" + groupNumber
						+ " resourceType=" + rule.resourceType
						+ " batch=" + (i + 1) + "/" + chunks.size()
						+ " survivor=" + survivorRef
						+ " deprecated=" + deprecatedRef
						+ " error=" + ex.getMessage(),
						ex)
					}
				}
				continue
			}

			throw new FSException("[ADT^A40] Unsupported relink path kind: " + rule.pathKind)
		}
	}

	/**
	 * Finds lightweight patch targets for SINGLE-path relinking.
	 */
	private List<PatchTarget> findPatchTargetsByRule(RelinkRule rule, String deprecatedRef) {
		List<PatchTarget> out = []

		Bundle result = fhir.original().search()
				.forResource(rule.resourceType)
				.where(new ReferenceClientParam(rule.searchParam).hasId(deprecatedRef))
				.count(PAGE_SIZE)
				.elementsSubset("id", "meta")
				.returnBundle(Bundle.class)
				.execute()

		Set<String> seenNextUrls = new LinkedHashSet<>()

		for (int pageCount = 1; result != null && pageCount <= MAX_PAGES; pageCount++) {
			out.addAll(extractPatchTargets(result, rule.resourceType))

			def next = result.getLink("next")
			String nextUrl = next?.getUrl()

			if (nextUrl == null || nextUrl.isBlank()) {
				return out
			}

			if (!seenNextUrls.add(nextUrl)) {
				throw new FSException(
				"[ADT^A40] Pagination loop detected while searching relink targets"
				+ " resourceType=" + rule.resourceType
				+ " searchParam=" + rule.searchParam
				+ " deprecatedRef=" + deprecatedRef
				+ " repeatedNextUrl=" + nextUrl)
			}

			result = fhir.original().loadPage().next(result).execute()
		}

		throw new FSException(
		"[ADT^A40] Pagination exceeded max pages while searching relink targets"
		+ " resourceType=" + rule.resourceType
		+ " searchParam=" + rule.searchParam
		+ " deprecatedRef=" + deprecatedRef
		+ " maxPages=" + MAX_PAGES)
	}

	/**
	 * Finds full resources for LIST-path relinking.
	 */
	private List<Resource> findFullResourcesByRule(RelinkRule rule, String deprecatedRef) {
		List<Resource> out = []

		Bundle result = fhir.original().search()
				.forResource(rule.resourceType)
				.where(new ReferenceClientParam(rule.searchParam).hasId(deprecatedRef))
				.count(PAGE_SIZE)
				.returnBundle(Bundle.class)
				.execute()

		Set<String> seenNextUrls = new LinkedHashSet<>()

		for (int pageCount = 1; result != null && pageCount <= MAX_PAGES; pageCount++) {
			out.addAll(extractResources(result))

			def next = result.getLink("next")
			String nextUrl = next?.getUrl()

			if (nextUrl == null || nextUrl.isBlank()) {
				return out
			}

			if (!seenNextUrls.add(nextUrl)) {
				throw new FSException(
				"[ADT^A40] Pagination loop detected while searching list relink targets"
				+ " resourceType=" + rule.resourceType
				+ " searchParam=" + rule.searchParam
				+ " deprecatedRef=" + deprecatedRef
				+ " repeatedNextUrl=" + nextUrl)
			}

			result = fhir.original().loadPage().next(result).execute()
		}

		throw new FSException(
		"[ADT^A40] Pagination exceeded max pages while searching list relink targets"
		+ " resourceType=" + rule.resourceType
		+ " searchParam=" + rule.searchParam
		+ " deprecatedRef=" + deprecatedRef
		+ " maxPages=" + MAX_PAGES)
	}

	private List<PatchTarget> extractPatchTargets(Bundle bundle, String resourceType) {
		List<PatchTarget> out = []

		if (bundle == null || !bundle.hasEntry()) {
			return out
		}

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			Resource resource = entry.getResource()
			if (resource == null) {
				continue
			}

			String id = resource.getIdElement()?.getIdPart()
			if (id == null || id.isBlank()) {
				continue
			}

			String versionId = null
			if (resource.hasMeta() && resource.getMeta().hasVersionId()) {
				versionId = resource.getMeta().getVersionId()
			}

			out.add(new PatchTarget(resourceType, id, versionId))
		}

		return out
	}

	private List<Resource> extractResources(Bundle bundle) {
		List<Resource> out = []

		if (bundle == null || !bundle.hasEntry()) {
			return out
		}

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.getResource() != null) {
				out.add(entry.getResource())
			}
		}

		return out
	}

	/**
	 * Builds one PATCH bundle for SINGLE-path relinking.
	 */
	private Bundle buildPatchBatch(RelinkRule rule, List<PatchTarget> targets, String survivorRef) {
		Bundle bundle = createBundle(Bundle.BundleType.BATCH)

		for (PatchTarget t : targets) {
			Parameters patch = buildSingleReferencePatch(rule.patchPath, survivorRef)

			Bundle.BundleEntryRequestComponent req = new Bundle.BundleEntryRequestComponent()
					.setMethod(HTTPVerb.PATCH)
					.setUrl(t.resourceType + '/' + t.id)

			if (t.versionId != null && !t.versionId.isBlank()) {
				req.setIfMatch('W/"' + t.versionId + '"')
			}

			bundle.addEntry()
					.setResource(patch)
					.setRequest(req)
		}

		return bundle
	}

	/**
	 * Builds one conditional PUT bundle for LIST-path relinking.
	 */
	private Bundle buildListUpdateBatch(RelinkRule rule, List<Resource> resources, String deprecatedRef, String survivorRef) {
		Bundle bundle = createBundle(Bundle.BundleType.BATCH)

		for (Resource resource : resources) {
			Resource updated = rewriteListReferences(resource, rule, deprecatedRef, survivorRef)
			if (updated == null) {
				continue
			}

			String versionId = resource?.getMeta()?.getVersionId()

			Bundle.BundleEntryRequestComponent req = new Bundle.BundleEntryRequestComponent()
					.setMethod(HTTPVerb.PUT)
					.setUrl(resource.getIdElement().toUnqualifiedVersionless().getValue())

			if (versionId != null && !versionId.isBlank()) {
				req.setIfMatch('W/"' + versionId + '"')
			}

			bundle.addEntry()
					.setResource(updated)
					.setRequest(req)
		}

		return bundle
	}

	private Parameters buildSingleReferencePatch(String path, String survivorRef) {
		Parameters patch = new Parameters()

		Parameters.ParametersParameterComponent op = patch.addParameter()
		op.setName("operation")
		op.addPart().setName("type").setValue(new CodeType("replace"))
		op.addPart().setName("path").setValue(new StringType(path))
		op.addPart().setName("value").setValue(new Reference(survivorRef))

		return patch
	}

	/**
	 * Rewrites list-valued patient references in a full resource.
	 *
	 * Current supported LIST resources:
	 * - Account.subject
	 *
	 * Behavior:
	 * - remove deprecated reference
	 * - add survivor reference only if not already present
	 * - preserve other entries
	 * - return null if no effective change
	 */
	private Resource rewriteListReferences(Resource resource, RelinkRule rule, String deprecatedRef, String survivorRef) {
		if (resource instanceof Account && rule.patchPath == "Account.subject") {
			Account updated = ((Account) resource).copy()

			List<Reference> existing = new ArrayList<>(updated.getSubject())
			List<Reference> rewritten = []
			boolean removedDeprecated = false
			boolean survivorAlreadyPresent = false

			for (Reference ref : existing) {
				String refValue = normalizeReferenceValue(ref)

				if (refValue == deprecatedRef) {
					removedDeprecated = true
					continue
				}

				if (refValue == survivorRef) {
					survivorAlreadyPresent = true
				}

				rewritten.add(ref)
			}

			if (removedDeprecated && !survivorAlreadyPresent) {
				rewritten.add(new Reference(survivorRef))
			}

			if (!removedDeprecated) {
				return null
			}

			updated.getSubject().clear()
			updated.setSubject(rewritten)
			return updated
		} else if (resource instanceof Appointment && rule.pathKind == PathKind.PARTICIPANT_ACTOR) {
			Appointment updated = ((Appointment) resource).copy()
			boolean changed = false

			updated.getParticipant().each { participant ->
				Reference actor = participant.getActor()
				if (normalizeReferenceValue(actor) == deprecatedRef) {
					participant.setActor(new Reference(survivorRef))
					changed = true
				}
			}

			return changed ? updated : null
		}

		throw new FSException("[ADT^A40] Full-resource relink is not implemented for "
		+ rule.resourceType + " path=" + rule.patchPath)
	}

	private String normalizeReferenceValue(Reference ref) {
		String value = ref?.getReference()
		return value != null && !value.isBlank() ? value.trim() : null
	}

	/**
	 * Builds a mechanical identifier namespace for the originating HL7 message.
	 *
	 * Format:
	 *   <Sending Application>-<Sending Facility>
	 *
	 * Example:
	 *   ADT_SYS-Ein1
	 *
	 * Spaces are replaced with '-'.
	 */
	private String buildMessageIdentifierSystem(MessageEnvelope envelope) {
		def msg = envelope?.getHl7Message()
		if (msg == null) {
			return null
		}

		String app = normalizeSystemPart(HL7Helper.get(msg, "/MSH-3"))
		String facility = normalizeSystemPart(HL7Helper.get(msg, "/MSH-4"))

		if (app != null && facility != null) {
			return app + "-" + facility
		}

		return app != null ? app : facility
	}

	private static String normalizeSystemPart(String value) {
		if (value == null || value.isBlank()) {
			return null
		}

		return value.trim().replaceAll("\\s+", "-")
	}

	/**
	 * Resolves merge operator from EVN-5 when present.
	 *
	 * For now we preserve the raw display value only.
	 */
	private String resolveMergeOperator(MessageEnvelope envelope) {
		def message = envelope?.getHl7Message()
		if (message == null) {
			return null
		}

		def evn = HL7Helper.getSegment(message, 'EVN')
		if (evn == null) {
			return null
		}

		String raw = HL7Helper.safeGet(evn, 5)
		return raw != null && !raw.isBlank() ? raw : null
	}

	private String toRef(Patient patient) {
		String ref = patient?.getIdElement()?.toUnqualifiedVersionless()?.getValue()
		if (ref == null || ref.isBlank()) {
			throw new FSException("[ADT^A40] Patient.id is required")
		}
		return ref
	}

	private void upsertLink(Patient patient, String otherRef, Patient.LinkType linkType) {
		for (Patient.PatientLinkComponent link : patient.getLink()) {
			if (link.hasOther() && otherRef == link.getOther().getReference() && linkType == link.getType()) {
				return
			}
		}

		patient.addLink()
				.setOther(new Reference(otherRef))
				.setType(linkType)
	}

	private void removeOppositeLink(Patient patient, String otherRef, Patient.LinkType wrongType) {
		Iterator<Patient.PatientLinkComponent> it = patient.getLink().iterator()

		while (it.hasNext()) {
			Patient.PatientLinkComponent link = it.next()
			if (link.hasOther() && otherRef == link.getOther().getReference() && wrongType == link.getType()) {
				it.remove()
			}
		}
	}

	private Integer resolveMergeGroupNumber(DomainResource resource) {
		IntegerType value = resource.getExtension()
				.find { matchesExt(it, EXT_MERGE_GROUP_NUMBER) }
				?.getValue() as IntegerType

		return value?.getValue() ?: 1
	}

	private String getRole(Patient patient) {
		CodeType value = patient.getExtension()
				.find { matchesExt(it, EXT_MERGE_ROLE) }
				?.getValue() as CodeType

		return value?.getCode()
	}

	private boolean matchesExt(Extension ext, String shortName) {
		String url = ext?.getUrl()
		if (url == null) {
			return false
		}
		return url == shortName || url.endsWith('/' + shortName)
	}

	private static Bundle createBundle(Bundle.BundleType type) {
		Bundle bundle = new Bundle()
		bundle.setType(type)

		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
		bundle.setTimestampElement(new InstantType(now.toString()))

		return bundle
	}

	private static final class MergeGroup {
		final int groupNumber
		Patient survivorStub
		Patient deprecatedStub
		Provenance provenance

		MergeGroup(int groupNumber) {
			this.groupNumber = groupNumber
		}
	}

	private static final class PatchTarget {
		final String resourceType
		final String id
		final String versionId

		PatchTarget(String resourceType, String id, String versionId) {
			this.resourceType = resourceType
			this.id = id
			this.versionId = versionId
		}
	}

	/**
	 * Default relink scope.
	 *
	 * pathKind:
	 * - SINGLE            = direct single Reference, updated by FHIRPath PATCH
	 * - LIST              = repeating List<Reference>, updated by full-resource PUT
	 * - PARTICIPANT_ACTOR = Appointment.participant.actor, updated by full-resource PUT
	 */
	private static enum PathKind {
		SINGLE,
		LIST,
		PARTICIPANT_ACTOR
	}

	private static final class RelinkRule {
		final String resourceType
		final String searchParam
		final String patchPath
		final PathKind pathKind

		RelinkRule(String resourceType, String searchParam, String patchPath, PathKind pathKind) {
			this.resourceType = resourceType
			this.searchParam = searchParam
			this.patchPath = patchPath
			this.pathKind = pathKind
		}
	}
}