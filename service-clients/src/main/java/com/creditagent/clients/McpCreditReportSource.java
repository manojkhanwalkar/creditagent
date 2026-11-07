package com.creditagent.clients;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads credit reports from the Credit Report MCP server through the LangChain4j MCP client, using
 * the {@code credit-report://ssn/{ssn}} resource.
 *
 * <p>The MCP connection is opened lazily on first use and re-opened after a transport failure, so a
 * caller can start before the MCP server is up.
 */
public class McpCreditReportSource implements CreditReportSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpCreditReportSource.class);

    /** MCP "resource not found" error code. */
    static final int RESOURCE_NOT_FOUND = -32002;
    /** Pinned so the client skips protocol auto-detection against our single-response server. */
    static final String PROTOCOL_VERSION = "2025-11-25";

    private final String mcpUrl;
    private final String clientKey;
    private final Duration timeout;
    private final JsonMapper jsonMapper;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile McpClient client;

    public McpCreditReportSource(String mcpUrl, String clientKey, Duration timeout, JsonMapper jsonMapper) {
        this.mcpUrl = Objects.requireNonNull(mcpUrl, "mcpUrl");
        this.clientKey = Objects.requireNonNull(clientKey, "clientKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public static String resourceUri(Ssn ssn) {
        return "credit-report://ssn/" + ssn.digits();
    }

    @Override
    public Optional<CreditReport> findReport(Ssn ssn) {
        McpReadResourceResult result;
        try {
            result = client().readResource(resourceUri(ssn));
        } catch (RuntimeException e) {
            McpException mcpError = findMcpException(e);
            if (mcpError != null && mcpError.errorCode() == RESOURCE_NOT_FOUND) {
                return Optional.empty();
            }
            if (mcpError == null) {
                // Transport-level failure: drop the client so the next call reconnects.
                reset();
            }
            // Message deliberately generic: the underlying exception may contain the resource URI.
            throw new ServiceUnavailableException("Credit Report MCP server request failed", e);
        }
        return result.contents().stream()
                .filter(McpTextResourceContents.class::isInstance)
                .map(McpTextResourceContents.class::cast)
                .map(McpTextResourceContents::text)
                .findFirst()
                .map(this::parse);
    }

    private CreditReport parse(String json) {
        try {
            return jsonMapper.readValue(json, CreditReport.class);
        } catch (JacksonException e) {
            throw new ServiceUnavailableException("Credit Report MCP server returned an unreadable report", e);
        }
    }

    private McpClient client() {
        McpClient current = client;
        if (current != null) {
            return current;
        }
        lock.lock();
        try {
            if (client == null) {
                StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                        .url(mcpUrl)
                        .timeout(timeout)
                        // Never enable request/response logging: payloads contain SSNs and reports.
                        .logRequests(false)
                        .logResponses(false)
                        .build();
                client = DefaultMcpClient.builder()
                        .key(clientKey)
                        .transport(transport)
                        .protocolVersion(PROTOCOL_VERSION)
                        .initializationTimeout(timeout)
                        .resourcesTimeout(timeout)
                        .build();
                log.info("Connected to Credit Report MCP server at {}", mcpUrl);
            }
            return client;
        } finally {
            lock.unlock();
        }
    }

    private void reset() {
        lock.lock();
        try {
            closeQuietly(client);
            client = null;
        } finally {
            lock.unlock();
        }
    }

    private static McpException findMcpException(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof McpException mcp) {
                return mcp;
            }
        }
        return null;
    }

    private static void closeQuietly(McpClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.debug("Ignoring error while closing MCP client", e);
        }
    }

    @Override
    public void close() {
        reset();
    }
}
