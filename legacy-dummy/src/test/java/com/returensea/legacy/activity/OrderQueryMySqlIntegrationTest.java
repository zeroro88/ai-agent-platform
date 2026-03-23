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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyDummyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("middleware")
class OrderQueryMySqlIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("无 orderId/phone 参数时不应返回全表数据")
    void query_withoutParams_returnsEmpty() {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/activities/registrations",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("middleware 下按手机号查询应返回订单与活动信息")
    void query_byPhone_shouldReturnOrders() {
        String phone = "13900005555";
        long activityId = seedActivity("中间件订单查询活动");
        registerOnce(activityId, phone);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/activities/registrations?phone=" + phone,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        List<Map<String, Object>> nonNullBody = Objects.requireNonNull(body);
        assertThat(nonNullBody).isNotEmpty();
        assertThat(nonNullBody)
                .anyMatch(o -> phone.equals(String.valueOf(o.get("phone")))
                        && "中间件订单查询活动".equals(String.valueOf(o.get("activityTitle"))));
    }

    @Test
    @DisplayName("middleware 下按 orderId 查询应精确返回单条订单")
    void query_byOrderId_shouldReturnSingleOrder() {
        String phone = "13900006666";
        long activityId = seedActivity("中间件订单精确查询活动");
        Map<String, Object> registerBody = registerOnce(activityId, phone);
        String orderId = String.valueOf(registerBody.get("id"));

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/activities/registrations?orderId=" + orderId,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> body = response.getBody();
        assertThat(body).isNotNull();
        List<Map<String, Object>> nonNullBody = Objects.requireNonNull(body);
        assertThat(nonNullBody).hasSize(1);
        assertThat(String.valueOf(nonNullBody.get(0).get("id"))).isEqualTo(orderId);
    }

    private Map<String, Object> registerOnce(long activityId, String phone) {
        cleanupOrderByPhoneAndActivity(phone, activityId);
        Map<String, Object> req = new HashMap<>();
        req.put("name", "订单查询用户");
        req.put("phone", phone);
        req.put("email", phone + "@example.com");
        Map<String, Object> body = Objects.requireNonNull(req);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/activities/" + activityId + "/register",
                HttpMethod.POST,
                new HttpEntity<>(body),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Objects.requireNonNull(response.getBody());
    }

    private long seedActivity(String title) {
        jdbcTemplate.update("DELETE FROM activity WHERE title = ?", title);
        jdbcTemplate.update("""
                INSERT INTO activity(title, description, location, start_time, end_time, status, price)
                VALUES (?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, 49.00)
                """, title, "order query integration test", "北京市订单测试区");
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

