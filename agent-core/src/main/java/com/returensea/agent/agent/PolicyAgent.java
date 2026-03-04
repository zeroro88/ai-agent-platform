package com.returensea.agent.agent;

import com.returensea.agent.rag.RAGClient;
import com.returensea.common.enums.AgentType;
import com.returensea.common.model.AgentRequest;
import com.returensea.common.model.RAGResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("POLICY")
public class PolicyAgent extends AbstractDomainAgent {

    private final RAGClient ragClient;

    public PolicyAgent(RAGClient ragClient) {
        this.ragClient = ragClient;
    }

    @Override
    protected AgentType getAgentType() {
        return AgentType.POLICY;
    }

    @Override
    protected String processInternal(AgentRequest request) {
        String message = request.getMessage();
        
        if (isPolicyQuery(message)) {
            return processPolicyQuery(request);
        }
        
        return "您好！我是政策助手，可以为您提供海归落户、创业补贴、税费优惠等政策咨询。请告诉我您想了解哪方面的政策？";
    }

    private boolean isPolicyQuery(String message) {
        String lower = message.toLowerCase();
        return lower.contains("落户") || lower.contains("户口") ||
               lower.contains("补贴") || lower.contains("优惠") ||
               lower.contains("创业") || lower.contains("购房") ||
               lower.contains("税费") || lower.contains("政策");
    }

    private String processPolicyQuery(AgentRequest request) {
        try {
            RAGResponse ragResponse = ragClient.querySync(request.getMessage(), "policy_consult");
            
            if (ragResponse != null && ragResponse.getAnswer() != null) {
                return ragResponse.getAnswer();
            }
            
            return "抱歉，暂时无法查询到相关政策信息。请稍后重试或联系人工客服。";
            
        } catch (Exception e) {
            log.error("Error querying RAG for policy: {}", e.getMessage(), e);
            return "查询政策时遇到技术问题，建议您联系人工客服获取帮助。";
        }
    }
}
