package com.creditagent.clients;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Makes {@link RetrySettings} ({@code credit.retry.*}) a bean in every service that calls another. */
@AutoConfiguration
@EnableConfigurationProperties(RetrySettings.class)
public class ServiceClientsAutoConfiguration {}
