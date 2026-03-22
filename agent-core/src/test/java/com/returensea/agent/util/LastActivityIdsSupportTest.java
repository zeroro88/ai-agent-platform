package com.returensea.agent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LastActivityIdsSupportTest {

    @Test
    void normalize_integerElements_becomeStrings() {
        @SuppressWarnings("unchecked")
        List<?> raw = List.of(42, 28L);
        List<String> n = LastActivityIdsSupport.normalize(raw);
        assertThat(n).containsExactly("42", "28");
    }

    @Test
    void normalize_mixedWithStrings() {
        List<String> n = LastActivityIdsSupport.normalize(List.of("42", 99));
        assertThat(n).containsExactly("42", "99");
    }
}
