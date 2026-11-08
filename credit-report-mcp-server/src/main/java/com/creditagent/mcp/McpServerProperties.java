package com.creditagent.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param directory reports root holding {@code Equifax/}, {@code Experian/} and {@code TransUnion/}
 *     (the "file system resource")
 * @param hmacKey must match the credit report generator's key
 * @param allowedOrigins browser origins allowed to call {@code /mcp}; requests without an Origin
 *     header (server-to-server) are always allowed. Guards against DNS-rebinding.
 */
@Validated
@ConfigurationProperties("credit.files")
public record McpServerProperties(
        @NotNull Path directory, @NotBlank @Size(min = 32) String hmacKey, List<String> allowedOrigins) {

    public McpServerProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

    @Override
    public String toString() {
        return "McpServerProperties[directory=" + directory + ", hmacKey=<redacted>, allowedOrigins=" + allowedOrigins + "]";
    }
}
