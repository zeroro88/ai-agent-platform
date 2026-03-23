package com.returensea.agent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityDiscoveryHeuristicsTest {

    @Test
    void positive_recommendAndActivity() {
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("推荐一些活动")).isTrue();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("推荐一些上海的活动")).isTrue();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("推荐上海的活动")).isTrue();
    }

    @Test
    void positive_recentOrFind() {
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("最近有什么活动")).isTrue();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("帮我找活动")).isTrue();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("找活动")).isTrue();
    }

    @Test
    void negative_chitchatOrVagueTopic() {
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("你好呀请问在吗")).isFalse();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("这个活动怎么样")).isFalse();
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("")).isFalse();
    }

    @Test
    void negative_createWithoutDiscoveryCue() {
        assertThat(ActivityDiscoveryHeuristics.looksLikeActivityDiscovery("我要发起活动")).isFalse();
    }
}
