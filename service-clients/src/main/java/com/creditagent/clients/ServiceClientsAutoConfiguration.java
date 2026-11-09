package com.creditagent.clients;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Makes {@link RetrySettings} ({@code credit.retry.*}) and {@link ReportLockProperties}
 * ({@code credit.report-lock.*}) beans in every service that calls another.
 */
@AutoConfiguration
@EnableConfigurationProperties({RetrySettings.class, ReportLockProperties.class})
public class ServiceClientsAutoConfiguration {}
