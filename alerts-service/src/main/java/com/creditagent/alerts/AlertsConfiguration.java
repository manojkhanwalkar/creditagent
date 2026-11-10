package com.creditagent.alerts;

import com.creditagent.web.support.RequestSizeLimitFilter;
import com.creditagent.web.support.ServiceTokenFilter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
class AlertsConfiguration {

    static final long MAX_BODY_BYTES = 4 * 1024;

    /** Only the web server (holding INTERNAL_API_TOKEN) may ask; before the inbound limits. */
    @Bean
    FilterRegistrationBean<ServiceTokenFilter> serviceTokenFilter(@Value("${credit.alerts.service-token:}") String token) {
        return ServiceTokenFilter.registration(token);
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        return RequestSizeLimitFilter.registration(MAX_BODY_BYTES, AlertsController.PATH);
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(
            @Value("${credit.alerts.dynamodb-endpoint:}") String endpoint,
            @Value("${credit.alerts.region:us-east-1}") String region,
            @Value("${credit.alerts.dynamodb-timeout:5s}") Duration timeout,
            @Value("${credit.alerts.table:Alerts}") String table,
            @Value("${credit.alerts.create-tables:false}") boolean createTables) {
        DynamoDbClient client = AlertsTable.client(endpoint, region, timeout);
        if (createTables) {
            AlertsTable.ensure(client, table);
        }
        return client;
    }

    @Bean
    AlertRepository alertRepository(DynamoDbClient dynamo, @Value("${credit.alerts.table:Alerts}") String table) {
        return new DynamoDbAlertRepository(dynamo, table);
    }
}
