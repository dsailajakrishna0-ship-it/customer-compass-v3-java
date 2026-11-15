package com.customercompass.api.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description served at {@code /v3/api-docs} and shown by Swagger UI at {@code
 * /swagger-ui.html}. It is generated from the controllers and their annotations, so it cannot drift
 * from the code; set {@code OPENAPI_ENABLED=false} to switch both off.
 */
@Configuration
class OpenApiConfig {

  @Bean
  OpenAPI customerCompassApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Customer Compass API (Java edition)")
                .version("v1")
                .description(
                    "The same contract as the Python edition. Most responses are JSON objects whose"
                        + " fields are described in the stage guides; request bodies are checked by"
                        + " hand and answered with FastAPI-style 422 errors."));
  }

  /**
   * The chat and agent routes read the raw body and the Content-Type header themselves (so that
   * every bad input gets the same 422). The header is not something a caller fills in, so it is
   * left out of the description.
   */
  @Bean
  OpenApiCustomizer withoutContentTypeHeader() {
    return openApi ->
        openApi.getPaths().values().stream()
            .flatMap(item -> item.readOperations().stream())
            .forEach(
                op -> {
                  if (op.getParameters() != null) {
                    op.getParameters()
                        .removeIf(
                            p ->
                                "header".equals(p.getIn())
                                    && "Content-Type".equalsIgnoreCase(p.getName()));
                  }
                });
  }
}
