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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyDummyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("middleware")
class ActivityRegisterMySqlIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("middleware 下报名成功应写入 orders")
    void register_shouldPersistOrder() {
        long activityId = seedTestActivity("中间件报名活动-成功");
        String phone = "13900001111";
        cleanupOrderByPhoneAndActivity(phone, activityId);
        Map<String, Object> requestBody = Objects.requireNonNull(registrationRequest(phone));
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/activities/" + activityId + "/register",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = Objects.requireNonNull(response.getBody());
        assertThat(String.valueOf(body.get("status"))).isEqualTo("CONFIRMED");

        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(1) FROM orders o
                JOIN user u ON o.user_id = u.id
                WHERE u.phone = ? AND o.activity_id = ?
                """,
                Integer.class,
                phone,
                activityId);
        assertThat(count).isNotNull();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("middleware 下重复报名应返回 409")
    void register_duplicate_shouldReturnConflict() {
        long activityId = seedTestActivity("中间件报名活动-重复");
        String phone = "13900002222";
        cleanupOrderByPhoneAndActivity(phone, activityId);
        Map<String, Object> requestBody = Objects.requireNonNull(registrationRequest(phone));
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody);

        ResponseEntity<Map<String, Object>> first = restTemplate.exchange(
                "/api/activities/" + activityId + "/register",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<>() {});
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> second = restTemplate.exchange(
                "/api/activities/" + activityId + "/register",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<>() {});
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("middleware 下报名不存在活动应返回 404")
    void register_activityNotFound_shouldReturnNotFound() {
        Map<String, Object> requestBody = Objects.requireNonNull(registrationRequest("13900003333"));
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/activities/99999999/register",
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> registrationRequest(String phone) {
        Map<String, Object> req = new HashMap<>();
        req.put("name", "测试用户");
        req.put("phone", phone);
        req.put("email", phone + "@example.com");
        return req;
    }

    private long seedTestActivity(String title) {
        jdbcTemplate.update("DELETE FROM activity WHERE title = ?", title);
        jdbcTemplate.update("""
                INSERT INTO activity(title, description, location, start_time, end_time, status, price)
                VALUES (?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, 99.00)
                """, title, "register integration test", "北京市报名测试区");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM activity WHERE title = ? ORDER BY id DESC LIMIT 1",
                Long.class,
                title);
        assertThat(id).isNotNull();
        return id;
    }

    private void cleanupOrderByPhoneAndActivity(String phone, long activityId) {
        jdbcTemplate.update("""
                DELETE o FROM orders o
                JOIN user u ON o.user_id = u.id
                WHERE u.phone = ? AND o.activity_id = ?
                """, phone, activityId);
    }
}

