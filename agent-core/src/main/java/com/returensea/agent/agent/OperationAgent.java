package com.returensea.agent.agent;

import com.returensea.common.enums.AgentType;
import com.returensea.common.model.AgentRequest;
import org.springframework.stereotype.Component;

@Component("OPERATION")
public class OperationAgent extends AbstractDomainAgent {

    @Override
    protected AgentType getAgentType() {
        return AgentType.OPERATION;
    }

    @Override
    protected String processInternal(AgentRequest request) {
        return "运营助手就绪。请通过运营后台访问更多功能。";
    }
}
