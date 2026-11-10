package com.creditagent.mcp;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The MCP endpoint's own settings. Where reports are read from is {@code credit.reports.*}
 * ({@link com.creditagent.reports.CreditReportsProperties}).
 *
 * @param allowedOrigins browser origins allowed to call {@code /mcp}; requests without an Origin
 *     header (server-to-server) are always allowed. Guards against DNS-rebinding.
 */
@ConfigurationProperties("credit.mcp")
public record McpServerProperties(List<String> allowedOrigins) {

    public McpServerProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
