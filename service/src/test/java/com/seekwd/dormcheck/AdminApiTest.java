package com.seekwd.dormcheck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seekwd.dormcheck.config.DormCheckApplication;
import com.seekwd.dormcheck.engine.CheckEngine;
import com.seekwd.dormcheck.security.SecuritySupport;
import com.seekwd.dormcheck.service.SessionService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(classes = DormCheckApplication.class, properties = {
    "spring.datasource.url=jdbc:sqlite:./target/dormcheck-admin-integration.sqlite3",
    "dormcheck.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "dormcheck.cookie-secure=false",
    "dormcheck.queue-worker-enabled=false",
    "dormcheck.worker=../worker/worker.py",
    "dormcheck.engine=../engine/goToBed.py"
})
@AutoConfigureMockMvc
class AdminApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecuritySupport security;
    @MockitoBean CheckEngine engine;
    @MockitoBean JavaMailSender mail;

    @BeforeEach
    void setUp() {
        doNothing().when(mail).send(any(SimpleMailMessage.class));
        doNothing().when(mail).send(any(MimeMessage.class));
        org.mockito.Mockito.when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
        jdbc.update("DELETE FROM admin_audit_logs");
        jdbc.update("DELETE FROM admin_accounts");
        jdbc.update("DELETE FROM feature_usage_events");
        jdbc.update("DELETE FROM check_jobs");
        jdbc.update("DELETE FROM rate_limits");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM auth_device_sessions");
        jdbc.update("DELETE FROM auth_devices");
        jdbc.update("DELETE FROM credentials");
        jdbc.update("DELETE FROM schedules");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM email_codes");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void adminViewsRequireAllowlistedNetworkAdminRoleAndTotp() throws Exception {
        MvcResult regular = register("regular@qq.com");
        mvc.perform(get("/api/admin/overview").cookie(regular.getResponse().getCookie(SessionService.COOKIE_NAME)))
            .andExpect(status().isForbidden());

        MvcResult admin = register("admin@qq.com");
        String adminId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(admin.getResponse().getContentAsByteArray()).at("/user/id").asText();
        jdbc.update("INSERT INTO admin_accounts(user_id,role,created_at) VALUES(?,?,?)", adminId, "owner", java.time.Instant.now().toString());
        jdbc.update("INSERT INTO user_totp(user_id,secret_ciphertext,enabled,updated_at) VALUES(?,?,1,?)",
            adminId, new byte[] { 1, 2, 3 }, java.time.Instant.now().toString());

        mvc.perform(get("/api/admin/overview").cookie(admin.getResponse().getCookie(SessionService.COOKIE_NAME)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.admin.role").value("owner"))
            .andExpect(jsonPath("$.users.total").value(2));

        RequestPostProcessor externalAddress = request -> { request.setRemoteAddr("198.51.100.23"); return request; };
        mvc.perform(get("/api/admin/overview").with(externalAddress).cookie(admin.getResponse().getCookie(SessionService.COOKIE_NAME)))
            .andExpect(status().isNotFound());
    }

    @Test
    void ownersCanAssignRolesWithAuditedReason() throws Exception {
        MvcResult owner = register("owner@qq.com");
        MvcResult target = register("target@qq.com");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String ownerId = mapper.readTree(owner.getResponse().getContentAsByteArray()).at("/user/id").asText();
        String targetId = mapper.readTree(target.getResponse().getContentAsByteArray()).at("/user/id").asText();
        jdbc.update("INSERT INTO admin_accounts(user_id,role,created_at) VALUES(?,?,?)", ownerId, "owner", java.time.Instant.now().toString());
        jdbc.update("INSERT INTO user_totp(user_id,secret_ciphertext,enabled,updated_at) VALUES(?,?,1,?)",
            ownerId, new byte[] { 1, 2, 3 }, java.time.Instant.now().toString());

        mvc.perform(put("/api/admin/roles").cookie(owner.getResponse().getCookie(SessionService.COOKIE_NAME))
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", mapper.readTree(owner.getResponse().getContentAsByteArray()).get("csrf_token").asText())
                .contentType("application/json").content("{\"userId\":\"" + targetId + "\",\"role\":\"analyst\",\"reason\":\"Read-only reporting access\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("analyst"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT role FROM admin_accounts WHERE user_id=?", String.class, targetId)).isEqualTo("analyst");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action LIKE ?", Integer.class, "%" + targetId + "%")).isEqualTo(1);
    }

    private MvcResult register(String email) throws Exception {
        jdbc.update("INSERT INTO email_codes(email,purpose,code_hash,expires_at,attempts) VALUES(?,?,?,?,0)",
            email, "register", security.hashToken("123456"), java.time.Instant.now().plusSeconds(600).toString());
        return mvc.perform(post("/api/auth/register").header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-Device-ID", java.util.UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"long test password 123\",\"emailCode\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn();
    }
}
