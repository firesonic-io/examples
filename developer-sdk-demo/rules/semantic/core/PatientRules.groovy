package core

import groovy.transform.BaseScript
import io.firesonic.script.api.validation.dsl.SemanticIssueCollector
import io.firesonic.script.api.validation.dsl.SemanticRuleBuilder
import io.firesonic.script.api.validation.dsl.SemanticRuleContext
import io.firesonic.script.api.validation.dsl.SemanticRulesDsl
import org.hl7.fhir.r4.model.*

import static io.firesonic.script.api.validation.RuleSeverity.ERROR

@BaseScript SemanticRulesDsl dsl

rule("SEMANTIC-CORE-PAT-001") { SemanticRuleBuilder r ->

	r.severity ERROR

	// Global rule: appliesTo(...) is intentionally omitted.

	r.category "Core platform"
	r.title "Patient identifier is required"
	r.description "Every Patient resource must contain at least one identifier"
	r.failureMessage "Patient does not contain an identifier"

	r.validate { SemanticRuleContext ctx, SemanticIssueCollector issues ->

		ctx.each(Patient) { Patient patient ->

			if (!patient.hasIdentifier()) {
				issues.add(r, patient)
			}
		}
	}
}

rule("SEMANTIC-CORE-PAT-002") { SemanticRuleBuilder r ->

	r.severity ERROR

	// Global rule: appliesTo(...) is intentionally omitted.

	r.category "Core platform"
	r.title "Patient first and last name are required"
	r.description "Every Patient resource must contain at least one name with both a first name and a last name."
	r.failureMessage "Patient does not contain both a first name and a last name"

	r.validate { SemanticRuleContext ctx, SemanticIssueCollector issues ->

		ctx.each(Patient) { Patient patient ->

			boolean hasCompleteName = patient.getName().any { HumanName name ->

				boolean hasFirstName = name.getGiven().any { StringType given ->
					given.hasValue() && !given.getValue().isBlank()
				}

				boolean hasLastName =
						name.hasFamily() &&
						name.getFamily() != null &&
						!name.getFamily().isBlank()

				return hasFirstName && hasLastName
			}

			if (!hasCompleteName) {
				issues.add(r, patient)
			}
		}
	}
}