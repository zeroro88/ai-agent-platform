package com.returensea.legacy.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复刻 {@link ActivityController} 中 {@code listFromDatabase} 的 SQL 与
 * {@link JdbcTemplate#query(String, Object[], org.springframework.jdbc.core.RowMapper)} 调用方式，
 * 使用 H2 验证：{@code city=上海} 时 {@code location LIKE ?} 绑定 {@code %上海%} 能命中数据。
 * <p>
 * 不启动 Spring、不连 MySQL，避免 Mockito 在部分 JDK/沙箱下无法 attach agent。
 */
class ActivityListLocationLikeSqlTest {

    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:activitylistliketest;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute("""
                CREATE TABLE activity (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  title VARCHAR(200) NOT NULL,
                  description TEXT,
                  location VARCHAR(200) NOT NULL,
                  start_time TIMESTAMP NOT NULL,
                  end_time TIMESTAMP NOT NULL,
                  status INT DEFAULT 0,
                  price DECIMAL(10,2) DEFAULT 0
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO activity (title, description, location, start_time, end_time, status, price)
                VALUES ('海归职业发展论坛', 'Networking', '上海国际会议中心', ?, ?, 0, 0)
                """,
                Timestamp.valueOf("2026-05-25 09:00:00"),
                Timestamp.valueOf("2026-05-25 12:00:00"));
    }

    @Test
    @DisplayName("listFromDatabase 等价 SQL：使用 query(sql, Object[], rowMapper) 时 city=上海 能查到行")
    @SuppressWarnings("deprecation")
    void listFromDatabase_equivalent_query_returnsRowsForShanghai() {
        String city = "上海";
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, location, start_time, price, description
                FROM activity
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        sql.append(" AND location LIKE ?");
        args.add("%" + city + "%");
        sql.append(" ORDER BY id DESC");

        // 与 ActivityController#listFromDatabase 一致：query(sql, Object[], RowMapper)
        List<Long> ids = jdbcTemplate.query(sql.toString(), args.toArray(),
                (rs, rowNum) -> rs.getLong("id"));

        assertThat(ids).hasSize(1);
    }

}
