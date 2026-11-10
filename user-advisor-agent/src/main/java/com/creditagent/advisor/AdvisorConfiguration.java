package com.creditagent.advisor;

import com.creditagent.advisor.guard.InputGuard;
import com.creditagent.advisor.guard.OutputGuard;
import com.creditagent.advisor.memory.ConversationMemory;
import com.creditagent.advisor.model.AdvisorModel;
import com.creditagent.advisor.model.PlaceholderAdvisorModel;
import com.creditagent.web.support.RequestSizeLimitFilter;
import com.creditagent.web.support.ServiceToken;
import com.creditagent.web.support.WebSupportAutoConfiguration;
import com.creditagent.advisor.eval.EvalLog;
import com.creditagent.advisor.eval.JsonEvalLog;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdvisorProperties.class)
class AdvisorConfiguration {

    /** A message plus a whole credit report; the web server sends far less. */
    static final long MAX_BODY_BYTES = 512 * 1024;

    /** Before the shared inbound limits: callers without the token must not use up the bucket. */
    @Bean
    FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilter(AdvisorProperties properties) {
        FilterRegistrationBean<ServiceTokenFilter> registration =
                new FilterRegistrationBean<>(new ServiceTokenFilter(new ServiceToken(properties.serviceToken())));
        registration.addUrlPatterns("/*");
        registration.setOrder(WebSupportAutoConfiguration.INBOUND_LIMIT_ORDER - 1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_BODY_BYTES, AdvisorController.BASE + "/*");
    }

    @Bean
    ConversationMemory conversationMemory(AdvisorProperties properties) {
        return new ConversationMemory(properties.memoryTtl(), properties.maxTurns(), properties.maxConversations(),
                properties.memoryPressure(), ConversationMemory::jvmHeapUsage, Clock.systemUTC());
    }

    /** The LLM placeholder. Replace this bean to plug in a model; the guardrails stay around it. */
    @Bean
    AdvisorModel advisorModel() {
        return new PlaceholderAdvisorModel();
    }

    /** Offline-eval log of every turn; {@code credit.advisor.eval-log.enabled=false} turns it off. */
    @Bean
    EvalLog evalLog(JsonMapper json, @Value("${credit.advisor.eval-log.enabled:true}") boolean enabled) {
        return enabled ? new JsonEvalLog(json) : EvalLog.NONE;
    }

    @Bean
    AdvisorService advisorService(AdvisorProperties properties, AdvisorModel model, ConversationMemory memory, EvalLog evals) {
        return new AdvisorService(new InputGuard(properties.maxPromptChars()), new OutputGuard(properties.maxReplyChars()),
                model, memory, evals, Clock.systemUTC());
    }

    @Bean
    MemorySweeper memorySweeper(ConversationMemory memory) {
        return new MemorySweeper(memory);
    }

    /** Drops expired conversations even when nobody comes back for them. */
    static final class MemorySweeper {
        private final ConversationMemory memory;

        MemorySweeper(ConversationMemory memory) {
            this.memory = memory;
        }

        @Scheduled(fixedDelayString = "${credit.advisor.sweep-interval:30s}", initialDelayString = "${credit.advisor.sweep-interval:30s}")
        void sweep() {
            memory.sweep();
        }
    }
}
