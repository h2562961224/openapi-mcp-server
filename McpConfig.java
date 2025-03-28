package com.agent.bookkeeper.application.base.mcp;

import com.agent.bookkeeper.application.base.config.JwtConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.AllArgsConstructor;
import org.springdoc.api.AbstractOpenApiResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * @author chuzhen
 * @since 2025/3/27 15:18
 */
@Configuration
@AllArgsConstructor
public class McpConfig {

    @Bean
    public InternalWebMvcSseServerTransport internalWebMvcSseServerTransport(UserContextSetter userContextSetter) {
        return new InternalWebMvcSseServerTransport(new ObjectMapper(), "/mcp/message", "/mcp/sse", userContextSetter);
    }

    @Bean
    public RouterFunction<?> routerFunction(InternalWebMvcSseServerTransport internalWebMvcSseServerTransport) {
        return internalWebMvcSseServerTransport.getRouterFunction();
    }

    @Bean
    public RestApiToolProvider restApiToolProvider(RequestMappingHandlerMapping handlerMapping, AbstractOpenApiResource abstractOpenApiResource){
        return new RestApiToolProvider(handlerMapping, abstractOpenApiResource);
    }

    @Bean
    public McpSyncServer mcpSyncServer(InternalWebMvcSseServerTransport internalWebMvcSseServerTransport, RestApiToolProvider restApiToolProvider) {
        return McpServer.sync(internalWebMvcSseServerTransport)
                .serverInfo("MCP Server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true) // Tool support with list changes notifications
                        .logging() // Logging support
                        .build())
                .tools(restApiToolProvider.getTools()) // Add @Tools
                .build();
    }
}
