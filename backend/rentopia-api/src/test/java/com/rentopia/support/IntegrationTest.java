package com.rentopia.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need the full application on a real PostgreSQL.
 * Flyway runs V1..Vn on context start, exactly as in production.
 * All subclasses share one cached Spring context and one database, so
 * tests create their own uniquely-named data instead of relying on cleanup.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }
}
