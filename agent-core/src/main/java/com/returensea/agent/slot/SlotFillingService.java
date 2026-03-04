package com.returensea.agent.slot;

import com.returensea.common.model.SlotFillingRequest;
import com.returensea.common.model.SlotFillingResult;
import com.returensea.common.model.SlotDefinition;

import java.util.List;
import java.util.Map;

public interface SlotFillingService {
    SlotFillingResult fillSlots(SlotFillingRequest request);
    
    Map<String, List<SlotDefinition>> getSlotDefinitions();
    
    void registerSlotDefinitions(String intentType, List<SlotDefinition> definitions);
}
