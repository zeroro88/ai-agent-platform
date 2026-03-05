package com.returensea.gateway;

import com.returensea.gateway.config.GatewayProperties;
import com.returensea.gateway.config.IntentRoutingProperties;
import com.returensea.gateway.graph.IntentRoutingGraphConfig;
import com.returensea.gateway.graph.IntentRoutingGraphNodes;
import com.returensea.gateway.service.IntentRouterServiceImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 意图路由集成测试用最小上下文：仅加载路由相关 Bean（含 LangGraph4j 图），不启动 Web、不依赖 AgentCoreClient / Mockito。
 */
@Configuration
@EnableConfigurationProperties({GatewayProperties.class, IntentRoutingProperties.class})
@Import({IntentRoutingGraphNodes.class, IntentRoutingGraphConfig.class, IntentRouterServiceImpl.class})
public class GatewayTestConfig {
}
