package com.github.tangyi.exam.service.exam;

import com.github.tangyi.api.exam.model.ExaminationRecord;
import com.github.tangyi.exam.service.ExamRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "exam.submission.recovery-enabled", havingValue = "true")
public class SubmissionRecoveryService {

    private final ExamRecordService records;
    private final ExaminationActionService actions;

    @Value("${exam.submission.recovery-stale-ms:300000}")
    private long staleMillis;

    @Scheduled(initialDelayString = "${exam.submission.recovery-initial-delay-ms:60000}",
            fixedDelayString = "${exam.submission.recovery-delay-ms:300000}")
    public void scan() {
        Date cutoff = new Date(System.currentTimeMillis() - staleMillis);
        int queued = 0;
        for (ExaminationRecord record : records.findStaleCalculations(cutoff)) {
            if (records.reserveStaleCalculation(record.getId(), record.getTenantCode(), cutoff)) {
                actions.retryPendingCalculation(record);
                queued++;
            }
        }
        if (queued > 0) {
            log.info("Requeued {} stale exam calculations", queued);
        }
    }
}
