package com.github.tangyi.baseline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Local synthetic-data checks, not MySQL or full exam-flow acceptance tests. */
class LocalDatabaseBaselineTests {
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:baseline_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
        source.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE baseline_attempts (id BIGINT PRIMARY KEY, score INTEGER)");
        jdbc.update("INSERT INTO baseline_attempts VALUES (1, 60), (2, 90), (3, NULL)");
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) { jdbc.execute("SHUTDOWN"); }
    }

    @Test
    void aggregateExcludesUngradedAnswers() {
        Double average = jdbc.queryForObject("SELECT AVG(score) FROM baseline_attempts", Double.class);
        assertNotNull(average);
        assertEquals(75.0, average, 0.001);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(score) FROM baseline_attempts", Integer.class));
    }

    @Test
    void failureRollsBackAllUpdates() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE baseline_attempts SET score = 100 WHERE id = 1");
            jdbc.update("INSERT INTO baseline_attempts VALUES (4, 80)");
            throw new IllegalStateException("Synthetic grading failure");
        }));
        assertEquals(60, jdbc.queryForObject("SELECT score FROM baseline_attempts WHERE id = 1", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM baseline_attempts", Integer.class));
    }

    @Test
    void duplicateIdentifierIsRejectedByDatabase() {
        assertThrows(DuplicateKeyException.class,
            () -> jdbc.update("INSERT INTO baseline_attempts VALUES (1, 100)"));
        assertEquals(60, jdbc.queryForObject("SELECT score FROM baseline_attempts WHERE id = 1", Integer.class));
    }
}
