package com.seekwd.dormcheck.security;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DatabasePermissions {
    private final Path database;

    public DatabasePermissions(@Value("${spring.datasource.url}") String url) {
        String path = url.substring("jdbc:sqlite:".length());
        database = Path.of(path).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restrict() {
        try {
            Files.createDirectories(database.getParent());
            Files.setPosixFilePermissions(database.getParent(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(database, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
        } catch (Exception error) {
            throw new IllegalStateException("could not restrict database file permissions", error);
        }
    }
}
