package com.returensea.gateway.service;

import com.returensea.gateway.dto.RouteResult;
import com.returensea.gateway.dto.ChatRequest;

public interface IntentRouterService {
    RouteResult route(ChatRequest request);
    boolean needsClarification(RouteResult result);
}
