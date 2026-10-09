package org.example.quantapi.mcp;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MCP server into quant_api at {@code /mcp} over streamable HTTP (R.5).
 *
 * <p><b>Stateless on purpose.</b> The SDK offers a session-keeping streamable transport and a
 * stateless one. Every tool here is a request/response read, nothing needs server-to-client
 * notifications, and each request carries its own bearer token — so a session would only add
 * state to lose on restart and to pin to one instance. Stateless also means authentication is
 * re-checked on every call rather than once at session start.
 *
 * <p>The transport is a plain servlet, so Spring Security's filter chain runs in front of it
 * like any controller: no token, no tool list. The MCP Java SDK is used directly rather than
 * through Spring AI's starter because the starter needs Spring Boot 3.4+, and upgrading the
 * whole API to expose one endpoint was the wrong trade. Jackson 2 bindings are chosen
 * explicitly: the SDK's convenience artifact pulls Jackson 3, which Boot 3.3 does not use.
 */
@Configuration
public class McpServerConfig {

    public static final String ENDPOINT = "/mcp";

    @Bean
    McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    HttpServletStatelessServerTransport mcpTransport(McpJsonMapper mcpJsonMapper) {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(mcpJsonMapper)
                .messageEndpoint(ENDPOINT)
                // Runs on the servlet thread, where the SecurityContext is populated; tool
                // handlers may not, so the caller travels in the transport context instead.
                .contextExtractor(request -> McpTransportContext.create(
                        Map.of(McpCaller.CONTEXT_KEY, McpCaller.current())))
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> bean = new ServletRegistrationBean<>(transport, ENDPOINT);
        bean.setName("mcp");
        return bean;
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, McpJsonMapper mcpJsonMapper,
                                     QuantMcpTools tools) {
        return McpServer.sync(transport)
                .serverInfo("quant-api", "1.0.0")
                .instructions("Equity research data for the quant platform: signals, features, news and, for a "
                        + "signed-in person, their own holdings. Everything is research output, not investment advice.")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .jsonMapper(mcpJsonMapper)
                .tools(tools.specifications(mcpJsonMapper))
                .build();
    }
}
