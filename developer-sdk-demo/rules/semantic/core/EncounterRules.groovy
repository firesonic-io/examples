package core

import groovy.transform.BaseScript
import io.firesonic.script.api.validation.dsl.SemanticIssueCollector
import io.firesonic.script.api.validation.dsl.SemanticRuleBuilder
import io.firesonic.script.api.validation.dsl.SemanticRuleContext
import io.firesonic.script.api.validation.dsl.SemanticRulesDsl
import org.hl7.fhir.r4.model.*

import static io.firesonic.script.api.validation.RuleSeverity.ERROR


@BaseScript SemanticRulesDsl dsl

rule("SEMANTIC-CORE-ENC-001") { SemanticRuleBuilder r ->

	r.severity ERROR

	// Global rule: appliesTo(...) is intentionally omitted.

	r.category "Core platform"
	r.title "Encounter identifier is required"
	r.description "Every Encounter resource must contain at least one identifier."
	r.failureMessage "Encounter does not contain an identifier"

	r.validate { SemanticRuleContext ctx, SemanticIssueCollector issues ->

		ctx.each(Encounter) { Encounter encounter ->

			if (!encounter.hasIdentifier()) {
				issues.add(r, encounter)
			}
		}
	}
}
