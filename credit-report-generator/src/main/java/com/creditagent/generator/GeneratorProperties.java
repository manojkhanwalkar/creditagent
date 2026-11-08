package com.creditagent.generator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param directory reports root; each bureau's files go in {@code <directory>/<Bureau>/} (shared
 *     with the MCP server)
 * @param hmacKey key used to derive file names; must match the MCP server's key
 * @param seedSsns SSNs to generate reports for at start-up (demo data only)
 */
@Validated
@ConfigurationProperties("credit.files")
public record GeneratorProperties(
        @NotNull Path directory, @NotBlank @Size(min = 32) String hmacKey, List<String> seedSsns) {

    public GeneratorProperties {
        seedSsns = seedSsns == null ? List.of() : List.copyOf(seedSsns);
    }

    @Override
    public String toString() {
        return "GeneratorProperties[directory=" + directory + ", hmacKey=<redacted>, seedSsns=" + seedSsns.size() + "]";
    }
}
