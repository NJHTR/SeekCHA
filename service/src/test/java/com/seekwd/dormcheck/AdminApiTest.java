package com.seekwd.dormcheck;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekwd.dormcheck.config.DormCheckApplication;
import com.seekwd.dormcheck.engine.CheckEngine;
import com.seekwd.dormcheck.security.SecuritySupport;
import com.seekwd.dormcheck.service.SessionService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.Cookie;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Properties;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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

@SpringBootTest(classes = DormCheckApplication.class, properties = {
    "spring.datasource.url=jdbc:sqlite:./target/dormcheck-admin-integration.sqlite3",
    "dormcheck.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "dormcheck.cookie-secure=false",
    "dormcheck.queue-worker-enabled=false", "dormcheck.worker=../worker/worker.py", "dormcheck.engine=../engine/goToBed.py",
    "dormcheck.admin-bootstrap-token=test-bootstrap"
})
@AutoConfigureMockMvc
class AdminApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecuritySupport security;
    @MockitoBean CheckEngine engine;
    @MockitoBean JavaMailSender mail;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        doNothing().when(mail).send(any(SimpleMailMessage.class));
        doNothing().when(mail).send(any(MimeMessage.class));
        org.mockito.Mockito.when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
        jdbc.update("DELETE FROM admin_sessions"); jdbc.update("DELETE FROM admin_audit_logs"); jdbc.update("DELETE FROM admin_users");
        jdbc.update("DELETE FROM admin_accounts"); jdbc.update("DELETE FROM feature_usage_events"); jdbc.update("DELETE FROM check_jobs");
        jdbc.update("DELETE FROM rate_limits"); jdbc.update("DELETE FROM user_totp"); jdbc.update("DELETE FROM auth_device_sessions");
        jdbc.update("DELETE FROM auth_devices"); jdbc.update("DELETE FROM credentials"); jdbc.update("DELETE FROM schedules");
        jdbc.update("DELETE FROM sessions"); jdbc.update("DELETE FROM email_codes"); jdbc.update("DELETE FROM users");
    }

    @Test
    void ordinaryUserSessionCannotAccessAdminApi() throws Exception {
        MvcResult regular = register("regular@qq.com");
        String userId = mapper.readTree(regular.getResponse().getContentAsByteArray()).at("/user/id").asText();
        jdbc.update("INSERT INTO admin_accounts(user_id,role,created_at) VALUES(?,?,?)", userId, "owner", Instant.now().toString());
        jdbc.update("INSERT INTO user_totp(user_id,secret_ciphertext,enabled,updated_at) VALUES(?,?,1,?)", userId, new byte[] { 1 }, Instant.now().toString());
        mvc.perform(get("/api/admin/overview").cookie(regular.getResponse().getCookie(SessionService.COOKIE_NAME))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/session")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void privateAdminUsesSeparatePasswordTotpAndCookie() throws Exception {
        JsonNode bootstrap = mapper.readTree(mvc.perform(post("/api/admin/auth/bootstrap").header("Origin", "http://localhost").header("Host", "localhost").contentType("application/json")
            .content("{\"token\":\"test-bootstrap\",\"email\":\"njhtr1018@gmail.com\",\"name\":\"NJHTR\",\"password\":\"a-very-long-admin-password\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String code = totp(decodeBase32(bootstrap.get("secret").asText()), Instant.now().getEpochSecond() / 30);
        mvc.perform(post("/api/admin/auth/bootstrap/confirm").header("Origin", "http://localhost").header("Host", "localhost").contentType("application/json")
            .content("{\"token\":\"test-bootstrap\",\"email\":\"njhtr1018@gmail.com\",\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk());
        MvcResult login = mvc.perform(post("/api/admin/auth/login").header("Origin", "http://localhost").header("Host", "localhost").contentType("application/json")
            .content("{\"email\":\"njhtr1018@gmail.com\",\"password\":\"a-very-long-admin-password\",\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.admin.role").value("owner")).andReturn();
        mvc.perform(get("/api/admin/overview").cookie(login.getResponse().getCookie("dormcheck_admin_session")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.admin.role").value("owner"));
        mvc.perform(get("/api/admin/overview").cookie(new Cookie(SessionService.COOKIE_NAME, "not-an-admin-token"))).andExpect(status().isUnauthorized());
        String adminCsrf = mapper.readTree(login.getResponse().getContentAsByteArray()).get("csrf_token").asText();
        JsonNode newAdmin = mapper.readTree(mvc.perform(post("/api/admin/accounts").header("Origin", "http://localhost").header("Host", "localhost")
            .header("X-CSRF-Token", adminCsrf).cookie(login.getResponse().getCookie("dormcheck_admin_session")).contentType("application/json")
            .content("{\"email\":\"operator@example.com\",\"name\":\"Operator\",\"password\":\"a-different-long-admin-password\",\"role\":\"analyst\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        String newAdminCode = totp(decodeBase32(newAdmin.get("secret").asText()), Instant.now().getEpochSecond() / 30);
        mvc.perform(post("/api/admin/accounts/totp/confirm").header("Origin", "http://localhost").header("Host", "localhost")
            .header("X-CSRF-Token", adminCsrf).cookie(login.getResponse().getCookie("dormcheck_admin_session")).contentType("application/json")
            .content("{\"email\":\"operator@example.com\",\"code\":\"" + newAdminCode + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT role FROM admin_users WHERE email=?", String.class, "operator@example.com")).isEqualTo("analyst");
    }

    private MvcResult register(String email) throws Exception {
        jdbc.update("INSERT INTO email_codes(email,purpose,code_hash,expires_at,attempts) VALUES(?,?,?,?,0)", email, "register", security.hashToken("123456"), Instant.now().plusSeconds(600).toString());
        return mvc.perform(post("/api/auth/register").header("Origin", "http://localhost").header("Host", "localhost")
            .header("X-Device-ID", java.util.UUID.randomUUID().toString()).contentType("application/json")
            .content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"long test password 123\",\"emailCode\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn();
    }

    private static byte[] decodeBase32(String value) { String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"; int buffer = 0, bits = 0; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); for (char c : value.toCharArray()) { buffer = (buffer << 5) | alphabet.indexOf(c); bits += 5; if (bits >= 8) { out.write((buffer >> (bits - 8)) & 255); bits -= 8; } } return out.toByteArray(); }
    private static String totp(byte[] secret, long counter) throws Exception { Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret, "HmacSHA1")); byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array()); int offset = digest[digest.length - 1] & 15; int binary = ((digest[offset] & 127) << 24) | ((digest[offset + 1] & 255) << 16) | ((digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255); return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000); }
}
