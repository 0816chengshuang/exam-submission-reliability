package com.github.tangyi.baseline;

import com.github.tangyi.exam.mapper.ExamRecordMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the production MyBatis claim statements against isolated H2 or MySQL rows. */
class SubmissionClaimTests {
    private JdbcTemplate jdbc;
    private SqlSessionFactory sessions;
    private SqlSessionTemplate transactionalSessions;
    private TransactionTemplate transaction;
    private boolean mysql;

    @BeforeEach
    void setUp() throws Exception {
        String configuredMysqlUrl = System.getenv("SG_TEST_MYSQL_URL");
        mysql = configuredMysqlUrl != null && !configuredMysqlUrl.isBlank();
        if (mysql && !configuredMysqlUrl.startsWith("jdbc:mysql://127.0.0.1:13306/sg_exam_test_")) {
            throw new IllegalArgumentException("MySQL tests require an isolated loopback sg_exam_test_* database");
        }
        String url = mysql ? configuredMysqlUrl :
                "jdbc:h2:mem:submission_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
        if (mysql) {
            source.setUsername(System.getenv().getOrDefault("SG_TEST_MYSQL_USER", "root"));
            source.setPassword(System.getenv().getOrDefault("SG_TEST_MYSQL_PASSWORD", ""));
        }
        source.setDriverClassName(mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        jdbc = new JdbcTemplate(source);
        if (mysql) {
            jdbc.execute("DROP TABLE IF EXISTS exam_examination_record");
        }
        jdbc.execute("CREATE TABLE exam_examination_record (id BIGINT PRIMARY KEY, user_id BIGINT, " +
                "examination_id BIGINT, tenant_code VARCHAR(16), submit_status INT, is_deleted INT, " +
                "update_time TIMESTAMP)");
        jdbc.execute("CREATE INDEX idx_submit_recovery ON exam_examination_record " +
                "(submit_status, is_deleted, update_time, id)");
        jdbc.update("INSERT INTO exam_examination_record VALUES (1, 10, 100, 'tenant-a', 0, 0, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO exam_examination_record VALUES (2, 20, 200, 'tenant-a', 0, 1, CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO exam_examination_record VALUES (3, 10, 100, 'tenant-a', 2, 0, ?)",
                Timestamp.from(Instant.now().minusSeconds(600)));
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/exam/ExamRecodeMapper.xml"));
        sessions = factory.getObject();
        transactionalSessions = new SqlSessionTemplate(sessions);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) {
            jdbc.execute(mysql ? "DROP TABLE IF EXISTS exam_examination_record" : "SHUTDOWN");
        }
    }

    @Test
    void onlyOwnerCanClaimOnce() {
        try (SqlSession session = sessions.openSession(true)) {
            ExamRecordMapper mapper = session.getMapper(ExamRecordMapper.class);
            assertEquals(0, mapper.claimSubmission(1L, 11L, "tenant-a", 2));
            assertEquals(0, mapper.claimSubmission(1L, 10L, "tenant-b", 2));
            assertEquals(0, mapper.claimSubmission(2L, 20L, "tenant-a", 2));
            assertEquals(1, mapper.claimSubmission(1L, 10L, "tenant-a", 2));
            assertEquals(0, mapper.claimSubmission(1L, 10L, "tenant-a", 2));
        }
        assertEquals(2, jdbc.queryForObject("SELECT submit_status FROM exam_examination_record WHERE id=1", Integer.class));
    }

    @Test
    void anonymousClaimRequiresMatchingExamAndTenant() {
        try (SqlSession session = sessions.openSession(true)) {
            ExamRecordMapper mapper = session.getMapper(ExamRecordMapper.class);
            assertEquals(0, mapper.claimAnonymousSubmission(1L, 200L, "tenant-a", 2));
            assertEquals(0, mapper.claimAnonymousSubmission(1L, 100L, "tenant-b", 2));
            assertEquals(1, mapper.claimAnonymousSubmission(1L, 100L, "tenant-a", 2));
            assertEquals(0, mapper.claimAnonymousSubmission(1L, 100L, "tenant-a", 2));
        }
    }

    @Test
    void rolledBackClaimCanBeRetried() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(1, transactionalSessions.getMapper(ExamRecordMapper.class)
                    .claimSubmission(1L, 10L, "tenant-a", 2));
            throw new IllegalStateException("Synthetic grading failure");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT submit_status FROM exam_examination_record WHERE id=1", Integer.class));
        try (SqlSession session = sessions.openSession(true)) {
            assertEquals(1, session.getMapper(ExamRecordMapper.class).claimSubmission(1L, 10L, "tenant-a", 2));
        }
    }

    @Test
    void concurrentClaimsHaveOneWinner() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        try {
            Future<Integer> first = workers.submit(() -> {
                return transaction.execute(status -> {
                    int count = transactionalSessions.getMapper(ExamRecordMapper.class)
                            .claimSubmission(1L, 10L, "tenant-a", 2);
                    firstClaimed.countDown();
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return count;
                });
            });
            assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));
            Future<Integer> second = workers.submit(() -> {
                return transaction.execute(status -> transactionalSessions.getMapper(ExamRecordMapper.class)
                        .claimSubmission(1L, 10L, "tenant-a", 2));
            });
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, second.get(5, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void staleCalculationCanBeReservedAndGradedOnce() {
        Date cutoff = Date.from(Instant.now().minusSeconds(300));
        try (SqlSession session = sessions.openSession(true)) {
            ExamRecordMapper mapper = session.getMapper(ExamRecordMapper.class);
            assertEquals(1, mapper.findStaleCalculations(cutoff).size());
            assertEquals(0, mapper.reserveStaleCalculation(3L, "tenant-b", cutoff));
            assertEquals(1, mapper.reserveStaleCalculation(3L, "tenant-a", cutoff));
            assertEquals(0, mapper.reserveStaleCalculation(3L, "tenant-a", cutoff));
            assertEquals(0, mapper.claimGrading(3L, "tenant-b", 4));
            assertEquals(1, mapper.claimGrading(3L, "tenant-a", 4));
            assertEquals(0, mapper.claimGrading(3L, "tenant-a", 4));
        }
        assertEquals(4, jdbc.queryForObject("SELECT submit_status FROM exam_examination_record WHERE id=3", Integer.class));
    }

    @Test
    void crashedWorkerRollsBackClaimForRecovery() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertEquals(1, transactionalSessions.getMapper(ExamRecordMapper.class)
                    .claimGrading(3L, "tenant-a", 4));
            throw new IllegalStateException("Synthetic worker failure");
        }));
        assertEquals(2, jdbc.queryForObject("SELECT submit_status FROM exam_examination_record WHERE id=3", Integer.class));
        try (SqlSession session = sessions.openSession(true)) {
            assertEquals(1, session.getMapper(ExamRecordMapper.class).claimGrading(3L, "tenant-a", 4));
        }
    }
}
