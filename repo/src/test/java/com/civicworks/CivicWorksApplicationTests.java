package com.civicworks;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test placeholder.
 * Requires a running PostgreSQL instance with Flyway migrations applied.
 * Use docker-compose to spin up the DB before running integration tests.
 *
 * For CI, prefer @WebMvcTest slices with mocked beans to avoid DB dependency.
 */
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.quartz.QuartzAutoConfiguration"
})
class CivicWorksApplicationTests {

    @Test
    void contextLoads() {
        // Context loading test is intentionally skipped here to avoid requiring
        // a live database. Use docker-compose up before running with @SpringBootTest.
    }
}
