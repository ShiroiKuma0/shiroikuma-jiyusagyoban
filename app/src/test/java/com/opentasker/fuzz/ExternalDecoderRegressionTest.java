package com.opentasker.fuzz;

import static org.junit.Assert.assertFalse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/** Every checked-in fuzzer reproducer is replayed deterministically by the JVM test gate. */
public final class ExternalDecoderRegressionTest {
    @Test
    public void checkedInCorpusRemainsNonCrashing() throws IOException {
        Path corpus = corpusPath();
        assertFalse("The decoder regression corpus must not be empty", Files.notExists(corpus));
        List<Path> cases;
        try (Stream<Path> paths = Files.list(corpus)) {
            cases = paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
        }
        assertFalse("The decoder regression corpus must contain a reproducer", cases.isEmpty());
        for (Path path : cases) {
            ExternalDecoderFuzzHarness.run(Files.readAllBytes(path));
        }
    }

    private static Path corpusPath() {
        for (Path candidate : List.of(
                Path.of("src/fuzzTest/regression/external-decoders"),
                Path.of("app/src/fuzzTest/regression/external-decoders")
        )) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return Path.of("src/fuzzTest/regression/external-decoders");
    }
}
