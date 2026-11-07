package org.hospital

import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.MessageHeader
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource

final class AdtPostNormalizer {

    private static final String US_CORE_PATIENT_PROFILE =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"

    private AdtPostNormalizer() {}

    static void normalize(Bundle bundle) {

        if (bundle == null || !bundle.hasEntry()) {
            return
        }

        bundle.getEntry().removeAll { Bundle.BundleEntryComponent entry ->

            Resource resource = entry.getResource()

            if (resource instanceof MessageHeader) {
                return true
            }

            if (resource instanceof Patient) {
                addPatientProfile((Patient) resource)
            }

            return false
        }
    }

    private static void addPatientProfile(Patient patient) {

        if (patient == null) {
            return
        }

        boolean alreadyPresent = patient.getMeta()
                .getProfile()
                .any { canonical -> canonical.getValue() == US_CORE_PATIENT_PROFILE
                }

        if (!alreadyPresent) {
            patient.getMeta().addProfile(US_CORE_PATIENT_PROFILE)
        }
    }
}