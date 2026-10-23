package com.seekwd.dormcheck;
import com.seekwd.dormcheck.engine.CheckEngine;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.SecuritySupport;
import com.seekwd.dormcheck.service.RateLimiter;
import com.seekwd.dormcheck.service.SessionService;
import com.seekwd.dormcheck.service.DormCheckService;
import com.seekwd.dormcheck.config.DormCheckApplication;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.servlet.http.Cookie;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

@SpringBootTest(classes = DormCheckApplication.class, properties = {
    "spring.datasource.url=jdbc:sqlite:./target/dormcheck-integration.sqlite3",
    "dormcheck.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "dormcheck.cookie-secure=false",
    "dormcheck.queue-worker-enabled=false",
    "dormcheck.worker=../worker/worker.py",
    "dormcheck.engine=../engine/goToBed.py"
})
@AutoConfigureMockMvc
class DormCheckApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SecuritySupport security;
    @Autowired RateLimiter limiter;
    @Autowired DormCheckService dormCheckService;
    @Autowired com.seekwd.dormcheck.service.CheckQueueService checkQueue;
    @Autowired com.seekwd.dormcheck.service.CampusAccessGuard campusAccess;
    @Autowired com.seekwd.dormcheck.service.XiangqiService xiangqiService;
    @MockitoBean CheckEngine engine;
    @MockitoBean JavaMailSender mail;

    @org.junit.jupiter.api.BeforeEach
    void allowMockEmailDelivery() {
        doNothing().when(mail).send(any(SimpleMailMessage.class));
        doNothing().when(mail).send(any(MimeMessage.class));
        when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @BeforeEach
    void clearDatabase() {
        jdbc.update("DELETE FROM xiangqi_match_queue");
        jdbc.update("DELETE FROM xiangqi_messages");
        jdbc.update("DELETE FROM xiangqi_ad_sessions");
        jdbc.update("DELETE FROM xiangqi_game_entries");
        jdbc.update("DELETE FROM xiangqi_ledger");
        jdbc.update("DELETE FROM xiangqi_wallets");
        jdbc.update("DELETE FROM xiangqi_games");
        jdbc.update("DELETE FROM check_jobs");
        jdbc.update("UPDATE check_queue_state SET not_before=0,lease_until=0,lease_token=NULL,cooldown_until=0,transient_failures=0 WHERE id=1");
        jdbc.update("DELETE FROM rate_limits");
        jdbc.update("DELETE FROM auth_device_sessions");
        jdbc.update("DELETE FROM auth_devices");
        jdbc.update("DELETE FROM email_codes");
        jdbc.update("DELETE FROM user_totp");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM runs");
        jdbc.update("DELETE FROM credentials");
        jdbc.update("DELETE FROM academic_cache");
        jdbc.update("DELETE FROM schedules");
        jdbc.update("DELETE FROM sessions");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void academicReadsUseCacheAndControlledRefresh() throws Exception {
        Map<String, Object> notice = Map.of("title", "公告", "date", "2026-10-11", "url", "/notice");
        when(engine.notices(anyString(), anyString())).thenReturn(
            Map.of("ok", true, "items", java.util.List.of(notice), "data_complete", true));
        MvcResult registered = register("academic-cache@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        mvc.perform(put("/api/credentials").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", json(registered).get("csrf_token").asText())
                .contentType("application/json").content("{\"account\":\"academic-student\",\"password\":\"academic-password\"}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/academic/notices").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/academic/notices").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("公告"));
        verify(engine, times(1)).notices(anyString(), anyString());

        // The campus access gate enforces a cooldown between upstream calls.
        // Reset the durable lease as well so this test can exercise refresh
        // without waiting for the production interval.
        jdbc.update("UPDATE check_queue_state SET not_before=0,lease_until=0,lease_token=NULL,cooldown_until=0 WHERE id=1");
        when(engine.notices(anyString(), anyString())).thenReturn(Map.of("ok", true, "items", java.util.List.of(), "data_complete", true));
        mvc.perform(get("/api/academic/notices?refresh=true").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        verify(engine, times(2)).notices(anyString(), anyString());

        jdbc.update("UPDATE academic_cache SET fresh_until=0 WHERE cache_type='notices'");
        when(engine.notices(anyString(), anyString())).thenReturn(Map.of("ok", true, "items", java.util.List.of(notice)));
        jdbc.update("UPDATE check_queue_state SET not_before=0,lease_until=0,lease_token=NULL,cooldown_until=0 WHERE id=1");
        mvc.perform(get("/api/academic/notices?refresh=true").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
            .andExpect(jsonPath("$.cache_status").value("stale-fallback"));
        verify(engine, times(3)).notices(anyString(), anyString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM academic_cache", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT payload FROM academic_cache", String.class)).contains("\"items\":[]");
    }

    @Test
    void xiangqiSupportsOnlineRoomsAndServerValidatedMoves() throws Exception {
        MvcResult host = register("xiangqi-host@qq.com");
        Cookie hostCookie = host.getResponse().getCookie(SessionService.COOKIE_NAME);
        String hostCsrf = json(host).get("csrf_token").asText();
        MvcResult created = mvc.perform(post("/api/xiangqi/games").cookie(hostCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", hostCsrf))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("waiting"))
            .andExpect(jsonPath("$.my_color").value("red"))
            .andReturn();
        String gameId = json(created).get("id").asText();
        String roomCode = json(created).get("room_code").asText();
        org.assertj.core.api.Assertions.assertThat(json(created).get("clock").get("red_ms").asLong()).isEqualTo(90_000);
        org.assertj.core.api.Assertions.assertThat(json(created).get("clock").get("increment_ms").asLong()).isZero();
        String hostId = json(host).get("user").get("id").asText();

        MvcResult guest = register("xiangqi-guest@qq.com");
        String guestId = json(guest).get("user").get("id").asText();
        Cookie guestCookie = guest.getResponse().getCookie(SessionService.COOKIE_NAME);
        String guestCsrf = json(guest).get("csrf_token").asText();
        MvcResult joined = mvc.perform(post("/api/xiangqi/games/join").cookie(guestCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", guestCsrf).contentType("application/json")
                .content("{\"roomCode\":\"" + roomCode + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.my_color").value("black"))
            .andExpect(jsonPath("$.my_turn").value(false))
            .andExpect(jsonPath("$.clock.black_ms").value(90000))
            .andExpect(jsonPath("$.game_type").value("friend"))
            .andReturn();
        mvc.perform(get("/api/xiangqi/profile").cookie(hostCookie)).andExpect(status().isOk());
        mvc.perform(get("/api/xiangqi/profile").cookie(guestCookie)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT coins FROM xiangqi_wallets WHERE user_id=?", Integer.class, hostId)).isEqualTo(10000);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT coins FROM xiangqi_wallets WHERE user_id=?", Integer.class, guestId)).isEqualTo(10000);

        mvc.perform(get("/api/xiangqi/match").cookie(hostCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("matched"))
            .andExpect(jsonPath("$.game.id").value(gameId));

        MvcResult hostView = mvc.perform(get("/api/xiangqi/games/" + gameId).cookie(hostCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.my_turn").value(true))
            .andExpect(jsonPath("$.legal_moves.a3[0]").value("a4"))
            .andExpect(jsonPath("$.legal_moves.b2").isArray())
            .andReturn();
        JsonNode legalMoves = json(hostView).get("legal_moves");
        int legalMoveCount = 0;
        for (var targets : legalMoves) legalMoveCount += targets.size();
        org.assertj.core.api.Assertions.assertThat(legalMoveCount).isEqualTo(44);
        org.assertj.core.api.Assertions.assertThat(legalMoves.get("b2").size()).isEqualTo(12);
        org.assertj.core.api.Assertions.assertThat(legalMoves.get("c0").toString()).contains("a2", "e2");
        org.assertj.core.api.Assertions.assertThat(legalMoves.get("g0").toString()).contains("e2", "i2");

        mvc.perform(post("/api/xiangqi/games/" + gameId + "/moves").cookie(hostCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", hostCsrf).contentType("application/json")
                .content("{\"from\":\"b0\",\"to\":\"d1\",\"expectedVersion\":2}"))
            .andExpect(status().isUnprocessableEntity());

        MvcResult firstMove = mvc.perform(post("/api/xiangqi/games/" + gameId + "/moves").cookie(hostCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", hostCsrf).contentType("application/json")
                .content("{\"from\":\"a3\",\"to\":\"a4\",\"expectedVersion\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.turn").value("black"))
            .andExpect(jsonPath("$.clock.increment_ms").value(0))
            .andExpect(jsonPath("$.moves[0].piece").value("rP"))
            .andReturn();
        org.assertj.core.api.Assertions.assertThat(json(firstMove).get("version").asInt()).isEqualTo(3);

        mvc.perform(post("/api/xiangqi/games/" + gameId + "/moves").cookie(guestCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", guestCsrf).contentType("application/json")
                .content("{\"from\":\"a6\",\"to\":\"a5\",\"expectedVersion\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.turn").value("red"));

        mvc.perform(post("/api/xiangqi/games/" + gameId + "/moves").cookie(guestCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", guestCsrf).contentType("application/json")
                .content("{\"from\":\"b0\",\"to\":\"c2\",\"expectedVersion\":4}"))
            .andExpect(status().isConflict());

        mvc.perform(post("/api/xiangqi/games/" + gameId + "/resign").cookie(hostCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", hostCsrf).contentType("application/json")
                .content("{\"expectedVersion\":4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("finished"))
            .andExpect(jsonPath("$.winner_color").value("black"))
            .andExpect(jsonPath("$.finish_reason").value("resignation"));
    }

    @Test
    void xiangqiAdClaimUsesUserBoundTemporaryPathAndRejectsEarlyClaims() throws Exception {
        MvcResult registered = register("xiangqi-ad@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        MvcResult started = mvc.perform(post("/api/xiangqi/rewards/ad/start").cookie(cookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.claim_path").exists())
            .andExpect(jsonPath("$.mode").value("demo")).andReturn();
        String claimPath = json(started).get("claim_path").asText();
        org.assertj.core.api.Assertions.assertThat(claimPath).matches("/xiangqi/rewards/ad/[0-9a-f-]{36}/claim");

        mvc.perform(post("/api" + claimPath).cookie(cookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", csrf))
            .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT coins FROM xiangqi_wallets WHERE user_id=(SELECT id FROM users WHERE email=?)", Integer.class, "xiangqi-ad@qq.com"))
            .isEqualTo(10000);
    }

    @Test
    void xiangqiClockIsServerAuthoritativeAndSettlesTimeoutOnce() throws Exception {
        MvcResult host = register("xiangqi-clock-host@qq.com");
        Cookie hostCookie = host.getResponse().getCookie(SessionService.COOKIE_NAME);
        String hostCsrf = json(host).get("csrf_token").asText();
        MvcResult room = mvc.perform(post("/api/xiangqi/games").cookie(hostCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", hostCsrf))
            .andExpect(status().isOk()).andReturn();
        String gameId = json(room).get("id").asText();
        String roomCode = json(room).get("room_code").asText();

        MvcResult guest = register("xiangqi-clock-guest@qq.com");
        Cookie guestCookie = guest.getResponse().getCookie(SessionService.COOKIE_NAME);
        String guestCsrf = json(guest).get("csrf_token").asText();
        mvc.perform(post("/api/xiangqi/games/join").cookie(guestCookie)
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", guestCsrf).contentType("application/json")
                .content("{\"roomCode\":\"" + roomCode + "\"}"))
            .andExpect(status().isOk());

        jdbc.update("UPDATE xiangqi_games SET turn_started_at=? WHERE id=?",
            java.time.Instant.now().minusSeconds(91).toString(), gameId);
        mvc.perform(get("/api/xiangqi/games/" + gameId).cookie(hostCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("finished"))
            .andExpect(jsonPath("$.finish_reason").value("timeout"))
            .andExpect(jsonPath("$.winner_color").value("black"));

        mvc.perform(get("/api/xiangqi/games/" + gameId).cookie(hostCookie)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT coins FROM xiangqi_wallets WHERE user_id=(SELECT id FROM users WHERE email=?)", Integer.class, "xiangqi-clock-host@qq.com"))
            .isEqualTo(10000);
    }

    @Test
    void xiangqiRankedGamesChargeAndUseEloForRating() throws Exception {
        MvcResult first = register("xiangqi-ranked-a@qq.com");
        MvcResult second = register("xiangqi-ranked-b@qq.com");
        String red = json(first).get("user").get("id").asText();
        String black = json(second).get("user").get("id").asText();
        Cookie redCookie = first.getResponse().getCookie(SessionService.COOKIE_NAME);
        String redCsrf = json(first).get("csrf_token").asText();
        Cookie blackCookie = second.getResponse().getCookie(SessionService.COOKIE_NAME);
        String blackCsrf = json(second).get("csrf_token").asText();
        mvc.perform(get("/api/xiangqi/profile").cookie(redCookie)).andExpect(status().isOk());
        mvc.perform(get("/api/xiangqi/profile").cookie(blackCookie)).andExpect(status().isOk());
        mvc.perform(post("/api/xiangqi/match").cookie(redCookie)
                .header("Origin", "http://localhost").header("Host", "localhost").header("X-CSRF-Token", redCsrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("searching"));
        MvcResult matched = mvc.perform(post("/api/xiangqi/match").cookie(blackCookie)
                .header("Origin", "http://localhost").header("Host", "localhost").header("X-CSRF-Token", blackCsrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("matched"))
            .andExpect(jsonPath("$.game.game_type").value("ranked")).andReturn();
        String gameId = json(matched).get("game").get("id").asText();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM xiangqi_game_entries WHERE game_id=?", Integer.class, gameId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT coins FROM xiangqi_wallets WHERE user_id=?", Integer.class, red)).isEqualTo(7500);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT coins FROM xiangqi_wallets WHERE user_id=?", Integer.class, black)).isEqualTo(7500);
        boolean secondIsRed = "red".equals(json(matched).get("game").get("my_color").asText());
        String redUser = secondIsRed ? black : red;
        String blackUser = secondIsRed ? red : black;
        String resignCookie = secondIsRed ? redCookie.getValue() : blackCookie.getValue();
        String resignCsrf = secondIsRed ? redCsrf : blackCsrf;
        int version = json(matched).get("game").get("version").asInt();
        mvc.perform(post("/api/xiangqi/games/" + gameId + "/resign").cookie(new Cookie(SessionService.COOKIE_NAME, resignCookie))
                .header("Origin", "http://localhost").header("Host", "localhost").header("X-CSRF-Token", resignCsrf)
                .contentType("application/json").content("{\"expectedVersion\":" + version + "}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("finished"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT rating FROM xiangqi_wallets WHERE user_id=?", Integer.class, redUser)).isEqualTo(1020);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT rating FROM xiangqi_wallets WHERE user_id=?", Integer.class, blackUser)).isEqualTo(980);
    }

    @Test
    void accountsKeepCredentialsPrivateAndRequireVerifiedIdentityForSchedules() throws Exception {
        when(engine.verify(anyString(), anyString())).thenReturn(Map.of("verified", true, "message", "校园平台登录验证成功。"));

        MvcResult first = register("one@qq.com");
        String csrf = json(first).get("csrf_token").asText();
        Cookie cookie = first.getResponse().getCookie(SessionService.COOKIE_NAME);

        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/dashboard").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.credential.configured").value(false));

        MvcResult unsaved = mvc.perform(put("/api/schedule").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"enabled\":true,\"run_time\":\"21:40\",\"days_of_week\":[1,2,3,4,5,6,7],\"timezone\":\"Asia/Shanghai\"}"))
            .andExpect(status().isConflict()).andReturn();
        org.assertj.core.api.Assertions.assertThat(unsaved.getResponse().getContentAsString()).contains("account is not configured");

        String campusPassword = "sensitive-campus-password";
        MvcResult stored = mvc.perform(put("/api/credentials").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"account\":\"student-123\",\"password\":\"" + campusPassword + "\"}"))
            .andExpect(status().isOk()).andReturn();
        org.assertj.core.api.Assertions.assertThat(stored.getResponse().getContentAsString()).doesNotContain(campusPassword);
        byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM credentials WHERE user_id=?",
            byte[].class, json(first).at("/user/id").asText());
        org.assertj.core.api.Assertions.assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(campusPassword);

        mvc.perform(post("/api/credentials/verify").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        mvc.perform(put("/api/schedule").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"enabled\":true,\"run_time\":\"21:40\",\"days_of_week\":[1,3,5],\"timezone\":\"Asia/Shanghai\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.days_of_week[0]").value(1))
            .andExpect(jsonPath("$.days_of_week[1]").value(3))
            .andExpect(jsonPath("$.days_of_week[2]").value(5));
        mvc.perform(get("/api/dashboard").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.schedule.days_of_week[0]").value(1))
            .andExpect(jsonPath("$.schedule.days_of_week[1]").value(3))
            .andExpect(jsonPath("$.schedule.days_of_week[2]").value(5));

        register("two@qq.com");
        MvcResult loginPending = mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"two@qq.com\",\"password\":\"long test password 123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.verification_required").value(true)).andReturn();
        org.assertj.core.api.Assertions.assertThat(loginPending.getResponse().getCookie(SessionService.COOKIE_NAME)).isNull();
        jdbc.update("UPDATE email_codes SET code_hash=?,expires_at=?,attempts=0 WHERE email=? AND purpose='login'",
            security.hashToken("654321"), java.time.Instant.now().plusSeconds(600).toString(), "two@qq.com");
        Cookie second = mvc.perform(post("/api/auth/verify").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"two@qq.com\",\"emailCode\":\"654321\",\"totpCode\":\"\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie(SessionService.COOKIE_NAME);
        mvc.perform(get("/api/dashboard").cookie(second))
            .andExpect(status().isOk()).andExpect(jsonPath("$.credential.configured").value(false))
            .andExpect(jsonPath("$.schedule.enabled").value(false));
    }

    @Test
    void mutationsRejectMissingCsrfAndWrongOrigin() throws Exception {
        MvcResult registered = register("csrf@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        mvc.perform(put("/api/credentials").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").contentType("application/json")
                .content("{\"account\":\"student\",\"password\":\"pass\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(cookie).header("Origin", "http://attacker.example")
                .header("Host", "localhost").header("X-CSRF-Token", json(registered).get("csrf_token").asText()))
            .andExpect(status().isForbidden());
    }

    @Test
    void notificationInboxIsPrivateAndTracksReadState() throws Exception {
        MvcResult first = register("notify-one@qq.com");
        String userId = json(first).at("/user/id").asText();
        Cookie firstCookie = first.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(first).get("csrf_token").asText();
        String older = java.time.Instant.now().minusSeconds(60).toString();
        String newer = java.time.Instant.now().toString();
        jdbc.update("INSERT INTO runs(id,user_id,status,message,started_at,duration_ms) VALUES(?,?,?,?,?,?)",
            "notify-success", userId, "success", "查寝已完成", older, 1200);
        jdbc.update("INSERT INTO notifications(id,user_id,run_id,created_at,read_at) VALUES(?,?,?,?,NULL)",
            "notify-success", userId, "notify-success", older);
        jdbc.update("INSERT INTO runs(id,user_id,status,message,started_at,duration_ms) VALUES(?,?,?,?,?,?)",
            "notify-failure", userId, "failure", "校园平台未确认", newer, 2400);
        jdbc.update("INSERT INTO notifications(id,user_id,run_id,created_at,read_at) VALUES(?,?,?,?,NULL)",
            "notify-failure", userId, "notify-failure", newer);

        MvcResult second = register("notify-two@qq.com");
        Cookie secondCookie = second.getResponse().getCookie(SessionService.COOKIE_NAME);
        mvc.perform(get("/api/notifications").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unread_count").value(2))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].status").value("failure"))
            .andExpect(jsonPath("$.items[0].read").value(false));
        mvc.perform(get("/api/notifications").cookie(secondCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unread_count").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/api/notifications/notify-failure/read").cookie(secondCookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", json(second).get("csrf_token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(false));
        mvc.perform(put("/api/notifications/notify-failure/read").cookie(firstCookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(true));
        mvc.perform(get("/api/notifications").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unread_count").value(1));
        mvc.perform(put("/api/notifications/read").cookie(firstCookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/api/notifications").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unread_count").value(0))
            .andExpect(jsonPath("$.items[0].read").value(true));
    }

    @Test
    void checkHistoryIsPaginatedFilteredAndScopedToTheCurrentUser() throws Exception {
        MvcResult first = register("history-one@qq.com");
        String firstUserId = json(first).at("/user/id").asText();
        Cookie firstCookie = first.getResponse().getCookie(SessionService.COOKIE_NAME);
        for (int i = 0; i < 125; i++) {
            jdbc.update("INSERT INTO runs(id,user_id,status,message,started_at,duration_ms) VALUES(?,?,?,?,?,?)",
                "history-" + i, firstUserId, i % 2 == 0 ? "success" : "failure", "result " + i,
                java.time.Instant.now().minusSeconds(i).toString(), 1000 + i);
        }
        MvcResult second = register("history-two@qq.com");
        Cookie secondCookie = second.getResponse().getCookie(SessionService.COOKIE_NAME);
        mvc.perform(get("/api/checks").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(125))
            .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.page_size").value(20))
            .andExpect(jsonPath("$.total_pages").value(7)).andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.items[0].id").value("history-0"));
        mvc.perform(get("/api/checks?page=7").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[0].id").value("history-120"))
            .andExpect(jsonPath("$.items[4].id").value("history-124"));
        mvc.perform(get("/api/checks?status=success").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(63))
            .andExpect(jsonPath("$.items[0].status").value("success"));
        mvc.perform(get("/api/checks").cookie(secondCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/checks?page=0").cookie(firstCookie))
            .andExpect(status().isBadRequest());

        mvc.perform(get("/api/dashboard").cookie(firstCookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runs.length()").value(4))
            .andExpect(jsonPath("$.stats.total").value(125))
            .andExpect(jsonPath("$.stats.success_count").value(63))
            .andExpect(jsonPath("$.stats.recent_total").value(125))
            .andExpect(jsonPath("$.stats.recent_success_count").value(63))
            .andExpect(jsonPath("$.stats.success_rate").value(50));
    }

    @Test
    void registrationOnlyAcceptsCommonMailboxDomains() throws Exception {
        mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"throwaway@random-mail.invalid\"}"))
            .andExpect(status().isUnprocessableEntity());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_codes", Integer.class)).isZero();
        mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"valid@qq.com\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(true));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_codes WHERE email='valid@qq.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void registrationCodeHasDistributedGlobalBudget() throws Exception {
        String[] domains = {"qq.com", "163.com", "126.com", "gmail.com", "outlook.com", "hotmail.com", "foxmail.com", "yeah.net", "icloud.com", "aliyun.com"};
        for (int index = 0; index < domains.length; index++) {
            mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                    .contentType("application/json").content("{\"email\":\"spray-" + index + "@" + domains[index] + "\"}"))
                .andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"spray-final@qq.com\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void loginCodesHaveDistributedGlobalBudget() throws Exception {
        var digest = security.passwordDigest("login code spray test password");
        for (int index = 0; index < 20; index++) {
            String email = "login-spray-" + index + "@gmail.com";
            jdbc.update("INSERT INTO users(id,email,display_name,password_salt,password_hash,created_at) VALUES(?,?,?,?,?,?)",
                java.util.UUID.randomUUID().toString(), email, "Test", digest.salt(), digest.hash(), security.now());
            String ip = "198.51.100." + (index + 1);
            mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                    .with(request -> { request.setRemoteAddr(ip); return request; })
                    .contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"login code spray test password\"}"))
                .andExpect(status().isOk());
        }
        jdbc.update("INSERT INTO users(id,email,display_name,password_salt,password_hash,created_at) VALUES(?,?,?,?,?,?)",
            java.util.UUID.randomUUID().toString(), "login-spray-final@gmail.com", "Test", digest.salt(), digest.hash(), security.now());
        mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"login-spray-final@gmail.com\",\"password\":\"login code spray test password\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void duplicateSessionCookiesAreRejectedAndTotpCannotBeSilentlyReset() throws Exception {
        MvcResult registered = register("secure-session@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        mvc.perform(get("/api/auth/session").cookie(cookie, new Cookie(SessionService.COOKIE_NAME, "attacker-value")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));

        String setup = mvc.perform(post("/api/auth/totp/setup").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(setup).get("secret").asText();
        mvc.perform(post("/api/auth/totp/confirm").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf).contentType("application/json")
                .content("{\"code\":\"" + totp(secret) + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/auth/totp/setup").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT enabled FROM user_totp WHERE user_id=?", Integer.class, json(registered).at("/user/id").asText())).isEqualTo(1);
    }

    @Test
    void checkResultsSendSuccessAndFailureNotificationsToTheAccountEmail() throws Exception {
        when(engine.check(anyString(), anyString())).thenReturn(
            Map.of("status", "success", "message", "查寝完成。", "duration_ms", 1200),
            Map.of("status", "failure", "message", "当前不在校园平台允许的查寝时段内。", "duration_ms", 2300));
        MvcResult registered = register("check-notify@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        String userId = json(registered).at("/user/id").asText();
        mvc.perform(put("/api/credentials").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"account\":\"student-456\",\"password\":\"sensitive-campus-password\"}"))
            .andExpect(status().isOk());
        jdbc.update("UPDATE credentials SET verified_at=? WHERE user_id=?", java.time.Instant.now().toString(), userId);
        clearInvocations(mail);

        mvc.perform(post("/api/checks").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("queued"));
        checkQueue.processOne();
        jdbc.update("UPDATE check_jobs SET created_at=? WHERE user_id=?", java.time.Instant.now().minusSeconds(86400).toString(), userId);
        jdbc.update("UPDATE check_queue_state SET not_before=0 WHERE id=1");
        jdbc.update("UPDATE check_jobs SET started_at=? WHERE user_id=?", java.time.Instant.now().minusSeconds(600).toString(), userId);
        mvc.perform(post("/api/checks").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("queued"));
        checkQueue.processOne();

        ArgumentCaptor<MimeMessage> mimeMessages = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mail, times(2)).send(mimeMessages.capture());
        org.assertj.core.api.Assertions.assertThat(mimeMessages.getAllValues()).hasSize(2);
        MimeMessage success = mimeMessages.getAllValues().get(0);
        MimeMessage failure = mimeMessages.getAllValues().get(1);
        org.assertj.core.api.Assertions.assertThat(success.getAllRecipients()[0].toString()).isEqualTo("check-notify@qq.com");
        org.assertj.core.api.Assertions.assertThat(success.getSubject()).contains("查寝日报", "检查完成");
        org.assertj.core.api.Assertions.assertThat(failure.getAllRecipients()[0].toString()).isEqualTo("check-notify@qq.com");
        org.assertj.core.api.Assertions.assertThat(failure.getSubject()).contains("任务未完成", "当前不在考勤时段");
        String successRaw = serialize(success);
        String failureRaw = serialize(failure);
        org.assertj.core.api.Assertions.assertThat(successRaw)
            .contains("Content-Type: image/png", "Content-Type: text/html", "Content-ID: <dorm-result-image>", "cid:dorm-result-image")
            .doesNotContain("sensitive-campus-password");
        org.assertj.core.api.Assertions.assertThat(failureRaw)
            .contains("Content-Type: image/jpeg", "Content-Type: text/html", "Content-ID: <dorm-result-image>", "cid:dorm-result-image");
    }

    @Test
    void scheduledRunsArePersistedAlongsideTheirNotifications() throws Exception {
        when(engine.check(anyString(), anyString())).thenReturn(
            Map.of("status", "success", "message", "定时查寝完成。", "duration_ms", 900));
        MvcResult registered = register("scheduled-notify@qq.com");
        String userId = json(registered).at("/user/id").asText();
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        mvc.perform(put("/api/credentials").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf)
                .contentType("application/json").content("{\"account\":\"scheduled-student\",\"password\":\"scheduled-password\"}"))
            .andExpect(status().isOk());
        jdbc.update("UPDATE credentials SET verified_at=? WHERE user_id=?", java.time.Instant.now().toString(), userId);

        jdbc.update("UPDATE schedules SET enabled=1 WHERE user_id=?", userId);
        dormCheckService.runScheduled(userId);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs", Integer.class)).isZero();
        checkQueue.processOne();

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM runs WHERE user_id=? AND status='success'", Integer.class, userId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM notifications WHERE user_id=? AND type='run'", Integer.class, userId)).isEqualTo(1);
    }

    private static String serialize(MimeMessage message) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void queueDeduplicatesAndSerializesUsersWithAGlobalGap() throws Exception {
        String first = preparedUser("queue-one@qq.com");
        String second = preparedUser("queue-two@qq.com");
        when(engine.check(anyString(), anyString())).thenReturn(Map.of("status", "success", "message", "完成", "duration_ms", 10));
        clearInvocations(engine, mail);
        var job = checkQueue.enqueue(first, "manual");
        var duplicate = checkQueue.enqueue(first, "scheduled");
        org.assertj.core.api.Assertions.assertThat(duplicate.get("id")).isEqualTo(job.get("id"));
        org.assertj.core.api.Assertions.assertThat(duplicate.get("duplicate")).isEqualTo(true);
        checkQueue.enqueue(second, "manual");
        checkQueue.processOne();
        checkQueue.processOne();
        verify(engine, times(1)).check(anyString(), anyString());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM check_jobs WHERE status='queued'", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT not_before FROM check_queue_state", Long.class))
            .isGreaterThanOrEqualTo(System.currentTimeMillis() + 55_000);
        jdbc.update("UPDATE check_queue_state SET not_before=0");
        checkQueue.processOne();
        verify(engine, times(2)).check(anyString(), anyString());
        org.assertj.core.api.Assertions.assertThat(checkQueue.enqueue(first, "manual").get("status")).isEqualTo("success");
        verify(engine, times(2)).check(anyString(), anyString());
    }

    @Test
    void transientFailuresWaitFiveMinutesAndOnlyNotifyWhenFinished() throws Exception {
        String userId = preparedUser("queue-retry@qq.com");
        when(engine.check(anyString(), anyString())).thenReturn(Map.of("status", "failure", "error_code", "network", "message", "暂时连接失败", "duration_ms", 0));
        clearInvocations(engine, mail);
        checkQueue.enqueue(userId, "manual");
        long before = System.currentTimeMillis();
        checkQueue.processOne();
        var waiting = checkQueue.status(userId);
        org.assertj.core.api.Assertions.assertThat(waiting.get("status")).isEqualTo("retry_wait");
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse((String) waiting.get("not_before")).toEpochMilli()).isGreaterThanOrEqualTo(before + 300_000);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs", Integer.class)).isZero();
        verify(mail, times(0)).send(any(MimeMessage.class));
        checkQueue.processOne();
        verify(engine, times(1)).check(anyString(), anyString());
        for (int attempt = 2; attempt <= 3; attempt++) {
            jdbc.update("UPDATE check_jobs SET not_before=? WHERE user_id=?", "2000-01-01T00:00:00.000Z", userId);
            jdbc.update("UPDATE check_queue_state SET not_before=0");
            checkQueue.processOne();
        }
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(userId).get("active")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> checkQueue.enqueue(userId, "manual")).isInstanceOf(ApiException.class).hasMessageContaining("3 次");
        org.assertj.core.api.Assertions.assertThat(checkQueue.enqueue(userId, "scheduled").get("status")).isEqualTo("queued");
        verify(engine, times(3)).check(anyString(), anyString());
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void riskResponsesPauseAllUsersAndDoNotRetryTheRejectedTask() throws Exception {
        String first = preparedUser("queue-risk@qq.com");
        String second = preparedUser("queue-wait@qq.com");
        when(engine.check(anyString(), anyString())).thenReturn(Map.of("status", "failure", "error_code", "rate_limited", "message", "校园访问受限", "duration_ms", 0));
        clearInvocations(engine);
        checkQueue.enqueue(first, "manual");
        checkQueue.enqueue(second, "manual");
        long before = System.currentTimeMillis();
        checkQueue.processOne();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT cooldown_until FROM check_queue_state", Long.class)).isGreaterThanOrEqualTo(before + 1_800_000);
        checkQueue.processOne();
        verify(engine, times(1)).check(anyString(), anyString());
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(second).get("paused")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(first).get("active")).isEqualTo(false);
    }

    @Test
    void simultaneousWorkersAndInteractiveCallsCannotOverlapACampusCheck() throws Exception {
        String first = preparedUser("queue-parallel@qq.com");
        String second = preparedUser("queue-parallel-two@qq.com");
        var started = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        when(engine.check(anyString(), anyString())).thenAnswer(ignored -> {
            started.countDown();
            org.assertj.core.api.Assertions.assertThat(finish.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return Map.of("status", "success", "message", "完成", "duration_ms", 0);
        });
        clearInvocations(engine);
        checkQueue.enqueue(first, "manual");
        checkQueue.enqueue(second, "manual");
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var worker = executor.submit(checkQueue::processOne);
        try {
            org.assertj.core.api.Assertions.assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            checkQueue.processOne();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> campusAccess.call(() -> Map.of("verified", true)))
                .isInstanceOf(com.seekwd.dormcheck.service.CampusAccessGuard.AccessBusyException.class);
            verify(engine, times(1)).check(anyString(), anyString());
        } finally {
            finish.countDown();
            worker.get(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    void restartKeepsQueuedJobsAndFinalizesInterruptedSubmissionsWithoutResending() throws Exception {
        String userId = preparedUser("queue-restart@qq.com");
        checkQueue.enqueue(userId, "manual");
        checkQueue.recover();
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(userId).get("status")).isEqualTo("queued");
        jdbc.update("UPDATE check_jobs SET status='running',attempts=1,started_at=? WHERE user_id=?", java.time.Instant.now().toString(), userId);
        clearInvocations(engine, mail);
        checkQueue.recover();
        checkQueue.recover();
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(userId).get("active")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs", Integer.class)).isEqualTo(1);
        verify(engine, times(0)).check(anyString(), anyString());
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void expiredAndUnverifiedJobsNeverContactCampus() throws Exception {
        String userId = preparedUser("queue-expire@qq.com");
        checkQueue.enqueue(userId, "manual");
        jdbc.update("UPDATE check_jobs SET expires_at='2000-01-01T00:00:00.000Z' WHERE user_id=?", userId);
        clearInvocations(engine);
        checkQueue.processOne();
        org.assertj.core.api.Assertions.assertThat(checkQueue.status(userId).get("active")).isEqualTo(false);
        checkQueue.enqueue(userId, "manual");
        jdbc.update("UPDATE credentials SET verified_at=NULL WHERE user_id=?", userId);
        checkQueue.processOne();
        verify(engine, times(0)).check(anyString(), anyString());
    }

    private String preparedUser(String email) throws Exception {
        MvcResult registered = register(email);
        String userId = json(registered).at("/user/id").asText();
        mvc.perform(put("/api/credentials").cookie(registered.getResponse().getCookie(SessionService.COOKIE_NAME))
                .header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-CSRF-Token", json(registered).get("csrf_token").asText())
                .contentType("application/json").content("{\"account\":\"student-test\",\"password\":\"test-campus-password\"}"))
            .andExpect(status().isOk());
        jdbc.update("UPDATE credentials SET verified_at=? WHERE user_id=?", java.time.Instant.now().toString(), userId);
        return userId;
    }

    @Test
    void loginDevicesCanBeReviewedAndRevoked() throws Exception {
        MvcResult registered = register("device@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        String deviceId = json(registered).get("current_device_id").asText();
        org.assertj.core.api.Assertions.assertThat(deviceId).isNotBlank();
        mvc.perform(get("/api/auth/devices").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(deviceId))
            .andExpect(jsonPath("$[0].screen_size").value("1920x1080"))
            .andExpect(jsonPath("$[0].hardware_threads").value(8));
        mvc.perform(delete("/api/auth/devices/" + deviceId).cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void passwordLoginFailuresBackOffWithoutBrowserChallenges() throws Exception {
        register("throttle@qq.com");
        for (int attempt = 0; attempt < 4; attempt++) {
            mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                    .contentType("application/json").content("{\"email\":\"throttle@qq.com\",\"password\":\"incorrect password 123\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"throttle@qq.com\",\"password\":\"incorrect password 123\"}"))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void malformedAndOversizedAuthenticationInputsReturnGenericValidationErrors() throws Exception {
        mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"x' OR 1=1 --@qq.com\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("request validation failed"));
        mvc.perform(post("/api/auth/register-code").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"" + "a".repeat(250) + "@qq.com\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.detail").value("request validation failed"));
        mvc.perform(put("/api/credentials").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"account\":\"' OR 1=1 --\",\"password\":\"test-password\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void failureBackoffIsScopedToTheIpAndEmailPairAndClearsOnSuccess() {
        String ip = "198.51.100.40";
        for (int i = 0; i < 4; i++) limiter.recordFailure("test-login-failure", ip, "person@qq.com", 86_400);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            limiter.checkFailureBackoff("test-login-failure", ip, "person@qq.com", 3, 60, 300, 86_400))
            .isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
            limiter.checkFailureBackoff("test-login-failure", "198.51.100.41", "person@qq.com", 3, 60, 300, 86_400));
        limiter.clearFailures("test-login-failure", ip, "person@qq.com");
        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() ->
            limiter.checkFailureBackoff("test-login-failure", ip, "person@qq.com", 3, 60, 300, 86_400));
    }

    @Test
    void deviceRateLimitsOnlyApplyToValidDeviceIdentifiers() {
        String device = java.util.UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) limiter.checkDevice("test-device", device, 2, 3600);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.checkDevice("test-device", device, 2, 3600))
            .isInstanceOf(ApiException.class);
        org.assertj.core.api.Assertions.assertThatNoException().isThrownBy(() -> limiter.checkDevice("test-device", "not-a-device", 1, 3600));
    }

    @Test
    void enabledTotpIsRequiredBeforeLoginSessionIsIssued() throws Exception {
        MvcResult registered = register("mfa@qq.com");
        Cookie cookie = registered.getResponse().getCookie(SessionService.COOKIE_NAME);
        String csrf = json(registered).get("csrf_token").asText();
        String setup = mvc.perform(post("/api/auth/totp/setup").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secret = mapper.readTree(setup).get("secret").asText();
        String totp = totp(secret);
        mvc.perform(post("/api/auth/totp/confirm").cookie(cookie).header("Origin", "http://localhost")
                .header("Host", "localhost").header("X-CSRF-Token", csrf).contentType("application/json")
                .content("{\"code\":\"" + totp + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));

        mvc.perform(post("/api/auth/login").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"mfa@qq.com\",\"password\":\"long test password 123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totp_required").value(true));
        jdbc.update("UPDATE email_codes SET code_hash=?,expires_at=?,attempts=0 WHERE email=? AND purpose='login'",
            security.hashToken("654321"), java.time.Instant.now().plusSeconds(600).toString(), "mfa@qq.com");
        String invalidTotp = "000000".equals(totp) ? "000001" : "000000";
        for (int attempt = 0; attempt < 8; attempt++) {
            mvc.perform(post("/api/auth/verify").header("Origin", "http://localhost").header("Host", "localhost")
                    .contentType("application/json").content("{\"email\":\"mfa@qq.com\",\"emailCode\":\"654321\",\"totpCode\":\"" + invalidTotp + "\"}"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/verify").header("Origin", "http://localhost").header("Host", "localhost")
                .contentType("application/json").content("{\"email\":\"mfa@qq.com\",\"emailCode\":\"654321\",\"totpCode\":\"" + invalidTotp + "\"}"))
            .andExpect(status().isTooManyRequests());
        org.assertj.core.api.Assertions.assertThat(mvc.perform(get("/api/auth/session"))
            .andReturn().getResponse().getContentAsString()).contains("false");
    }

    private String totp(String base32) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char value : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(value);
            bits += 5;
            if (bits >= 8) {
                bytes.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new javax.crypto.spec.SecretKeySpec(bytes.toByteArray(), "HmacSHA1"));
        long counter = java.time.Instant.now().getEpochSecond() / 30;
        byte[] digest = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(counter).array());
        int offset = digest[digest.length - 1] & 0x0f;
        int binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16)
            | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
        return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private MvcResult register(String email) throws Exception {
        jdbc.update("INSERT INTO email_codes(email,purpose,code_hash,expires_at,attempts) VALUES(?,?,?,?,0)",
            email, "register", security.hashToken("123456"), java.time.Instant.now().plusSeconds(600).toString());
        return mvc.perform(post("/api/auth/register").header("Origin", "http://localhost").header("Host", "localhost")
                .header("X-Device-ID", java.util.UUID.randomUUID().toString())
                .header("X-Device-Platform", "Test OS").header("X-Device-Screen", "1920x1080")
                .header("X-Device-CPU", "8").header("X-Device-Memory", "16")
                .contentType("application/json").content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"long test password 123\",\"emailCode\":\"123456\"}"))
            .andExpect(status().isOk()).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
