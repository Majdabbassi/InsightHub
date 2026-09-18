package com.dataanalytics.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger metadata for the backend. The description links out to the
 * analytics microservice's own interactive docs (FastAPI /docs), so a
 * reviewer can open both API surfaces from Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI insightHubOpenApi(
            @Value("${analytics.service.url}") String analyticsUrl) {
        String analyticsDocs = analyticsUrl.replaceAll("/+$", "") + "/docs";
        return new OpenAPI().info(new Info()
                .title("InsightHub API")
                .description("""
                        Backend for the InsightHub data analytics platform: user projects, \
                        dataset upload & analysis, cleaning, relationship detection, insights \
                        and the AI assistant chat (JWT-secured except /api/auth and /api/health).

                        The analytics microservice (statistics, cleaning, insights and the \
                        sandboxed SQL engine) serves its own interactive documentation at \
                        **%s** (FastAPI Swagger UI).""".formatted(analyticsDocs))
                .version("v1"));
    }
}