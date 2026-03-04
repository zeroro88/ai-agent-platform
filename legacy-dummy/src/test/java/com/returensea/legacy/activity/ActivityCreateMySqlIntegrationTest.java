package com.returensea.legacy.activity;

import com.returensea.legacy.LegacyDummyApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyDummyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("middleware")
class ActivityCreateMySqlIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("middleware 下创建活动应写入 MySQL 并返回新 ID")
    void create_shouldPersistToMySql() {
        Map<String, Object> request = new HashMap<>();
        request.put("title", "中间件创建活动测试");
        request.put("city", "北京");
        request.put("location", "北京市测试创建路");
        request.put("eventTime", "2026-04-01 10:00");
        request.put("fee", 99);
        request.put("description", "create integration test");

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/activities",
                HttpMethod.POST,
                new HttpEntity<>(request),
                new ParameterizedTypeReference<>() {});

        Map<String, Object> body = Objects.requireNonNull(response.getBody());
        String id = String.valueOf(body.get("id"));
        assertThat(id).isNotBlank();
        assertThat(id).doesNotStartWith("act-");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM activity WHERE id = ? AND title = ?",
                Integer.class,
                Long.parseLong(id),
                "中间件创建活动测试");
        assertThat(count).isNotNull();
        assertThat(count).isEqualTo(1);
    }
}

