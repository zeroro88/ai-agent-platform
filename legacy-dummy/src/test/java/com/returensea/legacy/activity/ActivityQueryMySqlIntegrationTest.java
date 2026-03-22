package com.returensea.legacy.activity;

import com.returensea.legacy.LegacyDummyApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = LegacyDummyApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("middleware")
class ActivityQueryMySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("按「上海」筛选：location 含上海时应返回行（验证 JdbcTemplate query(sql, Object[], RowMapper) 绑定）")
    void list_byCity_shanghai_shouldReturnNonEmpty() {
        String title = "中间件测试活动-上海筛选-" + System.currentTimeMillis();
        jdbcTemplate.update("DELETE FROM activity WHERE title = ?", title);
        jdbcTemplate.update("""
                INSERT INTO activity(title, description, location, start_time, end_time, status, price)
                VALUES (?, ?, '上海市浦东新区', NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, 0.00)
                """, title, "集成测试描述");
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/activities?city=上海",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> activities = response.getBody();

        assertThat(activities).isNotNull();
        assertThat(activities).isNotEmpty();
        assertThat(activities)
                .anyMatch(a -> title.equals(String.valueOf(a.get("title"))));
    }

    @Test
    @DisplayName("middleware 下活动查询应命中 MySQL 初始化数据")
    void list_shouldReadFromMySql() {
        long id = seedTestActivity("中间件测试活动-查询");
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/api/activities?city=北京&keyword=中间件测试活动-查询",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        List<Map<String, Object>> activities = response.getBody();

        assertThat(activities).isNotNull();
        assertThat(activities).isNotEmpty();
        assertThat(activities)
                .anyMatch(a -> String.valueOf(id).equals(String.valueOf(a.get("id"))));
    }

    @Test
    @DisplayName("middleware 下活动详情应可按 ID 从 MySQL 查询")
    void get_shouldReadFromMySql() {
        long id = seedTestActivity("中间件测试活动-详情");
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/api/activities/" + id,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        Map<String, Object> detail = response.getBody();

        assertThat(detail).isNotNull();
        Map<String, Object> nonNullDetail = Objects.requireNonNull(detail);
        assertThat(String.valueOf(nonNullDetail.get("id"))).isEqualTo(String.valueOf(id));
        assertThat(String.valueOf(nonNullDetail.get("location"))).contains("北京");
    }

    private long seedTestActivity(String title) {
        jdbcTemplate.update("DELETE FROM activity WHERE title = ?", title);
        jdbcTemplate.update("""
                INSERT INTO activity(title, description, location, start_time, end_time, status, price)
                VALUES (?, ?, ?, NOW(), DATE_ADD(NOW(), INTERVAL 2 HOUR), 0, 0.00)
                """, title, "integration test record", "北京市测试区");
        Long id = jdbcTemplate.queryForObject("SELECT id FROM activity WHERE title = ? ORDER BY id DESC LIMIT 1", Long.class, title);
        assertThat(id).isNotNull();
        return id;
    }
}

