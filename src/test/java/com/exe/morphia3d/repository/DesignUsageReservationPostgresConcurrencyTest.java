package com.exe.morphia3d.repository;

import com.exe.morphia3d.design.DesignQuotaException;
import com.exe.morphia3d.design.DesignUsageLimits;
import com.exe.morphia3d.design.DesignUsageReservationService;
import com.exe.morphia3d.entity.DesignUsageReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=false"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({DesignUsageReservationService.class, DesignUsageLimits.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DesignUsageReservationPostgresConcurrencyTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("design_usage_reservation_test")
            .withUsername("design_usage_test")
            .withPassword("design_usage_test_only");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        POSTGRES.start();
        DesignPipelineTestDatabaseSetup.migratePostgres16(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired private DesignUsageReservationService service;
    @Autowired private DesignUsageLimits limits;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDisposableBudgetState() {
        jdbc.update("DELETE FROM design_usage_reservations");
        jdbc.update("DELETE FROM design_usage_budget_windows");
        limits.setPerJobUnits(1_000);
        limits.setPerOwnerDailyUnits(1_000);
        limits.setGlobalDailyUnits(1_000);
    }

    @Test
    void concurrentProjectsCannotExceedOneGlobalDailyBudget() throws Exception {
        SeededJob first = seedJob();
        SeededJob second = seedJob();

        List<Attempt> results = race(first, second, 600);

        assertThat(results).filteredOn(Attempt::reserved).hasSize(1);
        assertThat(results).extracting(Attempt::backendPid).doesNotHaveDuplicates();
        assertThat(results).filteredOn(result -> !result.reserved()).extracting(Attempt::errorCode)
                .containsExactly("DESIGN_DAILY_LIMIT_EXCEEDED");
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 600L).containsEntry("settled_units", 0L);
    }

    @Test
    void concurrentJobsForOneOwnerCannotExceedTheOwnerDailyCap() throws Exception {
        limits.setPerOwnerDailyUnits(10);
        int ownerId = seedUser();
        SeededJob first = seedJob(ownerId);
        SeededJob second = seedJob(ownerId);

        List<Attempt> results = race(first, second, 6);

        assertThat(results).filteredOn(Attempt::reserved).hasSize(1);
        assertThat(results).extracting(Attempt::backendPid).doesNotHaveDuplicates();
        assertThat(results).filteredOn(result -> !result.reserved()).extracting(Attempt::errorCode)
                .containsExactly("DESIGN_DAILY_LIMIT_EXCEEDED");
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(window("USER:" + ownerId)).containsEntry("reserved_units", 6L).containsEntry("settled_units", 0L);
    }

    @Test
    void replayForTheSameJobReturnsItsReservationWithoutCountingTwice() {
        SeededJob job = seedJob();

        DesignUsageReservation first = service.reserve(job.jobId(), job.ownerId(), 5);
        DesignUsageReservation replay = service.reserve(job.jobId(), job.ownerId(), 5);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 5L).containsEntry("settled_units", 0L);
        assertThatThrownBy(() -> service.reserve(job.jobId(), job.ownerId(), 4))
                .isInstanceOf(DesignQuotaException.class).hasMessage("DESIGN_RESERVATION_CONFLICT");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 5L).containsEntry("settled_units", 0L);
    }

    @Test
    void exceedingOwnerLimitRollsBackWithoutSavingReservationOrBudgetCounters() {
        limits.setPerOwnerDailyUnits(10);
        SeededJob job = seedJob();

        assertThatThrownBy(() -> service.reserve(job.jobId(), job.ownerId(), 11))
                .isInstanceOf(DesignQuotaException.class).hasMessage("DESIGN_DAILY_LIMIT_EXCEEDED");

        assertThat(reservationCount()).isZero();
        assertThat(budgetWindowCount()).isZero();
    }

    @Test
    void settlementAndZeroCostReleaseUpdateCountersOnlyOnce() {
        SeededJob settledJob = seedJob();
        SeededJob releasedJob = seedJob(settledJob.ownerId());
        service.reserve(settledJob.jobId(), settledJob.ownerId(), 6);

        service.settle(settledJob.jobId(), 3, "usage-3");
        service.settle(settledJob.jobId(), 3, "usage-3-replay");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 0L).containsEntry("settled_units", 3L);
        assertThat(window("USER:" + settledJob.ownerId())).containsEntry("reserved_units", 0L).containsEntry("settled_units", 3L);

        service.reserve(releasedJob.jobId(), releasedJob.ownerId(), 4);
        service.releaseZeroCost(releasedJob.jobId());
        service.releaseZeroCost(releasedJob.jobId());

        assertThat(reservationStatus(releasedJob.jobId())).isEqualTo("RELEASED");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 0L).containsEntry("settled_units", 3L);
        assertThat(window("USER:" + releasedJob.ownerId())).containsEntry("reserved_units", 0L).containsEntry("settled_units", 3L);
    }

    @Test
    void unknownUsageKeepsReservationAndCannotBeReleasedAutomatically() {
        SeededJob job = seedJob();
        service.reserve(job.jobId(), job.ownerId(), 7);

        service.settle(job.jobId(), -1, "provider-outcome-unknown");

        assertThat(reservationStatus(job.jobId())).isEqualTo("UNKNOWN");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 7L).containsEntry("settled_units", 0L);
        assertThat(window("USER:" + job.ownerId())).containsEntry("reserved_units", 7L).containsEntry("settled_units", 0L);
        assertThatThrownBy(() -> service.releaseZeroCost(job.jobId()))
                .isInstanceOf(DesignQuotaException.class).hasMessage("DESIGN_UNKNOWN_COST_CANNOT_RELEASE");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 7L).containsEntry("settled_units", 0L);
    }

    @Test
    void confirmedOutcomeSettlesTheEntireReservationAndUnknownRemainsReserved() {
        SeededJob completed = seedJob();
        service.reserve(completed.jobId(), completed.ownerId(), 7);
        service.settleReserved(completed.jobId(), "mesh-task-1");
        service.settleReserved(completed.jobId(), "mesh-task-1");
        assertThat(reservationStatus(completed.jobId())).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT settled_units FROM design_usage_reservations WHERE job_id=?", Long.class,
                completed.jobId())).isEqualTo(7L);
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 0L).containsEntry("settled_units", 7L);

        SeededJob unresolved = seedJob();
        service.reserve(unresolved.jobId(), unresolved.ownerId(), 5);
        service.settle(unresolved.jobId(), -1, "provider-submit-outcome-unknown");
        assertThat(reservationStatus(unresolved.jobId())).isEqualTo("UNKNOWN");
        assertThat(window("GLOBAL")).containsEntry("reserved_units", 5L).containsEntry("settled_units", 7L);
    }

    @Test
    void unconfiguredOrZeroLimitsFailClosedWithoutCreatingBudgetWindows() {
        limits.setPerJobUnits(0);
        limits.setPerOwnerDailyUnits(0);
        limits.setGlobalDailyUnits(0);
        SeededJob job = seedJob();

        assertThatThrownBy(() -> service.reserve(job.jobId(), job.ownerId(), 1))
                .isInstanceOf(DesignQuotaException.class).hasMessage("DESIGN_USAGE_DISABLED");

        assertThat(reservationCount()).isZero();
        assertThat(budgetWindowCount()).isZero();
    }

    private List<Attempt> race(SeededJob first, SeededJob second, long units) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Attempt> firstAttempt = workers.submit(() -> attempt(first, units, ready, start));
            Future<Attempt> secondAttempt = workers.submit(() -> attempt(second, units, ready, start));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstAttempt.get(), secondAttempt.get());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private Attempt attempt(SeededJob job, long units, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("concurrency gate timed out");
        AtomicInteger backendPid = new AtomicInteger();
        try {
            new TransactionTemplate(transactionManager).execute(status -> {
                backendPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                service.reserve(job.jobId(), job.ownerId(), units);
                return null;
            });
            return new Attempt(true, null, backendPid.get());
        } catch (DesignQuotaException rejected) {
            return new Attempt(false, rejected.getMessage(), backendPid.get());
        }
    }

    private SeededJob seedJob() {
        return seedJob(seedUser());
    }

    private SeededJob seedJob(int ownerId) {
        UUID projectId = UUID.randomUUID();
        UUID referenceSetId = UUID.randomUUID();
        UUID referenceVersionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.update("INSERT INTO design_projects(project_id,owner_user_id,product_type,style_id,input_mode,normalized_prompt,prompt_profile_version,status,create_idempotency_key,request_fingerprint) VALUES (?,?,'figure','test','text','quota test','v1','DRAFT',?,?)",
                projectId, ownerId, "create-" + projectId, "a".repeat(64));
        jdbc.update("INSERT INTO design_reference_sets(reference_set_id,project_id,owner_user_id) VALUES (?,?,?)",
                referenceSetId, projectId, ownerId);
        jdbc.update("INSERT INTO design_reference_set_versions(reference_version_id,reference_set_id,project_id,owner_user_id,version_number,status,normalized_prompt,prompt_profile_version) VALUES (?,?,?, ?,1,'READY_FOR_REVIEW','quota test','v1')",
                referenceVersionId, referenceSetId, projectId, ownerId);
        jdbc.update("INSERT INTO design_pipeline_jobs(job_id,project_id,owner_user_id,stage,status,reference_version_id,idempotency_key,request_fingerprint) VALUES (?,?,?,'REFERENCE_IMAGE','PENDING',?,?,?)",
                jobId, projectId, ownerId, referenceVersionId, "job-" + jobId, "b".repeat(64));
        return new SeededJob(jobId, ownerId);
    }

    private int seedUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES (?,?, 'test-only') RETURNING user_id",
                Integer.class, "Quota test", "quota-" + suffix + "@example.invalid");
    }

    private java.util.Map<String, Object> window(String scope) {
        return jdbc.queryForMap("SELECT reserved_units,settled_units FROM design_usage_budget_windows WHERE scope_key=? "
                        + "AND window_date=(CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date",
                scope);
    }

    private int reservationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM design_usage_reservations", Integer.class);
    }

    private int budgetWindowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM design_usage_budget_windows", Integer.class);
    }

    private String reservationStatus(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM design_usage_reservations WHERE job_id=?", String.class, jobId);
    }

    private record SeededJob(UUID jobId, int ownerId) { }
    private record Attempt(boolean reserved, String errorCode, int backendPid) { }
}
