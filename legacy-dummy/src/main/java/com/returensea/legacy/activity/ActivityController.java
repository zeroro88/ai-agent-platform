package com.returensea.legacy.activity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private final Map<String, Activity> activityStore = new ConcurrentHashMap<>();
    private final Map<String, Registration> registrationStore = new ConcurrentHashMap<>();

    public ActivityController() {
        initializeSampleData();
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
        return activityStore.values().stream()
            .filter(a -> city == null || city.isEmpty() || a.getCity().equals(city))
            .filter(a -> keyword == null || keyword.isEmpty() || 
                   a.getTitle().contains(keyword) || a.getDescription().contains(keyword))
            .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public Activity get(@PathVariable String id) {
        Activity activity = activityStore.get(id);
        if (activity == null) {
            throw new RuntimeException("Activity not found: " + id);
        }
        return activity;
    }

    @PostMapping
    public Activity create(@RequestBody CreateActivityRequest request) {
        String id = "act-" + System.currentTimeMillis();
        Activity activity = Activity.builder()
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
        activityStore.put(id, activity);
        return activity;
    }

    @PostMapping("/{id}/register")
    public Registration register(@PathVariable String id, @RequestBody RegistrationRequest request) {
        Activity activity = activityStore.get(id);
        if (activity == null) {
            throw new RuntimeException("Activity not found: " + id);
        }
        
        String regId = "reg-" + System.currentTimeMillis();
        Registration registration = Registration.builder()
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
        return registration;
    }

    @GetMapping("/registrations")
    public List<Registration> queryRegistrations(@RequestParam(required = false) String phone,
                                                   @RequestParam(required = false) String orderId) {
        return registrationStore.values().stream()
            .filter(r -> phone == null || phone.isEmpty() || r.getPhone().equals(phone))
            .filter(r -> orderId == null || orderId.isEmpty() || r.getId().equals(orderId))
            .collect(Collectors.toList());
    }

    /** 清空测试数据：清空所有报名记录，活动列表恢复为初始样本（仅用于测试环境） */
    @PostMapping("/admin/reset")
    public Map<String, Object> resetTestData() {
        registrationStore.clear();
        activityStore.clear();
        initializeSampleData();
        return Map.of(
            "ok", true,
            "message", "已清空报名记录并恢复活动列表为初始样本",
            "activities", activityStore.size(),
            "registrations", 0
        );
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
}
