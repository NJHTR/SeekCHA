package com.seekwd.dormcheck.service.impl;

import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunNotificationEmailTest {
    @Test
    void failureMessagesSelectTheMatchingOriginalImage() {
        assertImage("验证码识别未通过", "captcha_incorrect.jpg");
        assertImage("今天已经签到过，本次没有重复提交", "already_checked.jpg");
        assertImage("返回格式异常", "response_parse_error.jpg");
        assertImage("验证码处理失败", "invalid_syntax.jpg");
        assertImage("运行所需文件缺失", "file_not_found.jpg");
        assertImage("不在校园平台允许的查寝时段内", "time_slot_invalid.jpg");
        assertThat(RunNotificationEmail.failureImage("校园平台未确认查寝成功，请查看校园平台状态。")).isNull();
        assertThat(new ClassPathResource(RunNotificationEmail.successImage().path()).exists()).isTrue();
    }

    @Test
    void htmlEscapesDynamicTextAndReferencesTheInlineImage() {
        String html = RunNotificationEmail.html(false, "错误", "<img src=x onerror=alert(1)>",
            "2026-10-10 21:40:00", "1.2 秒", "run-1");
        assertThat(html).contains("cid:dorm-result-image", "&lt;img src=x onerror=alert(1)&gt;")
            .doesNotContain("<img src=x onerror=alert(1)>");
    }

    private static void assertImage(String message, String fileName) {
        RunNotificationEmail.Image image = RunNotificationEmail.failureImage(message);
        assertThat(image.path()).endsWith("/" + fileName);
        assertThat(new ClassPathResource(image.path()).exists()).isTrue();
    }
}
