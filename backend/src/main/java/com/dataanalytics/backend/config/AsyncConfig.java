package com.dataanalytics.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables @Async execution for post-commit relationship detection. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
