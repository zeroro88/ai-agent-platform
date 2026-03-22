package com.returensea.agent.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户自然语言中抽取报名所需的姓名、电话（供 ActivityAgent、槽位填充共用）。
 */
public final class RegistrationParsers {

    private RegistrationParsers() {}

    public static String extractPhone(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("1[3-9]\\d{9}").matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        matcher = Pattern.compile("(?:电话|手机号|手机|联系方式)是\\s*(1[3-9]\\d{9})").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("(?:电话|手机号|手机|联系方式)\\s*[:：]\\s*(\\d{6,15})").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    public static String extractName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?:我)?(?:的)?(?:姓名|名字)是\\s*([^，,。\\s]+)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("姓名\\s*[:：]\\s*([^，,。\\s]+)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("我叫\\s*([^，,。\\s]+)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("我是\\s*([^，,。]+?)(?=\\s*[,，]|\\s*电话|\\s*手机|手机号|$)").matcher(text);
        if (matcher.find()) {
            String n = matcher.group(1).trim();
            if (n.length() >= 2 && n.length() <= 16) {
                return n;
            }
        }
        return null;
    }

    public static boolean looksLikeRegisterSubmit(String message) {
        if (message == null) {
            return false;
        }
        if (extractActivityOrdinalOrNumericToken(message) != null) {
            return true;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return message.contains("是的") || message.contains("好的") || message.contains("确认")
                || message.contains("参加") || message.contains("想报名") || message.contains("要报名")
                || message.contains("帮我报名") || m.contains("ok");
    }

    public static String extractActivityOrdinalOrNumericToken(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("act-\\d+").matcher(text);
        if (matcher.find()) {
            return matcher.group();
        }
        matcher = Pattern.compile("活动\\s*[Ii][Dd]?\\s*[:：\\s]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile("编号\\s*[:：]?\\s*(\\d+)").matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = Pattern.compile("第\\s*(\\d+)\\s*个").matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        matcher = Pattern.compile("第\\s*([一二三四五六七八九十两]+)\\s*个").matcher(text);
        if (matcher.find()) {
            int n = chineseOrdinalToInt(matcher.group(1).trim());
            if (n > 0) {
                return String.valueOf(n);
            }
        }
        return null;
    }

    public static boolean containsActivityOrdinalPhrase(String text) {
        if (text == null) {
            return false;
        }
        return Pattern.compile("第\\s*\\d+\\s*个").matcher(text).find()
                || Pattern.compile("第\\s*[一二三四五六七八九十两]+\\s*个").matcher(text).find();
    }

    public static int chineseOrdinalToInt(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        if (s.matches("\\d+")) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return switch (s) {
            case "一", "幺" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> -1;
        };
    }
}
