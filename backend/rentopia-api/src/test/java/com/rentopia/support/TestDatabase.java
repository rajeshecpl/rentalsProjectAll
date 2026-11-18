package com.rentopia.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL server (downloaded binaries, no Docker) shared by every
 * test in the JVM. Started on first use, stopped by a shutdown hook.
 */
public final class TestDatabase {

    private static EmbeddedPostgres instance;

    private TestDatabase() {
    }

    public static synchronized EmbeddedPostgres get() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.builder().start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        instance.close();
                    } catch (IOException ignored) {
                        // JVM is exiting
                    }
                }));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
            }
        }
        return instance;
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl("postgres", "postgres");
    }
}
