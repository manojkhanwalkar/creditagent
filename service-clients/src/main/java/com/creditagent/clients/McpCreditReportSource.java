package com.creditagent.clients;

import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
 *
 * <p>Retries (with the backoff of {@link RetrySettings}) happen only when the MCP server did no work:
 * the request never arrived (connection refused, unknown host), or it was refused with 429, 502 or
 * 503. A refusal is retried after {@link #REFUSAL_PAUSE}, since the server's {@code Retry-After}
 * is not visible through the MCP client. Not retried:
 * <ul>
 *   <li>a timeout: the server may still be working, and it already retries its own call to NRG, so
 *       a second read would only double the load during the slowdown. Give this client a
 *       {@code timeout} longer than the server's NRG retry budget instead;
 *   <li>an MCP error answer (not found, internal error), for the same reason;
 *   <li>anything else, such as a bug in this process.
 * </ul>
 *
 * <p>{@code timeout} bounds a whole look-up: reconnecting, every try and the waits between them.
 * The MCP client times the initialisation and the read separately, so without this bound a look-up
 * that reconnects could take twice the timeout, and more with retried connection failures.
 */
public class McpCreditReportSource implements CreditReportSource, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpCreditReportSource.class);

    /** MCP "resource not found" error code. */
    static final int RESOURCE_NOT_FOUND = -32002;
    /** Statuses that mean the MCP server refused the read before doing any work. */
    static final java.util.Set<Integer> REFUSED_STATUSES = java.util.Set.of(429, 502, 503);
    /** Wait before retrying a refusal: the smallest Retry-After the services' limit filter sends. */
    static final Duration REFUSAL_PAUSE = Duration.ofSeconds(1);

    /** Pinned so the client skips protocol auto-detection against our single-response server. */
    static final String PROTOCOL_VERSION = "2025-11-25";

    private final String mcpUrl;
    private final String clientKey;
    private final Duration timeout;
    private final RetrySettings retry;
    private final Duration refusalPause;
    private final JsonMapper jsonMapper;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile McpClient client;

    /**
     * @param timeout longest wait for one look-up, reconnects and retries included; make it longer than
     *     the MCP server's own retry budget for NRG, or this client gives up on reads the server is
     *     still completing
     */
    public McpCreditReportSource(
            String mcpUrl, String clientKey, Duration timeout, RetrySettings retry, JsonMapper jsonMapper) {
        this(mcpUrl, clientKey, timeout, retry, REFUSAL_PAUSE, jsonMapper);
    }

    McpCreditReportSource(String mcpUrl, String clientKey, Duration timeout, RetrySettings retry,
            Duration refusalPause, JsonMapper jsonMapper) {
        this.refusalPause = Objects.requireNonNull(refusalPause, "refusalPause");
        this.mcpUrl = Objects.requireNonNull(mcpUrl, "mcpUrl");
        this.clientKey = Objects.requireNonNull(clientKey, "clientKey");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    public static String resourceUri(Ssn ssn) {
        return "credit-report://ssn/" + ssn.digits();
    }

    @Override
    public Optional<CreditReport> findReport(Ssn ssn) {
        McpReadResourceResult result;
        try {
            AtomicInteger attempt = new AtomicInteger();
            result = withinTimeout(() ->
                    Retries.call(retry, McpCreditReportSource::isRetryable, () -> read(ssn, attempt.incrementAndGet())));
        } catch (RuntimeException e) {
            McpException mcpError = findMcpException(e);
            if (mcpError != null && mcpError.errorCode() == RESOURCE_NOT_FOUND) {
                return Optional.empty();
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

    /**
     * Runs {@code call} on a virtual thread and waits at most {@code timeout} for it. A FutureTask,
     * not a CompletableFuture: its cancel(true) interrupts the abandoned call, which then drops its
     * connection instead of staying blocked on the server.
     */
    private <T> T withinTimeout(Callable<T> call) {
        FutureTask<T> task = new FutureTask<>(call);
        Thread.ofVirtual().name("mcp-read").start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IllegalStateException("No answer from the Credit Report MCP server within " + timeout, e);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Credit Report MCP server", e);
        } catch (ExecutionException e) {
            // Keep the original exception: not-found and the error mapping depend on its cause chain.
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        }
    }

    private McpReadResourceResult read(Ssn ssn, int attempt) {
        try {
            return client().readResource(resourceUri(ssn));
        } catch (RuntimeException e) {
            boolean refused = refusedStatus(e);
            if (findMcpException(e) == null && !refused) {
                // The connection or request failed: drop the client so the next try reconnects. A
                // refusal (429/502/503) came over a working connection, so that is kept.
                reset();
            }
            if (refused && attempt < retry.maxAttempts() && !Retries.pause(refusalPause)) {
                // No cause, so the retry predicate does not see the refusal and stops here.
                IllegalStateException interrupted = new IllegalStateException("Interrupted while waiting to retry");
                interrupted.addSuppressed(e);
                throw interrupted;
            }
            throw e;
        }
    }

    /** The read never reached the MCP server, or the server refused it before doing any work. */
    static boolean isRetryable(Throwable e) {
        return findMcpException(e) == null && (Retries.notDelivered(e) || refusedStatus(e));
    }

    private static boolean refusedStatus(Throwable e) {
        HttpException http = Retries.find(e, HttpException.class);
        return http != null && REFUSED_STATUSES.contains(http.statusCode());
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
