package com.returensea.legacy.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private static final String LOG_PREFIX = "[legacy-dummy ActivityController]";

    private final Map<String, Activity> activityStore = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrationStore = new ConcurrentHashMap<>();
    private static final DateTimeFormatter EVENT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private Environment environment;

    public ActivityController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        initializeSampleData();
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return value.toString();
        }
    }

    private void logIn(String httpMethod, String relativePath, Object payload) {
        log.info("{} {} {} << IN: {}", LOG_PREFIX, httpMethod, relativePath, toJson(payload));
    }

    private void logOut(String httpMethod, String relativePath, Object payload) {
        log.info("{} {} {} >> OUT: {}", LOG_PREFIX, httpMethod, relativePath, toJson(payload));
    }

    private void initializeSampleData() {
        List<Activity> samples = List.of(
            Activity.builder()
                .id("act-001")
                .title("海归人才招聘会")
                .city("北京")
                .location("北京国际会议中心")
                .eventTime("2024-03-15 14:00")
                .capacity(500)
                .registered(320)
                .fee(0)
                .description("50+知名企业现场招聘，资深HR一对一简历指导")
                .build(),
            Activity.builder()
                .id("act-002")
                .title("创业分享沙龙")
                .city("北京")
                .location("中关村创业大街")
                .eventTime("2024-03-20 14:00")
                .capacity(100)
                .registered(65)
                .fee(0)
                .description("海归创业者分享创业经验，对接投资机构")
                .build(),
            Activity.builder()
                .id("act-003")
                .title("海归职业发展论坛")
                .city("上海")
                .location("上海国际会议中心")
                .eventTime("2024-03-25 09:00")
                .capacity(300)
                .registered(180)
                .fee(0)
                .description("行业大咖分享职业发展路径，Networking机会")
                .build(),
            Activity.builder()
                .id("act-004")
                .title("深圳海归创业大赛")
                .city("深圳")
                .location("深圳湾创业广场")
                .eventTime("2024-04-01 09:00")
                .capacity(200)
                .registered(120)
                .fee(0)
                .description("展示创业项目，争夺创业扶持资金")
                .build()
        );
        samples.forEach(a -> activityStore.put(a.getId(), a));
    }

    @GetMapping
    public List<Activity> list(@RequestParam(required = false) String city,
                               @RequestParam(required = false) String keyword) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("city", city);
        in.put("keyword", keyword);
        logIn("GET", "/api/activities", in);

        final String cityFilter = normalizeParam(city);
        final String keywordFilter = normalizeParam(keyword);
        List<Activity> out;
        if (environment.acceptsProfiles(Profiles.of("middleware")) && jdbcTemplate != null) {
            out = listFromDatabase(cityFilter, keywordFilter);
        } else {
            out = activityStore.values().stream()
                    .filter(a -> cityFilter == null || a.getCity().equals(cityFilter))
                    .filter(a -> keywordFilter == null
                            || a.getTitle().contains(keywordFilter)
                            || a.getDescription().contains(keywordFilter))
                    .collect(Collectors.toList());
        }
        logOut("GET", "/api/activities", out);
        return out;
    }

    /** trim 后空串视为未传，避免 stream 闭包要求 effectively final 与重复 isEmpty 判断 */
    private static String normalizeParam(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @GetMapping("/{id}")
    public Activity get(@PathVariable String id) {
        logIn("GET", "/api/activities/{id}", Map.of("id", id));
        Activity activity;
        if (environment.acceptsProfiles(Profiles.of("middleware")) && jdbcTemplate != null) {
            activity = getFromDatabase(id);
            if (activity == null) {
                throw new RuntimeException("Activity not found: " + id);
            }
        } else {
            activity = activityStore.get(id);
            if (activity == null) {
                throw new RuntimeException("Activity not found: " + id);
            }
        }
        logOut("GET", "/api/activities/{id}", activity);
        return activity;
    }

    @PostMapping
    public Activity create(@RequestBody CreateActivityRequest request) {
        logIn("POST", "/api/activities", request);
        Activity created;
        if (environment.acceptsProfiles(Profiles.of("middleware")) && jdbcTemplate != null) {
            created = createInDatabase(request);
        } else {
            String id = "act-" + System.currentTimeMillis();
            created = Activity.builder()
                    .id(id)
                    .title(request.getTitle())
                    .city(request.getCity())
                    .location(request.getLocation())
                    .eventTime(request.getEventTime())
                    .capacity(request.getCapacity() != null ? request.getCapacity() : 100)
                    .registered(0)
                    .fee(request.getFee() != null ? request.getFee() : 0)
                    .description(request.getDescription() != null ? request.getDescription() : "用户发起活动")
                    .build();
            activityStore.put(id, created);
        }
        logOut("POST", "/api/activities", created);
        return created;
    }

    @PostMapping("/{id}/register")
    public Registration register(@PathVariable String id, @RequestBody RegistrationRequest request) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("activityId", id);
        in.put("body", request);
        logIn("POST", "/api/activities/{id}/register", in);

        Registration registration;
        if (environment.acceptsProfiles(Profiles.of("middleware")) && jdbcTemplate != null) {
            registration = registerInDatabase(id, request);
        } else {
            Activity activity = activityStore.get(id);
            if (activity == null) {
                throw new RuntimeException("Activity not found: " + id);
            }

            String regId = "reg-" + System.currentTimeMillis();
            registration = Registration.builder()
                    .id(regId)
                    .activityId(id)
                    .activityTitle(activity.getTitle())
                    .name(request.getName())
                    .phone(request.getPhone())
                    .email(request.getEmail())
                    .status("CONFIRMED")
                    .registeredAt(LocalDateTime.now())
                    .build();

            registrationStore.put(regId, registration);
        }
        logOut("POST", "/api/activities/{id}/register", registration);
        return registration;
    }

    @GetMapping("/registrations")
    public List<Registration> queryRegistrations(@RequestParam(required = false) String phone,
                                                   @RequestParam(required = false) String orderId) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("phone", phone);
        in.put("orderId", orderId);
        logIn("GET", "/api/activities/registrations", in);

        boolean noFilter = (phone == null || phone.isBlank()) && (orderId == null || orderId.isBlank());
        if (noFilter) {
            log.warn("queryRegistrations: 拒绝无过滤条件查询（避免返回全表）");
            logOut("GET", "/api/activities/registrations", List.of());
            return List.of();
        }

        List<Registration> out;
        if (environment.acceptsProfiles(Profiles.of("middleware")) && jdbcTemplate != null) {
            out = queryRegistrationsFromDatabase(phone, orderId);
        } else {
            out = registrationStore.values().stream()
                    .filter(r -> phone == null || phone.isEmpty() || r.getPhone().equals(phone))
                    .filter(r -> orderId == null || orderId.isEmpty() || r.getId().equals(orderId))
                    .collect(Collectors.toList());
        }
        logOut("GET", "/api/activities/registrations", out);
        return out;
    }

    /** 清空测试数据：清空所有报名记录，活动列表恢复为初始样本（仅用于测试环境） */
    @PostMapping("/admin/reset")
    public Map<String, Object> resetTestData() {
        logIn("POST", "/api/activities/admin/reset", Map.of());

        registrationStore.clear();
        activityStore.clear();
        initializeSampleData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "已清空报名记录并恢复活动列表为初始样本");
        out.put("activities", activityStore.size());
        out.put("registrations", 0);
        logOut("POST", "/api/activities/admin/reset", out);
        return out;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Activity {
        private String id;
        private String title;
        private String city;
        private String location;
        private String eventTime;
        private Integer capacity;
        private Integer registered;
        private Integer fee;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Registration {
        private String id;
        private String activityId;
        private String activityTitle;
        private String name;
        private String phone;
        private String email;
        private String status;
        private LocalDateTime registeredAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegistrationRequest {
        private String name;
        private String phone;
        private String email;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateActivityRequest {
        private String title;
        private String city;
        private String location;
        private String eventTime;
        private Integer capacity;
        private Integer fee;
        private String description;
    }

    private List<Activity> listFromDatabase(String city, String keyword) {
        // 动态拼接 + 单占位符绑定「%关键词%」，避免 CONCAT('%',?,'%') 在部分驱动/字符集下与中文参数组合异常导致 0 行
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, location, start_time, price, description
                FROM activity
                WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (city != null) {
            sql.append(" AND location LIKE ?");
            args.add("%" + city + "%");
        }
        if (keyword != null) {
            sql.append(" AND (title LIKE ? OR description LIKE ?)");
            String kw = "%" + keyword + "%";
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY id DESC");
        // 必须用 query(sql, Object[] args, RowMapper)：若写成 query(sql, rowMapper, args.toArray())，
        // varargs 会把整个数组当成「一个」参数，导致占位符与 ? 数量不匹配，常表现为始终 0 行。
        return jdbcTemplate.query(sql.toString(), args.toArray(), (rs, rowNum) -> Activity.builder()
                .id(String.valueOf(rs.getLong("id")))
                .title(rs.getString("title"))
                .city(extractCity(rs.getString("location")))
                .location(rs.getString("location"))
                .eventTime(rs.getTimestamp("start_time").toLocalDateTime().toString())
                .capacity(null)
                .registered(null)
                .fee(rs.getBigDecimal("price") == null ? 0 : rs.getBigDecimal("price").intValue())
                .description(rs.getString("description"))
                .build());
    }

    private Activity getFromDatabase(String id) {
        String sql = """
                SELECT id, title, location, start_time, price, description
                FROM activity
                WHERE id = ?
                """;
        List<Activity> list = jdbcTemplate.query(sql, ps -> ps.setString(1, id), (rs, rowNum) -> Activity.builder()
                .id(String.valueOf(rs.getLong("id")))
                .title(rs.getString("title"))
                .city(extractCity(rs.getString("location")))
                .location(rs.getString("location"))
                .eventTime(rs.getTimestamp("start_time").toLocalDateTime().toString())
                .capacity(null)
                .registered(null)
                .fee(rs.getBigDecimal("price") == null ? 0 : rs.getBigDecimal("price").intValue())
                .description(rs.getString("description"))
                .build());
        return list.isEmpty() ? null : list.get(0);
    }

    private String extractCity(String location) {
        if (location == null || location.isEmpty()) {
            return "";
        }
        if (location.contains("北京")) {
            return "北京";
        }
        if (location.contains("上海")) {
            return "上海";
        }
        if (location.contains("深圳")) {
            return "深圳";
        }
        return location;
    }

    private Activity createInDatabase(CreateActivityRequest request) {
        LocalDateTime startTime = parseEventTime(request.getEventTime());
        LocalDateTime endTime = startTime.plusHours(2);
        Integer fee = request.getFee() == null ? 0 : request.getFee();
        String description = request.getDescription() == null ? "用户发起活动" : request.getDescription();
        String location = request.getLocation() == null ? "待定地点" : request.getLocation();

        String sql = """
                INSERT INTO activity(title, description, location, start_time, end_time, status, price)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.getTitle());
            ps.setString(2, description);
            ps.setString(3, location);
            ps.setObject(4, startTime);
            ps.setObject(5, endTime);
            ps.setBigDecimal(6, BigDecimal.valueOf(fee));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        String id = key == null ? "" : String.valueOf(key.longValue());
        return Activity.builder()
                .id(id)
                .title(request.getTitle())
                .city(request.getCity() == null ? extractCity(location) : request.getCity())
                .location(location)
                .eventTime(startTime.toString())
                .capacity(request.getCapacity())
                .registered(0)
                .fee(fee)
                .description(description)
                .build();
    }

    private Registration registerInDatabase(String activityId, RegistrationRequest request) {
        DbActivity dbActivity = loadDbActivity(activityId);
        if (dbActivity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Activity not found: " + activityId);
        }

        long userId = resolveOrCreateUserId(request);
        Integer duplicateCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM orders WHERE user_id = ? AND activity_id = ? AND status <> 2",
                Integer.class,
                userId,
                dbActivity.id);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate registration is not allowed");
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO orders(user_id, activity_id, amount, status) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, dbActivity.id);
            ps.setBigDecimal(3, dbActivity.price);
            ps.setInt(4, 1);
            return ps;
        }, keyHolder);

        Number orderId = keyHolder.getKey();
        String responseOrderId = orderId == null ? "" : String.valueOf(orderId.longValue());
        return Registration.builder()
                .id(responseOrderId)
                .activityId(String.valueOf(dbActivity.id))
                .activityTitle(dbActivity.title)
                .name(request.getName())
                .phone(request.getPhone())
                .email(request.getEmail())
                .status("CONFIRMED")
                .registeredAt(LocalDateTime.now())
                .build();
    }

    private List<Registration> queryRegistrationsFromDatabase(String phone, String orderId) {
        String sql = """
                SELECT o.id AS order_id, o.activity_id, o.status, o.created_at,
                       a.title AS activity_title,
                       u.username, u.phone, u.email
                FROM orders o
                JOIN user u ON o.user_id = u.id
                JOIN activity a ON o.activity_id = a.id
                WHERE (? IS NULL OR ? = '' OR u.phone = ?)
                  AND (? IS NULL OR ? = '' OR CAST(o.id AS CHAR) = ?)
                ORDER BY o.id DESC
                """;
        return jdbcTemplate.query(sql, ps -> {
            ps.setString(1, phone);
            ps.setString(2, phone);
            ps.setString(3, phone);
            ps.setString(4, orderId);
            ps.setString(5, orderId);
            ps.setString(6, orderId);
        }, (rs, rowNum) -> Registration.builder()
                .id(String.valueOf(rs.getLong("order_id")))
                .activityId(String.valueOf(rs.getLong("activity_id")))
                .activityTitle(rs.getString("activity_title"))
                .name(rs.getString("username"))
                .phone(rs.getString("phone"))
                .email(rs.getString("email"))
                .status(mapOrderStatus(rs.getInt("status")))
                .registeredAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build());
    }

    private DbActivity loadDbActivity(String activityId) {
        List<DbActivity> list = jdbcTemplate.query(
                "SELECT id, title, price FROM activity WHERE id = ?",
                ps -> ps.setString(1, activityId),
                (rs, rowNum) -> new DbActivity(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getBigDecimal("price") == null ? BigDecimal.ZERO : rs.getBigDecimal("price")));
        return list.isEmpty() ? null : list.get(0);
    }

    private long resolveOrCreateUserId(RegistrationRequest request) {
        if (request == null || request.getPhone() == null || request.getPhone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone is required for registration");
        }
        List<Long> userIds = jdbcTemplate.query(
                "SELECT id FROM user WHERE phone = ? LIMIT 1",
                ps -> ps.setString(1, request.getPhone()),
                (rs, rowNum) -> rs.getLong("id"));
        if (!userIds.isEmpty()) {
            return userIds.get(0);
        }

        String username = (request.getName() == null || request.getName().isBlank())
                ? "user-" + request.getPhone().substring(Math.max(0, request.getPhone().length() - 4))
                : request.getName();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO user(username, phone, email) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, request.getPhone());
            ps.setString(3, request.getEmail());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create user");
        }
        return key.longValue();
    }

    private String mapOrderStatus(int status) {
        return switch (status) {
            case 1 -> "CONFIRMED";
            case 2 -> "CANCELLED";
            default -> "PENDING";
        };
    }

    private LocalDateTime parseEventTime(String eventTime) {
        if (eventTime == null || eventTime.isBlank()) {
            return LocalDateTime.now().plusDays(1);
        }
        try {
            return LocalDateTime.parse(eventTime, EVENT_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(eventTime);
            } catch (DateTimeParseException e) {
                return LocalDateTime.now().plusDays(1);
            }
        }
    }

    private record DbActivity(long id, String title, BigDecimal price) {}
}
