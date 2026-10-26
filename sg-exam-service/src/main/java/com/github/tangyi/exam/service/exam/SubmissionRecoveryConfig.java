package com.github.tangyi.exam.service.exam;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "exam.submission.recovery-enabled", havingValue = "true")
public class SubmissionRecoveryConfig {
}
