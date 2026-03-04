package com.returensea.agent.agent;

import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.AgentResponse;

public interface DomainAgent {
    AgentResponse process(AgentRequest request);
    boolean canHandle(AgentRequest request);
}
