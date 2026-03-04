package com.returensea.legacy.activity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Activity {
    private String id;
    private String title;
    private String description;
    private String city;
    private String address;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private int maxParticipants;
    private int currentParticipants;
    private String organizer;
    private String status;
    private double price;
}
