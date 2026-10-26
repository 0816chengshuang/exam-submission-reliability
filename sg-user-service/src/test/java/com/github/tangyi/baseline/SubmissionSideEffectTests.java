package com.github.tangyi.baseline;

import com.github.tangyi.api.exam.enums.SubmitStatusEnum;
import com.github.tangyi.api.exam.model.Answer;
import com.github.tangyi.api.exam.model.ExaminationRecord;
import com.github.tangyi.api.exam.thread.IExecutorHolder;
import com.github.tangyi.common.utils.TenantHolder;
import com.github.tangyi.exam.service.ExamRecordService;
import com.github.tangyi.exam.service.RankInfoService;
import com.github.tangyi.exam.service.answer.AnswerService;
import com.github.tangyi.exam.service.exam.ExaminationActionService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmissionSideEffectTests {
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:side_effects;MODE=MySQL", "sa", "");
        source.setDriverClassName("org.h2.Driver");
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("demo-user", "", List.of()));
        TenantHolder.setTenantCode("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rankIsWrittenOnlyAfterCommit() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ZSetOperations<String, String> zset = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        RankInfoService rank = new RankInfoService(redis, null);
        ExaminationRecord record = new ExaminationRecord();
        record.setExaminationId(100L);
        record.setUserId(10L);
        record.setScore(80.0);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            rank.updateRankAfterCommit(record);
            verifyNoInteractions(redis);
            throw new IllegalStateException("Synthetic grading failure");
        }));
        verifyNoInteractions(redis);

        transaction.executeWithoutResult(status -> {
            rank.updateRankAfterCommit(record);
            verifyNoInteractions(redis);
        });
        verify(zset).add(anyString(), eq("10"), eq(80.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workerIsScheduledOnlyAfterCommit() {
        ExamRecordService records = mock(ExamRecordService.class);
        IExecutorHolder holder = mock(IExecutorHolder.class);
        ListeningExecutorService executor = mock(ListeningExecutorService.class);
        ListenableFuture<Boolean> future = mock(ListenableFuture.class);
        when(holder.getSubmitExecutor()).thenReturn(executor);
        when(executor.submit(any(Callable.class))).thenReturn(future);
        ExaminationRecord record = new ExaminationRecord();
        record.setId(1L);
        record.setExaminationId(100L);
        record.setTenantCode("tenant-a");
        when(records.get(1L)).thenReturn(record);
        when(records.claimAnonymousSubmission(1L, 100L, "tenant-a", SubmitStatusEnum.CALCULATE))
                .thenReturn(true);
        AnswerService answers = mock(AnswerService.class);
        when(answers.findListByExamRecordId(1L)).thenReturn(List.of(new Answer()));
        ExaminationActionService action = new ExaminationActionService(transaction.getTransactionManager(),
                null, null, null, records, null, answers, null, holder, null, null);
        Answer answer = new Answer();
        answer.setExamRecordId(1L);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(action.submitAsyncAnonymous(answer));
            verifyNoInteractions(executor);
            throw new IllegalStateException("Synthetic transaction failure");
        }));
        verifyNoInteractions(executor);

        transaction.executeWithoutResult(status -> {
            assertTrue(action.submitAsyncAnonymous(answer));
            verifyNoInteractions(executor);
        });
        verify(executor).submit(any(Callable.class));
    }
}
