package com.seekwd.dormcheck.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seekwd.dormcheck.exception.ApiException;
import com.seekwd.dormcheck.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class XiangqiService {
    private static final String ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int ROOM_CODE_LENGTH = 6;
    private static final int MAX_GAMES = 12;
    private static final int GAME_STAKE = 2500;
    private static final int AD_REWARD = 2500;
    private static final int DAILY_AD_LIMIT = 6;
    private static final int RELIEF_REWARD = 6000;
    private static final long INITIAL_CLOCK_MS = 90_000;
    private static final long MAX_GAME_MS = 15 * 60_000;
    private static final long INCREMENT_MS = 0;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<MoveRecord>> MOVE_LIST_TYPE = new TypeReference<>() {};
    private static final String GAME_SELECT = """
        SELECT g.id, g.room_code, g.host_user_id, g.guest_user_id, g.status,
               g.move_history, g.version, g.winner_color, g.draw_offer_by,
               g.finish_reason, g.created_at, g.updated_at, g.expires_at,
               g.red_clock_ms, g.black_clock_ms, g.turn_started_at, g.game_type, g.started_at,
               host.display_name AS host_name, COALESCE(guest.display_name, '') AS guest_name
        FROM xiangqi_games g
        JOIN users host ON host.id = g.host_user_id
        LEFT JOIN users guest ON guest.id = g.guest_user_id
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecuritySupport security;
    private final RateLimiter limiter;

    public XiangqiService(JdbcTemplate jdbc, ObjectMapper mapper, SecuritySupport security, RateLimiter limiter) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.security = security;
        this.limiter = limiter;
    }

    public synchronized Map<String, Object> create(Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-create-ip", request.getRemoteAddr(), userId, 10, 3600);
        ensureWallet(userId);
        String id = UUID.randomUUID().toString();
        String now = security.now();
        String expiresAt = Instant.now().plusSeconds(7 * 86_400L).toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            String roomCode = roomCode();
            try {
                jdbc.update("""
                    INSERT INTO xiangqi_games(id, room_code, host_user_id, status, move_history, version,
                        created_at, updated_at, expires_at, red_clock_ms, black_clock_ms, game_type) VALUES(?, ?, ?, 'waiting', '[]', 1, ?, ?, ?, ?, ?, 'friend')
                    """, id, roomCode, userId, now, now, expiresAt, INITIAL_CLOCK_MS, INITIAL_CLOCK_MS);
                return view(loadById(id), userId);
            } catch (DuplicateKeyException ignored) {
                // Retry only the random room code collision.
            }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法创建房间，请稍后重试");
    }

    @Transactional
    public synchronized Map<String, Object> join(String roomCode, Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-join", request.getRemoteAddr(), userId, 60, 3600);
        GameRow game = loadByCode(roomCode.trim().toUpperCase(Locale.ROOT));
        requireNotExpired(game);
        if (game.hostUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "不能加入自己创建的房间");
        }
        if (game.guestUserId() != null && !game.guestUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "房间已满");
        }
        if (game.guestUserId() != null && game.guestUserId().equals(userId)) {
            return view(game, userId);
        }
        if (!"waiting".equals(game.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "房间当前不可加入");
        }
        startFriendGame(game.id());
        int changed = jdbc.update("""
            UPDATE xiangqi_games SET guest_user_id = ?, status = 'active', version = version + 1,
                red_clock_ms = ?, black_clock_ms = ?,
                expires_at = ?, turn_started_at = ?, started_at = ?, updated_at = ? WHERE id = ? AND version = ? AND status = 'waiting'
            """, userId, INITIAL_CLOCK_MS, INITIAL_CLOCK_MS, Instant.now().plusSeconds(7 * 86_400L).toString(), security.now(), security.now(), security.now(),
            game.id(), game.version());
        if (changed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "房间状态已变化，请刷新后重试");
        }
        return view(loadById(game.id()), userId);
    }

    public Map<String, Object> profile(Map<String, Object> session) {
        String userId = userId(session);
        ensureWallet(userId);
        return walletView(userId);
    }

    @Transactional
    public synchronized Map<String, Object> match(Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-match", request.getRemoteAddr(), userId, 90, 3600);
        ensureWallet(userId);
        requireCoins(userId, GAME_STAKE);
        jdbc.update("DELETE FROM xiangqi_match_queue WHERE created_at < ?", Instant.now().minusSeconds(1800).toString());
        List<String> opponents = jdbc.query("""
            SELECT user_id FROM xiangqi_match_queue WHERE user_id <> ?
            ORDER BY ABS(rating - (SELECT rating FROM xiangqi_wallets WHERE user_id = ?)), created_at LIMIT 1
            """, (rs, rowNum) -> rs.getString(1), userId, userId);
        if (opponents.isEmpty()) {
            jdbc.update("INSERT OR REPLACE INTO xiangqi_match_queue(user_id,rating,created_at) VALUES(?,(SELECT rating FROM xiangqi_wallets WHERE user_id=?),?)",
                userId, userId, security.now());
            return Map.of("status", "searching", "profile", walletView(userId));
        }
        String opponent = opponents.get(0);
        ensureWallet(opponent);
        requireCoins(opponent, GAME_STAKE);
        String red = Math.floorMod(security.randomBytes(1)[0], 2) == 0 ? userId : opponent;
        String black = red.equals(userId) ? opponent : userId;
        String id = createActiveGame(red, black);
        jdbc.update("DELETE FROM xiangqi_match_queue WHERE user_id IN (?,?)", userId, opponent);
        return Map.of("status", "matched", "game", view(loadById(id), userId), "profile", walletView(userId));
    }

    public Map<String, Object> matchStatus(Map<String, Object> session) {
        String userId = userId(session);
        List<GameRow> games = jdbc.query(GAME_SELECT + """
            WHERE (g.host_user_id = ? OR g.guest_user_id = ?) AND g.status = 'active' AND g.expires_at > ?
            ORDER BY g.created_at DESC LIMIT 1
            """, (rs, rowNum) -> gameRow(rs), userId, userId, security.now());
        if (!games.isEmpty()) return Map.of("status", "matched", "game", view(games.get(0), userId));
        Integer queued = jdbc.queryForObject("SELECT COUNT(*) FROM xiangqi_match_queue WHERE user_id = ?", Integer.class, userId);
        return Map.of("status", queued != null && queued > 0 ? "searching" : "idle");
    }

    public Map<String, Object> cancelMatch(Map<String, Object> session) {
        jdbc.update("DELETE FROM xiangqi_match_queue WHERE user_id = ?", userId(session));
        return Map.of("status", "idle");
    }

    public List<Map<String, Object>> messages(String gameId, Map<String, Object> session) {
        String userId = userId(session);
        requireParticipant(loadById(gameId), userId);
        List<Map<String, Object>> result = jdbc.query("""
            SELECT m.id,m.user_id,m.body,m.created_at,u.display_name FROM xiangqi_messages m
            JOIN users u ON u.id=m.user_id WHERE m.game_id=? ORDER BY m.created_at DESC LIMIT 80
            """, (rs, rowNum) -> Map.<String, Object>of(
                "id", rs.getString("id"), "mine", userId.equals(rs.getString("user_id")),
                "name", rs.getString("display_name"), "body", rs.getString("body"),
                "created_at", rs.getString("created_at")), gameId);
        Collections.reverse(result);
        return result;
    }

    public Map<String, Object> postMessage(String gameId, String body, Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-chat", request.getRemoteAddr(), userId, 60, 60);
        GameRow game = requireParticipant(loadById(gameId), userId);
        requireNotExpired(game);
        String clean = body == null ? "" : body.strip().replaceAll("[\\r\\n\\t]+", " ");
        if (clean.isBlank() || clean.length() > 80) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "消息需为 1 至 80 个字符");
        String id = UUID.randomUUID().toString();
        String now = security.now();
        jdbc.update("INSERT INTO xiangqi_messages(id,game_id,user_id,body,created_at) VALUES(?,?,?,?,?)", id, gameId, userId, clean, now);
        return Map.of("id", id, "mine", true, "name", "我", "body", clean, "created_at", now);
    }

    public Map<String, Object> startAd(Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-ad-start", request.getRemoteAddr(), userId, 12, 3600);
        ensureWallet(userId);
        String date = LocalDate.now(SHANGHAI).toString();
        if (rewardedAds(userId, date) >= DAILY_AD_LIMIT) throw new ApiException(HttpStatus.CONFLICT, "今天的 6 次广告奖励已经领完");
        String id = UUID.randomUUID().toString();
        String now = security.now();
        String availableAt = Instant.now().plusSeconds(15).toString();
        String expiresAt = Instant.now().plusSeconds(600).toString();
        jdbc.update("INSERT INTO xiangqi_ad_sessions(id,user_id,reward_date,status,available_at,expires_at,created_at) VALUES(?,?,?,'started',?,?,?)",
            id, userId, date, availableAt, expiresAt, now);
        return Map.of("session_id", id, "claim_path", "/xiangqi/rewards/ad/" + id + "/claim",
            "available_at", availableAt, "expires_at", expiresAt, "reward", AD_REWARD, "mode", "demo");
    }

    @Transactional
    public synchronized Map<String, Object> claimAd(String sessionId, Map<String, Object> session) {
        String userId = userId(session);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM xiangqi_ad_sessions WHERE id=? AND user_id=?", sessionId, userId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "广告奖励会话不存在");
        Map<String, Object> row = rows.get(0);
        if (!"started".equals(row.get("status"))) throw new ApiException(HttpStatus.CONFLICT, "这次广告奖励已经处理");
        if (!Instant.parse(row.get("expires_at").toString()).isAfter(Instant.now())) {
            jdbc.update("UPDATE xiangqi_ad_sessions SET status='expired' WHERE id=? AND status='started'", sessionId);
            throw new ApiException(HttpStatus.GONE, "这次广告领取入口已过期，请重新获取");
        }
        if (Instant.parse(row.get("available_at").toString()).isAfter(Instant.now())) throw new ApiException(HttpStatus.CONFLICT, "广告尚未播放完成");
        String date = LocalDate.now(SHANGHAI).toString();
        if (!date.equals(row.get("reward_date")) || rewardedAds(userId, date) >= DAILY_AD_LIMIT) throw new ApiException(HttpStatus.CONFLICT, "今天的广告奖励不可再领取");
        jdbc.update("UPDATE xiangqi_ad_sessions SET status='rewarded',rewarded_at=? WHERE id=? AND status='started'", security.now(), sessionId);
        addCoins(userId, AD_REWARD, "rewarded_ad", sessionId);
        return walletView(userId);
    }

    @Transactional
    public synchronized Map<String, Object> claimRelief(Map<String, Object> session) {
        String userId = userId(session);
        ensureWallet(userId);
        Map<String, Object> wallet = jdbc.queryForMap("SELECT coins,relief_claims FROM xiangqi_wallets WHERE user_id=?", userId);
        if (((Number) wallet.get("coins")).intValue() >= GAME_STAKE) throw new ApiException(HttpStatus.CONFLICT, "金币足够开局时不能领取救济金");
        if (((Number) wallet.get("relief_claims")).intValue() >= 2) throw new ApiException(HttpStatus.CONFLICT, "两次救济金已经全部领取");
        jdbc.update("UPDATE xiangqi_wallets SET coins=coins+?,relief_claims=relief_claims+1,updated_at=? WHERE user_id=?", RELIEF_REWARD, security.now(), userId);
        ledger(userId, RELIEF_REWARD, "relief", null);
        return walletView(userId);
    }

    public List<Map<String, Object>> list(Map<String, Object> session) {
        String userId = userId(session);
        return jdbc.query(GAME_SELECT + """
            WHERE (g.host_user_id = ? OR g.guest_user_id = ?) AND g.expires_at > ?
            ORDER BY g.updated_at DESC LIMIT ?
            """, (rs, rowNum) -> gameRow(rs), userId, userId, security.now(), MAX_GAMES)
            .stream().map(game -> view(game, userId)).toList();
    }

    @Transactional
    public synchronized Map<String, Object> get(String id, Map<String, Object> session) {
        String userId = userId(session);
        GameRow game = requireParticipant(loadById(id), userId);
        requireNotExpired(game);
        if ("active".equals(game.status()) && isGameLimitReached(game, Instant.now())) {
            updateGame(game, "finished", readMoves(game.moveHistory()), null, null, "game_limit");
            jdbc.update("UPDATE xiangqi_games SET turn_started_at=NULL WHERE id=?", game.id());
            settleGame(loadById(game.id()));
        }
        game = loadById(id);
        game = finishOnTimeout(game);
        return view(game, userId);
    }

    @Transactional
    public synchronized Map<String, Object> move(String id, String from, String to, int expectedVersion,
        Map<String, Object> session, HttpServletRequest request) {
        String userId = userId(session);
        limiter.check("xiangqi-move", request.getRemoteAddr(), userId, 600, 3600);
        GameRow game = requireParticipant(loadById(id), userId);
        requireNotExpired(game);
        if ("active".equals(game.status()) && isGameLimitReached(game, Instant.now())) {
            finishGameLimit(game);
            return view(loadById(id), userId);
        }
        requireVersion(game, expectedVersion);
        if (!"active".equals(game.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "棋局尚未开始或已经结束");
        }

        List<MoveRecord> moves = readMoves(game.moveHistory());
        Board board = Board.initial();
        for (MoveRecord previous : moves) {
            applyStoredMove(board, previous);
        }
        String color = colorFor(game, userId);
        String turn = turn(moves.size());
        if (!color.equals(turn)) {
            throw new ApiException(HttpStatus.CONFLICT, "还没有轮到你走棋");
        }
        long remaining = remainingClock(game, color, Instant.now());
        if (remaining <= 0) {
            finishTimeout(game, color);
            return view(loadById(id), userId);
        }

        int start = square(from);
        int end = square(to);
        Stone moving = board.at(start);
        if (moving == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "起点没有棋子");
        if (!moving.color().equals(color)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "不能移动对方的棋子");
        }
        if (!legalMoves(board, color).getOrDefault(position(start), List.of()).contains(position(end))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "这步棋不合法");
        }

        Stone captured = board.at(end);
        board.move(start, end);
        moves.add(new MoveRecord(position(start), position(end), moving.code(), captured == null ? "" : captured.code()));

        String status = "active";
        String winner = null;
        String reason = null;
        String next = color.equals("red") ? "black" : "red";
        boolean nextInCheck = kingInCheck(board, next);
        if (legalMoves(board, next).isEmpty()) {
            status = "finished";
            winner = color;
            reason = nextInCheck ? "checkmate" : "stalemate";
        } else if (drawBySixtyMoves(moves)) {
            status = "finished";
            reason = "sixty_move";
        }
        updateGame(game, status, moves, winner, null, reason);
        updateClockAfterMove(game, color, remaining, "finished".equals(status));
        if ("finished".equals(status)) settleGame(loadById(id));
        return view(loadById(id), userId);
    }

    @Transactional
    public synchronized Map<String, Object> resign(String id, int expectedVersion, Map<String, Object> session) {
        String userId = userId(session);
        GameRow game = requireParticipant(loadById(id), userId);
        requireNotExpired(game);
        game = finishOnTimeout(game);
        if ("finished".equals(game.status())) return view(game, userId);
        requireVersion(game, expectedVersion);
        if (!"active".equals(game.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "棋局当前不能认输");
        }
        String winner = "red".equals(colorFor(game, userId)) ? "black" : "red";
        updateGame(game, "finished", readMoves(game.moveHistory()), winner, null, "resignation");
        settleGame(loadById(id));
        return view(loadById(id), userId);
    }

    @Transactional
    public synchronized Map<String, Object> draw(String id, String action, int expectedVersion,
        Map<String, Object> session) {
        String userId = userId(session);
        GameRow game = requireParticipant(loadById(id), userId);
        requireNotExpired(game);
        game = finishOnTimeout(game);
        if ("finished".equals(game.status())) return view(game, userId);
        requireVersion(game, expectedVersion);
        if (!"active".equals(game.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "棋局当前不能议和");
        }
        switch (action) {
            case "offer" -> {
                if (userId.equals(game.drawOfferBy())) {
                    throw new ApiException(HttpStatus.CONFLICT, "已经发送过和棋请求");
                }
                updateGame(game, "active", readMoves(game.moveHistory()), null, userId, game.finishReason());
            }
            case "accept" -> {
                if (game.drawOfferBy() == null || userId.equals(game.drawOfferBy())) {
                    throw new ApiException(HttpStatus.CONFLICT, "没有可接受的和棋请求");
                }
                updateGame(game, "finished", readMoves(game.moveHistory()), null, null, "agreement");
                settleGame(loadById(id));
            }
            case "decline" -> {
                if (game.drawOfferBy() == null || userId.equals(game.drawOfferBy())) {
                    throw new ApiException(HttpStatus.CONFLICT, "没有可拒绝的和棋请求");
                }
                updateGame(game, "active", readMoves(game.moveHistory()), null, null, null);
            }
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "和棋操作无效");
        }
        return view(loadById(id), userId);
    }

    private void updateGame(GameRow game, String status, List<MoveRecord> moves, String winner,
        String drawOfferBy, String reason) {
        int changed;
        try {
            changed = jdbc.update("""
                UPDATE xiangqi_games SET status = ?, move_history = ?, winner_color = ?, draw_offer_by = ?,
                    finish_reason = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?
                """, status, mapper.writeValueAsString(moves), winner, drawOfferBy, reason,
                security.now(), game.id(), game.version());
        } catch (Exception error) {
            throw new IllegalStateException("could not save xiangqi game", error);
        }
        if (changed != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "棋局已更新，请刷新后重试");
        }
    }

    private Map<String, Object> view(GameRow game, String userId) {
        List<MoveRecord> moves = readMoves(game.moveHistory());
        Board board = Board.initial();
        for (MoveRecord move : moves) applyStoredMove(board, move);
        String myColor = colorForNullable(game, userId);
        String turn = turn(moves.size());
        boolean active = "active".equals(game.status());
        boolean myTurn = active && myColor != null && myColor.equals(turn);
        String opponent = game.hostUserId().equals(userId) ? game.guestName() : game.hostName();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", game.id());
        response.put("room_code", game.roomCode());
        response.put("status", game.status());
        response.put("game_type", game.gameType());
        response.put("version", game.version());
        response.put("host_name", game.hostName());
        response.put("guest_name", game.guestName());
        response.put("opponent_name", opponent == null || opponent.isBlank() ? null : opponent);
        response.put("my_color", myColor);
        response.put("turn", turn);
        response.put("my_turn", myTurn);
        response.put("in_check", !"finished".equals(game.status()) && kingInCheck(board, turn));
        response.put("winner_color", game.winnerColor());
        response.put("finish_reason", game.finishReason());
        response.put("draw_offered_by_me", userId.equals(game.drawOfferBy()));
        response.put("draw_offered_by_opponent", game.drawOfferBy() != null && !userId.equals(game.drawOfferBy()));
        response.put("board", boardView(game, board));
        response.put("fen", board.fen(turn));
        response.put("legal_moves", myTurn ? legalMoves(board, myColor) : Map.of());
        response.put("moves", moveView(moves));
        Instant sampledAt = Instant.now();
        response.put("clock", Map.of("red_ms", remainingClock(game, "red", sampledAt),
            "black_ms", remainingClock(game, "black", sampledAt), "increment_ms", INCREMENT_MS,
            "max_game_ms", MAX_GAME_MS, "started_at", game.startedAt() == null ? "" : game.startedAt(),
            "running_color", active ? turn : "", "turn_started_at", game.turnStartedAt() == null ? "" : game.turnStartedAt(),
            "sampled_at", sampledAt.toString()));
        response.put("created_at", game.createdAt());
        response.put("updated_at", game.updatedAt());
        return response;
    }

    private long remainingClock(GameRow game, String color, Instant now) {
        long remaining = "red".equals(color) ? game.redClockMs() : game.blackClockMs();
        if (!"active".equals(game.status()) || game.turnStartedAt() == null || !color.equals(turn(readMoves(game.moveHistory()).size()))) return remaining;
        long elapsed = Math.max(0, Duration.between(Instant.parse(game.turnStartedAt()), now).toMillis());
        remaining = Math.max(0, remaining - elapsed);
        if (game.startedAt() != null) remaining = Math.min(remaining, Math.max(0, MAX_GAME_MS - Duration.between(Instant.parse(game.startedAt()), now).toMillis()));
        return remaining;
    }

    private boolean isGameLimitReached(GameRow game, Instant now) {
        return game.startedAt() != null && Duration.between(Instant.parse(game.startedAt()), now).toMillis() >= MAX_GAME_MS;
    }

    private void finishGameLimit(GameRow game) {
        updateGame(game, "finished", readMoves(game.moveHistory()), null, null, "game_limit");
        jdbc.update("UPDATE xiangqi_games SET turn_started_at=NULL WHERE id=?", game.id());
        settleGame(loadById(game.id()));
    }

    private void finishTimeout(GameRow game, String expiredColor) {
        String winner = expiredColor.equals("red") ? "black" : "red";
        updateGame(game, "finished", readMoves(game.moveHistory()), winner, null, "timeout");
        jdbc.update("UPDATE xiangqi_games SET turn_started_at=NULL WHERE id=?", game.id());
        settleGame(loadById(game.id()));
    }

    private GameRow finishOnTimeout(GameRow game) {
        if (!"active".equals(game.status())) return game;
        String running = turn(readMoves(game.moveHistory()).size());
        if (isGameLimitReached(game, Instant.now())) {
            finishGameLimit(game);
            return loadById(game.id());
        }
        if (remainingClock(game, running, Instant.now()) > 0) return game;
        finishTimeout(game, running);
        return loadById(game.id());
    }

    private void updateClockAfterMove(GameRow game, String movedColor, long remaining, boolean finished) {
        long red = game.redClockMs();
        long black = game.blackClockMs();
        if ("red".equals(movedColor)) red = remaining;
        else black = remaining;
        jdbc.update("UPDATE xiangqi_games SET red_clock_ms=?,black_clock_ms=?,turn_started_at=? WHERE id=?",
            red, black, finished ? null : security.now(), game.id());
    }

    private Map<String, Object> boardView(GameRow game, Board board) {
        Map<String, String> squares = new TreeMap<>();
        for (int index = 0; index < 90; index++) {
            Stone stone = board.pieces[index];
            if (stone != null) squares.put(position(index), stone.code());
        }
        return Map.of("squares", squares, "red_name", game.hostName(), "black_name", game.guestName());
    }

    private List<Map<String, Object>> moveView(List<MoveRecord> moves) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < moves.size(); index++) {
            MoveRecord move = moves.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", index / 2 + 1);
            item.put("color", index % 2 == 0 ? "red" : "black");
            item.put("from", move.from());
            item.put("to", move.to());
            item.put("piece", move.piece());
            item.put("captured", move.captured());
            item.put("notation", move.from() + " - " + move.to());
            result.add(item);
        }
        return result;
    }

    private void applyStoredMove(Board board, MoveRecord move) {
        int start = square(move.from());
        int end = square(move.to());
        Stone piece = board.at(start);
        if (piece == null || !piece.code().equals(move.piece()) || board.at(end) != null
            && !board.at(end).code().equals(move.captured())) {
            throw new IllegalStateException("invalid stored xiangqi move");
        }
        board.move(start, end);
    }

    private Map<String, List<String>> legalMoves(Board board, String color) {
        Map<String, List<String>> result = new TreeMap<>();
        for (int from = 0; from < 90; from++) {
            Stone piece = board.pieces[from];
            if (piece == null || !piece.color().equals(color)) continue;
            List<String> targets = new ArrayList<>();
            for (int to : pseudoTargets(board, from, piece)) {
                Board next = board.copy();
                next.move(from, to);
                if (!kingInCheck(next, color)) targets.add(position(to));
            }
            if (!targets.isEmpty()) result.put(position(from), targets.stream().sorted().toList());
        }
        return result;
    }

    private List<Integer> pseudoTargets(Board board, int from, Stone piece) {
        List<Integer> targets = new ArrayList<>();
        int file = from % 9;
        int rank = from / 9;
        switch (piece.type()) {
            case 'K' -> {
                int[][] steps = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
                for (int[] step : steps) addIfLegalStep(board, targets, piece, file + step[0], rank + step[1], true);
            }
            case 'A' -> {
                int[][] steps = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                for (int[] step : steps) addIfLegalStep(board, targets, piece, file + step[0], rank + step[1], true);
            }
            case 'B' -> {
                int[][] steps = {{2, 2}, {2, -2}, {-2, 2}, {-2, -2}};
                for (int[] step : steps) {
                    int targetFile = file + step[0];
                    int targetRank = rank + step[1];
                    int eye = (rank + step[1] / 2) * 9 + file + step[0] / 2;
                    if (!inside(targetFile, targetRank) || board.pieces[eye] != null || !ownSide(piece.color(), targetRank)) continue;
                    addTarget(board, targets, piece, targetRank * 9 + targetFile);
                }
            }
            case 'N' -> {
                int[][] steps = {{1, 2}, {-1, 2}, {1, -2}, {-1, -2}, {2, 1}, {2, -1}, {-2, 1}, {-2, -1}};
                for (int[] step : steps) {
                    int targetFile = file + step[0];
                    int targetRank = rank + step[1];
                    if (!inside(targetFile, targetRank)) continue;
                    int legFile = file + (Math.abs(step[0]) == 2 ? Integer.signum(step[0]) : 0);
                    int legRank = rank + (Math.abs(step[1]) == 2 ? Integer.signum(step[1]) : 0);
                    if (board.pieces[legRank * 9 + legFile] != null) continue;
                    addTarget(board, targets, piece, targetRank * 9 + targetFile);
                }
            }
            case 'R', 'C' -> {
                int[][] directions = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};
                for (int[] direction : directions) {
                    int targetFile = file + direction[0];
                    int targetRank = rank + direction[1];
                    int screens = 0;
                    while (inside(targetFile, targetRank)) {
                        int target = targetRank * 9 + targetFile;
                        Stone occupant = board.pieces[target];
                        if (piece.type() == 'R') {
                            if (occupant == null) targets.add(target);
                            else {
                                if (!occupant.color().equals(piece.color())) targets.add(target);
                                break;
                            }
                        } else if (occupant == null) {
                            if (screens == 0) targets.add(target);
                        } else {
                            screens++;
                            if (screens == 2) {
                                if (!occupant.color().equals(piece.color())) targets.add(target);
                                break;
                            }
                        }
                        targetFile += direction[0];
                        targetRank += direction[1];
                    }
                }
            }
            case 'P' -> {
                int forward = piece.color().equals("red") ? 1 : -1;
                addIfLegalStep(board, targets, piece, file, rank + forward, false);
                if (!ownSide(piece.color(), rank)) {
                    addIfLegalStep(board, targets, piece, file - 1, rank, false);
                    addIfLegalStep(board, targets, piece, file + 1, rank, false);
                }
            }
            default -> { }
        }
        return targets;
    }

    private void addIfLegalStep(Board board, List<Integer> targets, Stone piece, int file, int rank, boolean palace) {
        if (!inside(file, rank) || palace && !inPalace(piece.color(), file, rank)) return;
        addTarget(board, targets, piece, rank * 9 + file);
    }

    private void addTarget(Board board, List<Integer> targets, Stone piece, int target) {
        Stone occupant = board.pieces[target];
        if (occupant == null || !occupant.color().equals(piece.color())) targets.add(target);
    }

    private boolean kingInCheck(Board board, String color) {
        int king = board.king(color);
        if (king < 0) return true;
        int kingFile = king % 9;
        int kingRank = king / 9;
        int enemyKing = board.king(color.equals("red") ? "black" : "red");
        if (enemyKing >= 0 && enemyKing % 9 == kingFile && piecesBetween(board, king, enemyKing) == 0) return true;
        for (int from = 0; from < 90; from++) {
            Stone piece = board.pieces[from];
            if (piece == null || piece.color().equals(color)) continue;
            if (pseudoTargets(board, from, piece).contains(king)) return true;
        }
        return false;
    }

    private int piecesBetween(Board board, int first, int second) {
        int step = second > first ? 9 : -9;
        int count = 0;
        for (int index = first + step; index != second; index += step) {
            if (board.pieces[index] != null) count++;
        }
        return count;
    }

    private boolean drawBySixtyMoves(List<MoveRecord> moves) {
        int quietPlies = 0;
        for (int index = moves.size() - 1; index >= 0 && quietPlies < 120; index--) {
            MoveRecord move = moves.get(index);
            if (!move.captured().isBlank() || "P".equals(move.piece().substring(1))) break;
            quietPlies++;
        }
        return quietPlies >= 120;
    }

    private boolean ownSide(String color, int rank) {
        return color.equals("red") ? rank <= 4 : rank >= 5;
    }

    private boolean inPalace(String color, int file, int rank) {
        if (file < 3 || file > 5) return false;
        return color.equals("red") ? rank >= 0 && rank <= 2 : rank >= 7 && rank <= 9;
    }

    private boolean inside(int file, int rank) {
        return file >= 0 && file < 9 && rank >= 0 && rank < 10;
    }

    private int square(String value) {
        if (value == null || !value.matches("^[a-i][0-9]$")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "棋盘坐标无效");
        }
        int file = value.charAt(0) - 'a';
        int rank = value.charAt(1) - '0';
        return rank * 9 + file;
    }

    private String position(int square) {
        return String.valueOf((char) ('a' + square % 9)) + (square / 9);
    }

    private String turn(int moves) {
        return moves % 2 == 0 ? "red" : "black";
    }

    private List<MoveRecord> readMoves(String history) {
        try {
            return mapper.readValue(history == null || history.isBlank() ? "[]" : history, MOVE_LIST_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("invalid xiangqi history", error);
        }
    }

    private GameRow loadById(String id) {
        List<GameRow> rows = jdbc.query(GAME_SELECT + " WHERE g.id = ?", (rs, rowNum) -> gameRow(rs), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "棋局不存在");
        return rows.get(0);
    }

    private GameRow loadByCode(String roomCode) {
        List<GameRow> rows = jdbc.query(GAME_SELECT + " WHERE g.room_code = ?", (rs, rowNum) -> gameRow(rs), roomCode);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "房间不存在，请检查房间号");
        return rows.get(0);
    }

    private GameRow gameRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GameRow(
            rs.getString("id"), rs.getString("room_code"), rs.getString("host_user_id"),
            rs.getString("guest_user_id"), rs.getString("status"), rs.getString("move_history"),
            rs.getInt("version"), rs.getString("winner_color"), rs.getString("draw_offer_by"),
            rs.getString("finish_reason"), rs.getString("created_at"), rs.getString("updated_at"),
            rs.getString("expires_at"), rs.getString("host_name"), rs.getString("guest_name"),
            rs.getLong("red_clock_ms"), rs.getLong("black_clock_ms"), rs.getString("turn_started_at"),
            rs.getString("game_type"), rs.getString("started_at"));
    }

    private GameRow requireParticipant(GameRow game, String userId) {
        if (!game.hostUserId().equals(userId) && !userId.equals(game.guestUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "你不是这局棋的参与者");
        }
        return game;
    }

    private void requireNotExpired(GameRow game) {
        if (game.expiresAt() == null || !Instant.parse(game.expiresAt()).isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "房间已过期");
        }
    }

    private void requireVersion(GameRow game, int expectedVersion) {
        if (game.version() != expectedVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "棋局已更新，请刷新后重试");
        }
    }

    private String userId(Map<String, Object> session) {
        Object value = session.get("user_id");
        if (value == null) throw security.unauthorized();
        return value.toString();
    }

    private String colorFor(GameRow game, String userId) {
        String color = colorForNullable(game, userId);
        if (color == null) throw new ApiException(HttpStatus.FORBIDDEN, "你不是这局棋的参与者");
        return color;
    }

    private String colorForNullable(GameRow game, String userId) {
        if (game.hostUserId().equals(userId)) return "red";
        if (userId.equals(game.guestUserId())) return "black";
        return null;
    }

    private String roomCode() {
        StringBuilder result = new StringBuilder(ROOM_CODE_LENGTH);
        for (int index = 0; index < ROOM_CODE_LENGTH; index++) {
            int selected = Math.floorMod((int) security.randomBytes(1)[0], ROOM_ALPHABET.length());
            result.append(ROOM_ALPHABET.charAt(selected));
        }
        return result.toString();
    }

    private void ensureWallet(String userId) {
        String now = security.now();
        int created = jdbc.update("""
            INSERT OR IGNORE INTO xiangqi_wallets(user_id,coins,rating,created_at,updated_at)
            VALUES(?,10000,1000,?,?)
            """, userId, now, now);
        if (created == 1) ledger(userId, 10000, "welcome", null);
    }

    private Map<String, Object> walletView(String userId) {
        ensureWallet(userId);
        Map<String, Object> wallet = jdbc.queryForMap("SELECT coins,rating,wins,losses,draws,relief_claims,rated_games FROM xiangqi_wallets WHERE user_id=?", userId);
        String date = LocalDate.now(SHANGHAI).toString();
        Map<String, Object> result = new LinkedHashMap<>(wallet);
        result.put("stake", GAME_STAKE);
        result.put("ad_reward", AD_REWARD);
        result.put("ads_used_today", rewardedAds(userId, date));
        result.put("ads_limit", DAILY_AD_LIMIT);
        result.put("relief_reward", RELIEF_REWARD);
        result.put("relief_remaining", 2 - ((Number) wallet.get("relief_claims")).intValue());
        result.put("can_claim_relief", ((Number) wallet.get("coins")).intValue() < GAME_STAKE
            && ((Number) wallet.get("relief_claims")).intValue() < 2);
        return result;
    }

    private int rewardedAds(String userId, String date) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM xiangqi_ad_sessions WHERE user_id=? AND reward_date=? AND status='rewarded'", Integer.class, userId, date);
        return count == null ? 0 : count;
    }

    private void requireCoins(String userId, int amount) {
        ensureWallet(userId);
        Integer coins = jdbc.queryForObject("SELECT coins FROM xiangqi_wallets WHERE user_id=?", Integer.class, userId);
        if (coins == null || coins < amount) throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "金币不足，无法开始对局");
    }

    private void startPaidGame(String gameId, String redUserId, String blackUserId) {
        ensureWallet(redUserId);
        ensureWallet(blackUserId);
        requireCoins(redUserId, GAME_STAKE);
        requireCoins(blackUserId, GAME_STAKE);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM xiangqi_game_entries WHERE game_id=?", Integer.class, gameId);
        if (exists != null && exists > 0) return;
        deductCoins(redUserId, GAME_STAKE, "game_entry", gameId);
        deductCoins(blackUserId, GAME_STAKE, "game_entry", gameId);
        jdbc.update("INSERT INTO xiangqi_game_entries(game_id,stake,settled,created_at) VALUES(?,?,0,?)", gameId, GAME_STAKE, security.now());
    }

    private String createActiveGame(String redUserId, String blackUserId) {
        String id = UUID.randomUUID().toString();
        String now = security.now();
        String expiresAt = Instant.now().plusSeconds(7 * 86_400L).toString();
        for (int attempt = 0; attempt < 8; attempt++) {
            try {
                jdbc.update("""
                    INSERT INTO xiangqi_games(id,room_code,host_user_id,guest_user_id,status,move_history,version,created_at,updated_at,expires_at,red_clock_ms,black_clock_ms,turn_started_at,game_type,started_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, roomCode(), redUserId, blackUserId, "active", "[]", 2, now, now, expiresAt,
                    INITIAL_CLOCK_MS, INITIAL_CLOCK_MS, now, "ranked", now);
                startPaidGame(id, redUserId, blackUserId);
                return id;
            } catch (DuplicateKeyException ignored) { }
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "暂时无法建立匹配对局");
    }

    private void settleGame(GameRow game) {
        if ("friend".equals(game.gameType())) return;
        List<Map<String, Object>> entries = jdbc.queryForList("SELECT stake,settled FROM xiangqi_game_entries WHERE game_id=?", game.id());
        if (entries.isEmpty() || ((Number) entries.get(0).get("settled")).intValue() == 1) return;
        int stake = ((Number) entries.get(0).get("stake")).intValue();
        if (game.winnerColor() == null) {
            addCoins(game.hostUserId(), stake, "game_draw_refund", game.id());
            addCoins(game.guestUserId(), stake, "game_draw_refund", game.id());
            jdbc.update("UPDATE xiangqi_wallets SET draws=draws+1,updated_at=? WHERE user_id IN (?,?)", security.now(), game.hostUserId(), game.guestUserId());
        } else {
            String winner = "red".equals(game.winnerColor()) ? game.hostUserId() : game.guestUserId();
            String loser = winner.equals(game.hostUserId()) ? game.guestUserId() : game.hostUserId();
            int winnerDelta = eloDelta(game, winner, 1.0);
            int loserDelta = eloDelta(game, loser, 0.0);
            addCoins(winner, stake * 2, "game_prize", game.id());
            jdbc.update("UPDATE xiangqi_wallets SET rating=rating+?,rated_games=rated_games+1,wins=wins+1,updated_at=? WHERE user_id=?", winnerDelta, security.now(), winner);
            jdbc.update("UPDATE xiangqi_wallets SET rating=MAX(0,rating+?),rated_games=rated_games+1,losses=losses+1,updated_at=? WHERE user_id=?", loserDelta, security.now(), loser);
        }
        if (game.winnerColor() == null) jdbc.update("UPDATE xiangqi_wallets SET rated_games=rated_games+1 WHERE user_id IN (?,?)", game.hostUserId(), game.guestUserId());
        jdbc.update("UPDATE xiangqi_game_entries SET settled=1,settled_at=? WHERE game_id=? AND settled=0", security.now(), game.id());
    }

    private int eloDelta(GameRow game, String userId, double score) {
        String opponent = game.hostUserId().equals(userId) ? game.guestUserId() : game.hostUserId();
        int rating = jdbc.queryForObject("SELECT rating FROM xiangqi_wallets WHERE user_id=?", Integer.class, userId);
        int opponentRating = jdbc.queryForObject("SELECT rating FROM xiangqi_wallets WHERE user_id=?", Integer.class, opponent);
        int ratedGames = jdbc.queryForObject("SELECT rated_games FROM xiangqi_wallets WHERE user_id=?", Integer.class, userId);
        int k = ratedGames < 20 ? 40 : rating >= 1800 ? 16 : 24;
        double expected = 1.0 / (1.0 + Math.pow(10.0, (opponentRating - rating) / 400.0));
        return (int) Math.round(k * (score - expected));
    }

    private void startFriendGame(String gameId) {
        jdbc.update("UPDATE xiangqi_games SET game_type='friend' WHERE id=?", gameId);
    }

    private void deductCoins(String userId, int amount, String reason, String reference) {
        int changed = jdbc.update("UPDATE xiangqi_wallets SET coins=coins-?,updated_at=? WHERE user_id=? AND coins>=?", amount, security.now(), userId, amount);
        if (changed != 1) throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "金币不足，无法开始对局");
        ledger(userId, -amount, reason, reference);
    }

    private void addCoins(String userId, int amount, String reason, String reference) {
        ensureWallet(userId);
        jdbc.update("UPDATE xiangqi_wallets SET coins=coins+?,updated_at=? WHERE user_id=?", amount, security.now(), userId);
        ledger(userId, amount, reason, reference);
    }

    private void ledger(String userId, int delta, String reason, String reference) {
        jdbc.update("INSERT INTO xiangqi_ledger(id,user_id,delta,reason,reference_id,created_at) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(), userId, delta, reason, reference, security.now());
    }

    private static final class Board {
        private final Stone[] pieces;

        private Board(Stone[] pieces) {
            this.pieces = pieces;
        }

        private static Board initial() {
            Stone[] pieces = new Stone[90];
            String back = "RNBAKABNR";
            for (int file = 0; file < 9; file++) {
                pieces[file] = new Stone("red", back.charAt(file));
                pieces[81 + file] = new Stone("black", back.charAt(file));
            }
            pieces[2 * 9 + 1] = new Stone("red", 'C');
            pieces[2 * 9 + 7] = new Stone("red", 'C');
            pieces[7 * 9 + 1] = new Stone("black", 'C');
            pieces[7 * 9 + 7] = new Stone("black", 'C');
            for (int file = 0; file < 9; file += 2) {
                pieces[3 * 9 + file] = new Stone("red", 'P');
                pieces[6 * 9 + file] = new Stone("black", 'P');
            }
            return new Board(pieces);
        }

        private Board copy() {
            return new Board(pieces.clone());
        }

        private Stone at(int square) {
            return pieces[square];
        }

        private void move(int from, int to) {
            pieces[to] = pieces[from];
            pieces[from] = null;
        }

        private int king(String color) {
            for (int index = 0; index < 90; index++) {
                Stone stone = pieces[index];
                if (stone != null && stone.color().equals(color) && stone.type() == 'K') return index;
            }
            return -1;
        }

        private String fen(String turn) {
            StringBuilder fen = new StringBuilder();
            for (int rank = 9; rank >= 0; rank--) {
                int empty = 0;
                for (int file = 0; file < 9; file++) {
                    Stone stone = pieces[rank * 9 + file];
                    if (stone == null) {
                        empty++;
                        continue;
                    }
                    if (empty > 0) { fen.append(empty); empty = 0; }
                    fen.append(stone.color().equals("red") ? stone.type() : Character.toLowerCase(stone.type()));
                }
                if (empty > 0) fen.append(empty);
                if (rank > 0) fen.append('/');
            }
            return fen.append(' ').append(turn.equals("red") ? 'r' : 'b').append(" - - 0 1").toString();
        }
    }

    private record Stone(String color, char type) {
        private String code() {
            return (color.equals("red") ? "r" : "b") + type;
        }
    }

    private record GameRow(String id, String roomCode, String hostUserId, String guestUserId,
        String status, String moveHistory, int version, String winnerColor, String drawOfferBy,
        String finishReason, String createdAt, String updatedAt, String expiresAt,
        String hostName, String guestName, long redClockMs, long blackClockMs, String turnStartedAt,
        String gameType, String startedAt) {}

    private record MoveRecord(String from, String to, String piece, String captured) {}
}
