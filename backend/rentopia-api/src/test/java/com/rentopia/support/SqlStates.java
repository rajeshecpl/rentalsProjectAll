package com.rentopia.support;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public final class SqlStates {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String CHECK_VIOLATION = "23514";
    public static final String EXCLUSION_VIOLATION = "23P01";

    private SqlStates() {
    }

    /** Asserts the call fails with a PostgreSQL error carrying the given SQLSTATE. */
    public static void assertSqlState(String expected, ThrowingCallable call) {
        assertThatThrownBy(call).satisfies(ex -> {
            String state = sqlState(ex);
            if (!expected.equals(state)) {
                throw new AssertionError("Expected SQLSTATE " + expected + " but got " + state, ex);
            }
        });
    }

    public static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
