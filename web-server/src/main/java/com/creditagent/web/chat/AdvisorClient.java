package com.creditagent.web.chat;

import com.creditagent.clients.ServiceUnavailableException;
import com.creditagent.common.CreditReport;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The User Advisor Agent's internal API, with the service token. A chat message is not retried after
 * it may have arrived (each one adds to the conversation); the client it is built on retries only
 * failures that prove it never did, and 429 or 503. Forgetting a conversation is safe to repeat.
 */
public class AdvisorClient {

    static final String MESSAGES = "/internal/v1/conversations/{id}/messages";
    static final String CONVERSATION = "/internal/v1/conversations/{id}";

    /** The agent's answer. {@code refused} = a guardrail refused the question or replaced the answer. */
    public record ChatReply(String reply, String disclaimer, Boolean refused) {}

    private final RestClient chat;
    private final RestClient forget;
    private final String serviceToken;

    /**
     * @param chat a client that retries only undelivered requests
     * @param forget a client that may retry any transient failure
     */
    public AdvisorClient(RestClient chat, RestClient forget, String serviceToken) {
        this.chat = Objects.requireNonNull(chat);
        this.forget = Objects.requireNonNull(forget);
        this.serviceToken = serviceToken;
    }

    /** @throws ServiceUnavailableException if the agent cannot answer */
    public ChatReply chat(String conversationId, String message, CreditReport report) {
        requireToken();
        try {
            ChatReply reply = chat.post().uri(MESSAGES, conversationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message, "report", report))
                    .retrieve()
                    .body(ChatReply.class);
            if (reply == null || reply.reply() == null || reply.disclaimer() == null) {
                throw new ServiceUnavailableException("User Advisor Agent returned no answer", null);
            }
            return reply;
        } catch (RestClientException | HttpMessageConversionException e) {
            throw new ServiceUnavailableException("User Advisor Agent request failed", e);
        }
    }

    /** @throws ServiceUnavailableException if the agent cannot be told */
    public void forget(String conversationId) {
        requireToken();
        try {
            forget.delete().uri(CONVERSATION, conversationId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("User Advisor Agent request failed", e);
        }
    }

    private void requireToken() {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new ServiceUnavailableException("The chat needs INTERNAL_API_TOKEN", null);
        }
    }
}
