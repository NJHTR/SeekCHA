package com.seekwd.dormcheck.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.mybatis.spring.annotation.MapperScan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

@SpringBootApplication(scanBasePackages = "com.seekwd.dormcheck")
@EnableScheduling
@MapperScan("com.seekwd.dormcheck.mapper")
public class DormCheckApplication {
    public static void main(String[] args) {
        String database = System.getenv().getOrDefault("DORMCHECK_DATABASE", "./var/dormcheck.sqlite3");
        try {
            Path parent = Path.of(database).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                try {
                    Files.setPosixFilePermissions(parent, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                } catch (UnsupportedOperationException ignored) {
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("could not create the private database directory", error);
        }
        SpringApplication.run(DormCheckApplication.class, args);
    }
}
