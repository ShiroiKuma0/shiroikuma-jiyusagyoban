package com.opentasker.fuzz;

/** Jazzer entry point kept in the opt-in fuzzTest source set. */
public final class ExternalDecoderFuzzTarget {
    private ExternalDecoderFuzzTarget() {
    }

    public static void fuzzerTestOneInput(byte[] input) {
        ExternalDecoderFuzzHarness.run(input);
    }
}
