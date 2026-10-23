package com.seekwd.dormcheck.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class XiangqiSchemaMigration {
    private final JdbcTemplate jdbc;

    public XiangqiSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        addColumnIfMissing("xiangqi_games", "red_clock_ms", "INTEGER NOT NULL DEFAULT 90000");
        addColumnIfMissing("xiangqi_games", "black_clock_ms", "INTEGER NOT NULL DEFAULT 90000");
        addColumnIfMissing("xiangqi_games", "turn_started_at", "TEXT");
        addColumnIfMissing("xiangqi_games", "game_type", "TEXT NOT NULL DEFAULT 'friend'");
        addColumnIfMissing("xiangqi_games", "started_at", "TEXT");
        addColumnIfMissing("xiangqi_wallets", "rated_games", "INTEGER NOT NULL DEFAULT 0");
        jdbc.update("UPDATE xiangqi_games SET game_type='ranked' WHERE id IN (SELECT game_id FROM xiangqi_game_entries)");
        jdbc.update("UPDATE xiangqi_games SET started_at=COALESCE(turn_started_at,updated_at), red_clock_ms=MIN(red_clock_ms,90000), black_clock_ms=MIN(black_clock_ms,90000) WHERE status='active' AND started_at IS NULL");
        jdbc.update("UPDATE xiangqi_wallets SET rated_games=wins+losses+draws WHERE rated_games=0 AND wins+losses+draws>0");
        addColumnIfMissing("xiangqi_ad_sessions", "expires_at", "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        boolean present = jdbc.query("PRAGMA table_info(" + table + ")", rs -> {
            while (rs.next()) if (column.equals(rs.getString("name"))) return true;
            return false;
        });
        if (!present) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }
}
