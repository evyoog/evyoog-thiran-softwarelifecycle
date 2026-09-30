package com.vyoog.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * VYB-0903 (F10): a runner that does not extend {@link VerificationRunnerBase} would boot the
 * whole application with whatever datasource the environment (or a future default) supplies. This
 * fails the build on the next one somebody adds without it.
 */
class RunnersUseLocalDatabaseTest {

    @Test
    void VYB0903_AC1_everyRunnerInThisPackageExtendsTheLocalDatabaseBase() throws Exception {
        Path classes = Path.of(VerificationRunnerBase.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve("com/vyoog/api");
        List<String> runners;
        try (Stream<Path> files = Files.list(classes)) {
            runners = files.map(p -> p.getFileName().toString())
                .filter(n -> n.endsWith("Runner.class"))
                .map(n -> "com.vyoog.api." + n.substring(0, n.length() - ".class".length()))
                .sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(runners).as("the runners found").hasSizeGreaterThanOrEqualTo(16);
        for (String name : runners) {
            Class<?> runner = Class.forName(name);
            assertThat(VerificationRunnerBase.class.isAssignableFrom(runner))
                .as(name + " must extend VerificationRunnerBase").isTrue();
        }
    }
}
