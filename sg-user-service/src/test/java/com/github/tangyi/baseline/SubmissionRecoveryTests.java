package com.github.tangyi.baseline;

import com.github.tangyi.api.exam.model.ExaminationRecord;
import com.github.tangyi.exam.service.ExamRecordService;
import com.github.tangyi.exam.service.exam.ExaminationActionService;
import com.github.tangyi.exam.service.exam.SubmissionRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubmissionRecoveryTests {

    @Test
    void onlyReservedStaleRecordIsRequeued() {
        ExamRecordService records = mock(ExamRecordService.class);
        ExaminationActionService actions = mock(ExaminationActionService.class);
        SubmissionRecoveryService recovery = new SubmissionRecoveryService(records, actions);
        ReflectionTestUtils.setField(recovery, "staleMillis", 300000L);
        ExaminationRecord record = new ExaminationRecord();
        record.setId(3L);
        record.setTenantCode("tenant-a");
        when(records.findStaleCalculations(any(Date.class))).thenReturn(List.of(record));

        recovery.scan();
        verifyNoInteractions(actions);

        when(records.reserveStaleCalculation(eq(3L), eq("tenant-a"), any(Date.class))).thenReturn(true);
        recovery.scan();
        verify(actions).retryPendingCalculation(record);
    }
}
