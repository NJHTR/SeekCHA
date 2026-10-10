package com.seekwd.dormcheck.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Adds notification fields to databases created before system events were supported. */
@Component
public final class NotificationSchemaMigration {
    private final JdbcTemplate jdbc;

    public NotificationSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        if (runIdIsRequired()) rebuildForNullableRunId();
        addColumnIfMissing("type", "TEXT NOT NULL DEFAULT 'run'");
        addColumnIfMissing("title", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing("message", "TEXT NOT NULL DEFAULT ''");
    }

    private boolean runIdIsRequired() {
        return jdbc.query("PRAGMA table_info(notifications)", rs -> {
            while (rs.next()) {
                if ("run_id".equals(rs.getString("name"))) return rs.getInt("notnull") == 1;
            }
            return false;
        });
    }

    private void rebuildForNullableRunId() {
        jdbc.execute("PRAGMA foreign_keys=OFF");
        try {
            jdbc.execute("ALTER TABLE notifications RENAME TO notifications_legacy");
            jdbc.execute("CREATE TABLE notifications (" +
                "id TEXT PRIMARY KEY," +
                "user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE," +
                "run_id TEXT UNIQUE REFERENCES runs(id) ON DELETE CASCADE," +
                "type TEXT NOT NULL DEFAULT 'run'," +
                "title TEXT NOT NULL DEFAULT ''," +
                "message TEXT NOT NULL DEFAULT ''," +
                "created_at TEXT NOT NULL," +
                "read_at TEXT)");
            jdbc.execute("INSERT INTO notifications(id,user_id,run_id,type,title,message,created_at,read_at) " +
                "SELECT id,user_id,run_id,'run','', '',created_at,read_at FROM notifications_legacy");
            jdbc.execute("DROP TABLE notifications_legacy");
            jdbc.execute("CREATE INDEX IF NOT EXISTS notifications_user_unread ON notifications(user_id, read_at, created_at DESC)");
        } finally {
            jdbc.execute("PRAGMA foreign_keys=ON");
        }
    }

    private void addColumnIfMissing(String column, String definition) {
        boolean present = jdbc.query("PRAGMA table_info(notifications)", rs -> {
            while (rs.next()) if (column.equals(rs.getString("name"))) return true;
            return false;
        });
        if (!present) jdbc.execute("ALTER TABLE notifications ADD COLUMN " + column + " " + definition);
    }
}
