package com.returensea.agent.agent;

import com.returensea.common.enums.AgentType;
import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractDomainAgent implements DomainAgent {

    protected abstract AgentType getAgentType();
    protected abstract String processInternal(AgentRequest request);

    @Override
    public boolean canHandle(AgentRequest request) {
        return request != null
                && request.getAgentType() != null
                && request.getAgentType() == getAgentType();
    }

    @Override
    public AgentResponse process(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            String content = processInternal(request);
            
            return AgentResponse.builder()
                    .responseId(java.util.UUID.randomUUID().toString())
                    .sessionId(request.getSessionId())
                    .userId(request.getUserId())
                    .content(content)
                    .agentType(getAgentType())
                    .timestamp(java.time.LocalDateTime.now())
                    .processTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("Error processing request in agent: {}", getAgentType(), e);
            return AgentResponse.builder()
                    .responseId(java.util.UUID.randomUUID().toString())
                    .sessionId(request.getSessionId())
                    .userId(request.getUserId())
                    .content("处理请求时出错：" + e.getMessage())
                    .agentType(getAgentType())
                    .error(e.getMessage())
                    .timestamp(java.time.LocalDateTime.now())
                    .processTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
}
