import ca.uhn.hl7v2.model.Segment
import core.HL7Helper
import org.hl7.fhir.r4.model.Bundle
import org.hospital.AdtPostNormalizer
import org.hospital.AdtPreNormalizer
import org.hospital.TextNormalizer

import core.merge.PatientMergeWorkflow
import core.transfer.PatientTransferWorkflow
import groovy.util.logging.Slf4j
import io.firesonic.fhir.FhirClient
import io.firesonic.script.api.FilterResult
import io.firesonic.script.api.InjectCache
import io.firesonic.script.api.InjectFhir
import io.firesonic.script.api.InjectMetrics
import io.firesonic.script.api.InjectSupport
import io.firesonic.script.api.InjectHttp
import io.firesonic.script.api.IntegrationScript
import io.firesonic.script.api.MessageEnvelope
import io.firesonic.script.api.ScriptProperty
import io.firesonic.script.api.ScriptSupport
import io.firesonic.script.api.cache.ScriptCacheFactory
import io.firesonic.script.api.http.ScriptHttpClient
import io.firesonic.script.api.http.ScriptHttpClientFactory
import io.firesonic.script.api.metrics.ScriptMetrics
import io.firesonic.script.api.validation.SemanticValidationResult
import org.hl7.fhir.r4.model.MessageHeader


@Slf4j(value = "LOG")
class Main implements IntegrationScript {

	@ScriptProperty("enable_debug")
	boolean debugEnabled = false

	@ScriptProperty("defaultTimezone")
	String defaultTimezone

	@ScriptProperty("scriptVersion")
	String scriptVersion = "unknown"

	@InjectFhir
	FhirClient fhir

	@InjectSupport
	ScriptSupport support

	@InjectCache
	ScriptCacheFactory caches

	@InjectMetrics
	ScriptMetrics metrics

	@InjectHttp
	ScriptHttpClientFactory httpClientFactory

	@Override
	void onStartup() {
		Objects.requireNonNull(defaultTimezone, "Timezone is mandatory")

		if(debugEnabled) {
			LOG.info("Custom script initialized")
			LOG.info("Default timezone: $defaultTimezone")
		}
	}

	@Override
	FilterResult shouldProcess(MessageEnvelope envelope) {
		return FilterResult.accept()
	}

	@Override
	MessageEnvelope preConvert(MessageEnvelope envelope) {
		if ("A01" == envelope.triggerEvent) {
			AdtPreNormalizer.normalize(envelope, defaultTimezone)
		}
		return envelope
	}

	@Override
	Bundle postConvert(MessageEnvelope envelope, Bundle bundle) {
		if (!bundle || !bundle.entry) return bundle
		if ("A01" == envelope.triggerEvent) {
			AdtPostNormalizer.normalize(bundle)
		}

		if ("A02" == envelope.triggerEvent) {
			bundle = new PatientTransferWorkflow(fhir).applyTransferMerge(envelope, bundle)
		}

		if ("A40" == envelope.triggerEvent) {
			return new PatientMergeWorkflow(fhir, support).applyPatientMerge(envelope, bundle)
		}

		return bundle
	}

	@Override
	void postValidation(MessageEnvelope envelope,
			Bundle bundle,
			SemanticValidationResult result) {

		if (result.isEmpty()) {
			return
		}

		LOG.info("Semantic validation completed: ${result.summary()}")

		result.errors().each {
			LOG.error("[${it.ruleId}] ${it.message}")
		}

		result.warnings().each {
			LOG.warn("[${it.ruleId}] ${it.message}")
		}

		result.info().each {
			LOG.info("[${it.ruleId}] ${it.message}")
		}
	}

	@Override
	void onShutdown() {
	}

	@Override
	String scriptVersion() {
		return scriptVersion
	}
}