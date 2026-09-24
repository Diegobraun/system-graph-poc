package com.example.graphmcp;

import com.example.graphmcp.tools.SystemGraphTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GraphMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphMcpServerApplication.class, args);
    }

    @Bean
    ToolCallbackProvider systemGraphToolCallbacks(SystemGraphTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
