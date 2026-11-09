package com.creditagent.mcp;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(McpHttpController.class)
@Import(McpHttpControllerTest.Props.class)
class McpHttpControllerTest {

    @TestConfiguration
    static class Props {
        @Bean
        McpServerProperties mcpServerProperties() {
            return new McpServerProperties(
                    Path.of("unused"), "test-key-0123456789abcdef0123456789abcdef", List.of("http://allowed.example"));
        }

        @Bean
        com.creditagent.web.support.ServiceToken serviceToken() {
            return new com.creditagent.web.support.ServiceToken(TOKEN);
        }
    }

    static final String TOKEN = "service-token-service-token-service-token";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CreditReportMcpHandler handler;

    @Test
    void requestsGetTheHandlersJsonResponse() throws Exception {
        given(handler.handle(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn(Optional.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void notificationsGet202() throws Exception {
        given(handler.handle(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn(Optional.empty());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void foreignBrowserOriginsAreRefused() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).header("Origin", "http://evil.example").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(handler);
    }

    @Test
    void allowListedOriginsAreServed() throws Exception {
        given(handler.handle(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).willReturn(Optional.empty());
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).header("Origin", "http://allowed.example").content("{}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void oversizedBodiesAreRefused() throws Exception {
        String big = "{\"pad\":\"" + "x".repeat((int) McpHttpController.MAX_BODY_BYTES) + "\"}";
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().is(413));
        verifyNoInteractions(handler);
    }

    @Test
    void noServerSentEventStream() throws Exception {
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void onlyTheServiceTokenMarksACallerAsInternal() throws Exception {
        given(handler.handle(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .willReturn(Optional.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + TOKEN).content(body));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer wrong").content(body));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/mcp")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(handler);
        order.verify(handler).handle(body, true);
        order.verify(handler, org.mockito.Mockito.times(2)).handle(body, false);
    }
}
