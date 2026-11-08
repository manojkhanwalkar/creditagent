package com.creditagent.agent.a2a;

import com.creditagent.agent.AgentProperties;
import com.creditagent.agent.CreditIntelligenceService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the a2a-java server components by hand. The SDK's reference servers use Quarkus/CDI; the
 * builders below are the SDK's documented non-CDI construction path, so the agent can run inside
 * Spring Boot alongside the rest of the system.
 */
@Configuration(proxyBeanMethods = false)
class A2aServerConfiguration {

    static final String AGENT_VERSION = "0.1.0";

    private volatile int localPort = -1;

    @EventListener
    void onWebServerReady(WebServerInitializedEvent event) {
        localPort = event.getWebServer().getPort();
    }

    @Bean
    AgentExecutor creditIntelligenceAgentExecutor(CreditIntelligenceService service, JsonMapper jsonMapper) {
        return new CreditIntelligenceAgentExecutor(service, jsonMapper);
    }

    @Bean
    InMemoryTaskStore a2aTaskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    MainEventBus a2aMainEventBus() {
        return new MainEventBus();
    }

    @Bean
    QueueManager a2aQueueManager(InMemoryTaskStore taskStore, MainEventBus bus) {
        return new InMemoryQueueManager(taskStore, bus);
    }

    @Bean(initMethod = "start")
    MainEventBusProcessor a2aMainEventBusProcessor(
            MainEventBus bus, InMemoryTaskStore taskStore, QueueManager queueManager) {
        // Push notifications are not offered (see the agent card), so this sender is a no-op.
        PushNotificationSender noPush = (event, taskSnapshot) -> {};
        return new MainEventBusProcessor(bus, taskStore, noPush, queueManager);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService a2aExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Deliberately not a Spring bean: {@link DefaultRequestHandler} carries CDI {@code @Inject}
     * fields and a {@code @PostConstruct} that Spring would try to satisfy and run, failing on the
     * missing {@code A2AConfigProvider} and overwriting what the builder set. The builder already
     * produces a fully initialised handler.
     */
    private static RequestHandler a2aRequestHandler(
            AgentExecutor agentExecutor,
            InMemoryTaskStore taskStore,
            QueueManager queueManager,
            MainEventBusProcessor processor,
            ExecutorService a2aExecutor) {
        return DefaultRequestHandler.builder()
                .agentExecutor(agentExecutor)
                .taskStore(taskStore)
                .queueManager(queueManager)
                .mainEventBusProcessor(processor)
                .executor(a2aExecutor)
                .eventConsumerExecutor(a2aExecutor)
                .pushNotificationsEnabled(false)
                // The SDK fails closed (TaskNotFoundError) when authorization is required but no
                // TaskAuthorizationProvider is set. Callers are not authenticated yet: see S-2 in
                // docs/security-review.md. Replace this with a provider when S-2 is closed.
                .authorizationRequired(false)
                .build();
    }

    /**
     * The card's URL must name this server's real address, which with {@code server.port=0} is only
     * known once the web server is up, so the handler is created on first use.
     */
    @Bean
    Supplier<JSONRPCHandler> a2aJsonRpcHandler(
            AgentProperties properties,
            AgentExecutor agentExecutor,
            InMemoryTaskStore taskStore,
            QueueManager queueManager,
            MainEventBusProcessor processor,
            ExecutorService a2aExecutor) {
        RequestHandler requestHandler =
                a2aRequestHandler(agentExecutor, taskStore, queueManager, processor, a2aExecutor);
        return new Supplier<>() {
            private volatile JSONRPCHandler handler;

            @Override
            public JSONRPCHandler get() {
                JSONRPCHandler h = handler;
                if (h == null) {
                    synchronized (this) {
                        if (handler == null) {
                            AgentCard card = AgentCards.creditIntelligence(publicUrl(properties), AGENT_VERSION);
                            handler = new JSONRPCHandler(card, requestHandler, a2aExecutor);
                        }
                        h = handler;
                    }
                }
                return h;
            }
        };
    }

    private String publicUrl(AgentProperties properties) {
        if (properties.publicUrl() != null && !properties.publicUrl().isBlank()) {
            return properties.publicUrl();
        }
        if (localPort < 0) {
            throw new IllegalStateException("Web server port not known yet");
        }
        return "http://localhost:" + localPort;
    }
}
