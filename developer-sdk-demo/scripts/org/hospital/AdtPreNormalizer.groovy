package org.hospital

import ca.uhn.hl7v2.model.Message
import ca.uhn.hl7v2.model.Segment

import core.HL7Helper
import core.HL7TimezoneNormalizer
import io.firesonic.script.api.MessageEnvelope

final class AdtPreNormalizer {

    private AdtPreNormalizer() {}

    static MessageEnvelope normalize(MessageEnvelope envelope,
                                     String timezone) {

        if (envelope == null) {
            return null
        }

        Message msg = envelope.getHl7Message()

        if (msg == null) {
            return envelope
        }

        clearUnsupportedSegments(msg)
        normalizeAdtTimezone(msg, timezone)

        Segment pid = HL7Helper.getSegment(msg, 'PID')
        normalizePatientName(pid)
        normalizePatientAddress(pid)

        Segment pv1 = HL7Helper.getSegment(msg, 'PV1')
        normalizeDoctorName(pv1)

        return envelope
    }

    private static void clearUnsupportedSegments(Message msg) {
        HL7Helper.clearRepeatedSegments(msg, 'AL1')
        HL7Helper.clearRepeatedSegments(msg, 'IN1')
        HL7Helper.clearRepeatedSegments(msg, 'DG1')
    }

    private static void normalizePatientName(Segment pid) {

        if (pid == null) {
            return
        }

        String familyName =
                TextNormalizer.name(HL7Helper.safeGet(pid, 5, 1))

        String givenName =
                TextNormalizer.name(HL7Helper.safeGet(pid, 5, 2))

        String secondName =
                TextNormalizer.name(HL7Helper.safeGet(pid, 5, 3))

        HL7Helper.setFieldComponent(pid,
                5,
                1,
                familyName)

        HL7Helper.setFieldComponent(pid,
                5,
                2,
                givenName)

        HL7Helper.setFieldComponent(pid,
                5,
                3,
                secondName)
    }

    private static void normalizePatientAddress(Segment pid) {

        if (pid == null) {
            return
        }

        String street =
                TextNormalizer.addressLine(HL7Helper.safeGet(pid, 11, 1))

        String city =
                TextNormalizer.text(HL7Helper.safeGet(pid, 11, 3))

        HL7Helper.setFieldComponent(pid,
                11,
                1,
                street)

        HL7Helper.setFieldComponent(pid,
                11,
                3,
                city)
    }

    private static void normalizeDoctorName(Segment pv1) {

        if (pv1 == null) {
            return
        }

        String familyName =
                TextNormalizer.name(HL7Helper.safeGet(pv1, 7, 2))

        String givenName =
                TextNormalizer.name(HL7Helper.safeGet(pv1, 7, 3))

        HL7Helper.setFieldComponent(pv1,
                7,
                2,
                familyName)

        HL7Helper.setFieldComponent(pv1,
                7,
                3,
                givenName)
    }

    private static void normalizeAdtTimezone(Message msg,
                                             String defaultTimezone) {

        HL7TimezoneNormalizer.applyTimezone(msg,
                defaultTimezone,
                ['/PV1-44-1'])
    }
}