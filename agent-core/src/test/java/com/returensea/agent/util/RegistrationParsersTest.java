package com.returensea.agent.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationParsersTest {

    @Test
    void extractPhone_mobileNumberKeyword() {
        assertThat(RegistrationParsers.extractPhone("是的，姓名：liulinjie，手机号：12312312"))
                .isEqualTo("12312312");
    }

    @Test
    void extractPhone_mainlandMobile() {
        assertThat(RegistrationParsers.extractPhone("电话13800138000"))
                .isEqualTo("13800138000");
    }

    @Test
    void extractName_colonAfterXingming() {
        assertThat(RegistrationParsers.extractName("是的，姓名：liulinjie，手机号：123"))
                .isEqualTo("liulinjie");
    }

    @Test
    void extractName_woShiZhangSan() {
        assertThat(RegistrationParsers.extractName("我是张三，电话是13800138000"))
                .isEqualTo("张三");
    }

    @Test
    void extractActivityOrdinal_chineseThird() {
        assertThat(RegistrationParsers.extractActivityOrdinalOrNumericToken("参加活动第三个"))
                .isEqualTo("3");
    }

    @Test
    void extractActivityOrdinal_digit() {
        assertThat(RegistrationParsers.extractActivityOrdinalOrNumericToken("报名第2个活动"))
                .isEqualTo("2");
    }

    @Test
    void looksLikeRegisterSubmit_yes() {
        assertThat(RegistrationParsers.looksLikeRegisterSubmit("是的")).isTrue();
    }
}
