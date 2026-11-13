package com.customercompass.api.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which MCP tool servers the tools chat can use. {@code springbootUrl} empty means this
 * application's own server; {@code fastmcpUrl} empty means that server is not configured.
 *
 * @param backend the server used when a request does not name one: springboot or fastmcp
 */
@ConfigurationProperties("mcp")
public record McpProperties(
    @DefaultValue("springboot") String backend,
    @DefaultValue("") String fastmcpUrl,
    @DefaultValue("") String springbootUrl) {}
