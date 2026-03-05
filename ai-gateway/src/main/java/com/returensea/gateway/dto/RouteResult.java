package com.returensea.gateway.dto;

import com.returensea.common.enums.AgentType;
import com.returensea.common.enums.IntentType;
import com.returensea.common.enums.PermissionLevel;
import com.returensea.common.enums.RouteType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private IntentType intentType;
    private AgentType targetAgent;
    private RouteType routeType;
    private PermissionLevel requiredPermission;
    private Map<String, Object> extractedEntities;
    private double confidence;
    private String clarification;
    private boolean needsClarification;
}
