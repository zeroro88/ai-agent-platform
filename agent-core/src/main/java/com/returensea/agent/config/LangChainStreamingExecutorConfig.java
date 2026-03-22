package com.returensea.agent.config;

import com.returensea.agent.context.AgentContextPropagatingExecutor;
import com.returensea.agent.context.StreamRequestContextRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class LangChainStreamingExecutorConfig {

    public static final String ACTIVITY_STREAMING_TOOL_EXECUTOR = "activityStreamingToolExecutor";

    @Bean(name = ACTIVITY_STREAMING_TOOL_EXECUTOR)
    public Executor activityStreamingToolExecutor(StreamRequestContextRegistry streamRequestContextRegistry) {
        Executor delegate = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "activity-agent-tool");
            t.setDaemon(true);
            return t;
        });
        return new AgentContextPropagatingExecutor(delegate, streamRequestContextRegistry);
    }
}
