package dev.justbuild.provider;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliModelTest {
    @TempDir Path dir;

    @Test void acceptsModelIdentifiersAndRejectsOptionsAndShellSyntax() {
        for (String model : List.of("", "sonnet", "sonnet[1m]", "gpt-6.1-sol", "vendor/model:tag", "grok-build-superfast"))
            assertTrue(CliModel.valid(model), model);
        for (String model : List.of("--help", "model & whoami", "model|whoami", "%PATH%", "$(whoami)", "a\nb", "a".repeat(201)))
            assertFalse(CliModel.valid(model), model);
    }

    @Test void batchLaunchersAreRefusedBeforeProcessCreation() {
        for (String name : List.of("provider.cmd", "provider.BAT")) {
            var error = assertThrows(ProviderException.class, () -> ProcessRunner.run(List.of(dir.resolve(name).toString()),
                    "", dir, Duration.ofSeconds(1), new CancellationToken()));
            assertTrue(error.getMessage().contains("native executable"));
        }
    }

    @Test void everyCliRejectsUnsafeModelsBeforeLookingForAnExecutable() {
        var request = new PlanRequest("s", "u", "{}", "model & whoami", Duration.ofSeconds(1));
        for (BuildProvider provider : List.of(new ClaudeCliProvider("missing"), new CodexCliProvider("missing"),
                new GrokCliProvider("missing"))) {
            var error = assertThrows(ProviderException.class, () -> provider.complete(request, new CancellationToken(), new RecordingListener()));
            assertTrue(error.getMessage().startsWith("Invalid CLI model name"));
        }
    }
}
