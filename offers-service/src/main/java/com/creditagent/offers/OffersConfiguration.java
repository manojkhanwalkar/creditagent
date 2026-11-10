package com.creditagent.offers;

import com.creditagent.web.support.RequestSizeLimitFilter;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class OffersConfiguration {

    static final String SERVER_NAME = "credit-card-offers";
    static final String SERVER_VERSION = "0.1.0";

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(OffersRestController.MAX_BODY_BYTES,
                OffersRestController.MCP_PATH, OffersRestController.OFFERS_PATH);
    }

    /** The placeholder until the offers marketplace exists. Replace this bean to plug one in. */
    @Bean
    OfferMarketplace offerMarketplace() {
        return OfferMarketplace.placeholder();
    }

    @Bean
    OfferEngine offerEngine(OfferMarketplace marketplace) {
        return new OfferEngine(marketplace, Clock.systemUTC());
    }

    @Bean
    OffersMcpHandler offersMcpHandler(OfferEngine engine, JsonMapper json) {
        McpImplementation info = new McpImplementation();
        info.setName(SERVER_NAME);
        info.setVersion(SERVER_VERSION);
        return new OffersMcpHandler(new McpServer(List.of(new OfferTools(engine, json)), info));
    }
}
