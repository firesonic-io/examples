# FireSonic Developer SDK Demo

Sample integration from the **FireSonic in Action | Developer SDK Overview & Live Demo** video.

Demonstrates how to customize an **HL7 v2.3 ADT A01** admission message in Groovy, convert it with FireSonic to a **FHIR R4 transaction Bundle**, and inspect the generated resources in **IntelliJ IDEA**.

## Requirements

- **FireSonic Developer SDK 0.1.0**, installed and configured separately ([firesonic.io](https://firesonic.io))
- Java and IntelliJ IDEA configured as required by your SDK distribution
- A valid Developer SDK license and SDK configuration

This repository contains example source files, **not** the complete SDK, its dependencies, or a standalone Maven project.

## What's included

- `input/01-adt-a01-admission.hl7` – synthetic HL7 v2.3 admission message
- `scripts/Main.groovy` – integration hooks for pre-conversion, post-conversion, and validation feedback
- `scripts/org/hospital/` – sample ADT pre/post normalizers and text normalization helpers
- `scripts/core/` – shared helper and workflow scripts used by the provided `Main.groovy`
- `scripts/script.properties` – example script settings, including the default timezone
- `rules/semantic/core/` – sample Patient and Encounter semantic rules

## Run the example

1. Download and configure the **FireSonic Developer SDK 0.1.0**. Open its Maven project in IntelliJ IDEA.
2. Back up any existing `scripts/` and `rules/` files in your SDK project.
3. Copy this example's **`scripts/` and `rules/` contents** into the corresponding directories in your SDK project. Review overwrites, especially `Main.groovy`, `script.properties`, and core scripts.
4. Set the SDK input to `input/01-adt-a01-admission.hl7` (copy the file to the input location configured in `config/sdk.properties`). Configure the SDK output mode as desired; `folder` is convenient for local inspection.
5. Check `scripts/script.properties` and adjust `defaultTimezone` for your test scenario. The supplied sample uses `-0500`.
6. Run the SDK from IntelliJ IDEA using the runner provided by your SDK distribution. Inspect logs and the generated FHIR R4 Bundle in the configured output directory.

The `A01` hooks normalize names and address text, remove selected HL7 segments before conversion, remove the FHIR `MessageHeader` entry afterward, and add a US Core Patient profile URL to the Patient resource. **Adding a profile URL does not establish US Core conformance.**

`Main.groovy` also references A02 transfer and A40 merge workflows; those workflows are **not** exercised by the included A01 message and may require a reachable FHIR server and appropriate test resources.

## Notes

- Use **synthetic data only**. Do not commit SDK license files, tokens, or real patient information.
- The included `.hl7` file is LF-delimited in this snapshot. Some HL7 tools expect CR (`\r`) segment delimiters; convert delimiters if your runner requires standard ER7 framing.
- Automated Spock tests are **not included** in this snapshot.
- These files are provided as a learning example. Review all mappings and validation behavior before using similar logic in production.

## Issues

When reporting a problem, include the SDK version, affected script/rule, synthetic input, expected result, and actual result (without credentials or protected health information).
