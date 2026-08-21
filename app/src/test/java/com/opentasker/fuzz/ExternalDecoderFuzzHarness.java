package com.opentasker.fuzz;

import com.opentasker.core.data.StructuredDataReader;
import com.opentasker.core.expressions.TemplateExpressionEngine;
import com.opentasker.core.expressions.TemplateScope;
import com.opentasker.core.transfer.MacroDroidImporter;
import com.opentasker.core.transfer.OpenTaskerBundleCodec;
import com.opentasker.core.transfer.TaskerXmlImporter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Shared bounded decoder dispatch used by the opt-in Jazzer target and its regression test.
 * Byte zero selects the decoder; structured inputs use byte one for their format and pass the
 * remaining UTF-8 text to the parser.
 */
public final class ExternalDecoderFuzzHarness {
    private static final int MAX_INPUT_BYTES = 1_000_000;

    private ExternalDecoderFuzzHarness() {
    }

    public static void run(byte[] input) {
        if (input == null || input.length == 0) return;

        int boundedLength = Math.min(input.length, MAX_INPUT_BYTES);
        byte[] bounded = input.length == boundedLength ? input : Arrays.copyOf(input, boundedLength);
        int selector = bounded[0] & 0xff;
        int route = selector >= '@' && selector <= 'D' ? selector - '@' : selector % 5;
        try {
            switch (route) {
                case 0 -> OpenTaskerBundleCodec.INSTANCE.decode(text(bounded, 1));
                case 1 -> TaskerXmlImporter.INSTANCE.parse(text(bounded, 1), "fuzz", 0L);
                case 2 -> new TemplateExpressionEngine().expand(text(bounded, 1), new TemplateScope());
                case 3 -> runStructured(bounded);
                case 4 -> MacroDroidImporter.INSTANCE.parse(text(bounded, 1), "fuzz", 0L);
                default -> throw new AssertionError("unreachable decoder route");
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // The failures these decoders raise deliberately for malformed input.
            // (kotlinx.serialization's SerializationException extends IllegalArgumentException.)
            //
            // A blanket `catch (Exception)` here defeated the point of fuzzing a pure-JVM decoder:
            // every unexpected RuntimeException - NullPointerException, IndexOutOfBoundsException,
            // ClassCastException - is exactly what Jazzer exists to surface from attacker-supplied
            // import files, and catching Exception reclassified all of them as "expected malformed
            // input". Only StackOverflowError, OOM and JVM crashes could ever be reported.
        }
    }

    private static void runStructured(byte[] input) {
        int selector = input.length > 1 ? input[1] & 3 : 0;
        String body = text(input, Math.min(2, input.length));
        switch (selector) {
            case 0 -> StructuredDataReader.INSTANCE.read("json", body, "items[0].name");
            case 1 -> StructuredDataReader.INSTANCE.read("csv", body, "*");
            case 2 -> StructuredDataReader.INSTANCE.read("xml", body, "root/item/name");
            case 3 -> StructuredDataReader.INSTANCE.read("html", body, "title");
            default -> throw new AssertionError("unreachable structured decoder route");
        }
    }

    private static String text(byte[] input, int offset) {
        return new String(input, offset, input.length - offset, StandardCharsets.UTF_8);
    }
}
