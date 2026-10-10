package com.seekwd.dormcheck.service.impl;

final class RunNotificationEmail {
    private static final String IMAGE_CID = "dorm-result-image";

    private RunNotificationEmail() {}

    static Image successImage() {
        return new Image("mail/images/success/success_img_gz_1.png", "检查完成", "image/png");
    }

    static Image failureImage(String message) {
        if (message == null) return null;
        if (message.contains("验证码识别未通过") || message.contains("验证码错误")) {
            return failure("captcha_incorrect.jpg", "验证码没有通过");
        }
        if (message.contains("今天已经签到过") || message.contains("重复签到")) {
            return failure("already_checked.jpg", "今天已经签到过了");
        }
        if (message.contains("返回格式异常") || message.contains("响应解析失败")) {
            return failure("response_parse_error.jpg", "服务端响应异常");
        }
        if (message.contains("验证码处理失败") || message.contains("验证码计算异常")) {
            return failure("invalid_syntax.jpg", "验证码计算异常");
        }
        if (message.contains("所需文件缺失") || message.contains("文件不存在")) {
            return failure("file_not_found.jpg", "任务启动异常");
        }
        if (message.contains("允许的查寝时段") || message.contains("不在查寝时段") || message.contains("不在考勤时段")) {
            return failure("time_slot_invalid.jpg", "当前不在考勤时段");
        }
        return null;
    }

    static String html(boolean successful, String title, String message, String time, String duration, String runId) {
        String accent = successful ? "#26785c" : "#a24c4c";
        String statusBackground = successful ? "#eff8f3" : "#fbf2f1";
        String statusBorder = successful ? "#c7e4d4" : "#edcfcc";
        String heading = successful ? "今晚的查寝报告" : "查寝任务未完成";
        String subtitle = successful ? "夜间宿舍巡查记录" : "NIGHT INSPECTION / INCIDENT REPORT";
        String status = successful ? "检查完成" : title;

        String template = """
            <!doctype html>
            <html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>GZIST Dorm Check</title></head>
            <body style="margin:0;padding:0;background:#edf3f8;color:#30465a;font-family:Arial,'Microsoft YaHei','PingFang SC',sans-serif;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#edf3f8;padding:24px 10px;">
                <tr><td align="center">
                  <table role="presentation" width="700" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:700px;background:#fff;border:1px solid #d8e2eb;border-radius:12px;overflow:hidden;">
                    <tr><td style="padding:28px 30px;background:#f8fbfd;border-bottom:1px solid #e2eaf0;">
                      <div style="font-size:10px;letter-spacing:2px;color:#6f899e;">GZIST / DORM CHECK SYSTEM</div>
                      <div style="margin-top:10px;font-size:26px;line-height:1.35;font-weight:700;color:#284a67;">{{heading}}</div>
                      <div style="margin-top:6px;font-size:13px;color:#74899b;">{{subtitle}}</div>
                    </td></tr>
                    <tr><td style="padding:22px 24px 8px;">
                      <div style="padding-left:11px;border-left:3px solid {{accent}};font-size:11px;color:#71869a;">{{imageLabel}}</div>
                      <div style="margin-top:12px;border:1px solid #dce5ec;border-radius:8px;overflow:hidden;">
                        <img src="cid:{{cid}}" alt="Dorm assistant report" width="652" style="display:block;width:100%;max-width:652px;height:auto;margin:0 auto;border:0;">
                      </div>
                    </td></tr>
                    <tr><td style="padding:18px 24px 0;">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:{{statusBackground}};border:1px solid {{statusBorder}};border-radius:8px;">
                        <tr><td style="padding:15px 17px;">
                          <div style="font-size:16px;font-weight:700;color:{{accent}};">{{status}}</div>
                          <div style="margin-top:5px;font-size:13px;line-height:1.7;color:#526778;">{{message}}</div>
                        </td></tr>
                      </table>
                    </td></tr>
                    <tr><td style="padding:22px 24px 8px;font-size:11px;font-weight:700;letter-spacing:1px;color:#71869a;">执行详情</td></tr>
                    <tr><td style="padding:0 24px 24px;">
                      <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-top:1px solid #e6edf2;">
                        <tr><td style="padding:11px 0;border-bottom:1px solid #e6edf2;font-size:13px;color:#71869a;">执行时间</td><td align="right" style="padding:11px 0;border-bottom:1px solid #e6edf2;font-size:13px;color:#30465a;">{{time}}</td></tr>
                        <tr><td style="padding:11px 0;border-bottom:1px solid #e6edf2;font-size:13px;color:#71869a;">执行耗时</td><td align="right" style="padding:11px 0;border-bottom:1px solid #e6edf2;font-size:13px;color:#30465a;">{{duration}}</td></tr>
                        <tr><td style="padding:11px 0;font-size:13px;color:#71869a;">记录编号</td><td align="right" style="padding:11px 0;font-size:12px;color:#526778;word-break:break-all;">{{runId}}</td></tr>
                      </table>
                    </td></tr>
                    <tr><td style="padding:15px 24px;background:#f8fbfd;border-top:1px solid #e2eaf0;font-size:11px;line-height:1.7;color:#7b8d9c;">如非本人操作，请登录查寝中心检查账号和定时任务设置。<br>DORM ASSISTANT / CQ-01</td></tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """;
        return template
            .replace("{{heading}}", heading)
            .replace("{{subtitle}}", subtitle)
            .replace("{{imageLabel}}", successful ? "CHARACTER / DORM ASSISTANT" : "INCIDENT / CHARACTER REPORT")
            .replace("{{accent}}", accent)
            .replace("{{cid}}", IMAGE_CID)
            .replace("{{statusBackground}}", statusBackground)
            .replace("{{statusBorder}}", statusBorder)
            .replace("{{status}}", status)
            .replace("{{message}}", escape(message))
            .replace("{{time}}", escape(time))
            .replace("{{duration}}", escape(duration))
            .replace("{{runId}}", escape(runId));
    }

    private static Image failure(String fileName, String title) {
        return new Image("mail/images/failure/" + fileName, title, "image/jpeg");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    record Image(String path, String title, String contentType) {}
}
