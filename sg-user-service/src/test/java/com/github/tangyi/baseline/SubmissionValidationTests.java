package com.github.tangyi.baseline;

import com.github.tangyi.api.exam.dto.AnswerDto;
import com.github.tangyi.api.exam.enums.SubmitStatusEnum;
import com.github.tangyi.api.exam.model.Answer;
import com.github.tangyi.common.utils.TenantHolder;
import com.github.tangyi.exam.service.ExamRecordService;
import com.github.tangyi.exam.service.MaterialSubjectService;
import com.github.tangyi.exam.service.answer.AnswerService;
import com.github.tangyi.exam.service.exam.ExaminationActionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.*;

class SubmissionValidationTests {
    private ExamRecordService records;
    private AnswerService answers;
    private MaterialSubjectService materials;
    private ExaminationActionService action;

    @BeforeEach
    void setUp() {
        records = mock(ExamRecordService.class);
        answers = mock(AnswerService.class);
        materials = mock(MaterialSubjectService.class);
        action = new ExaminationActionService(null, null, null, null, records, null, answers,
                null, null, null, materials);
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
    void mixedRecordsAreRejectedBeforeClaim() {
        assertFalse(action.submitAllAnonymous(100L, List.of(answer(1, 11), answer(2, 12))));
        verifyNoInteractions(records, answers);
    }

    @Test
    void duplicateSubjectsAreRejectedBeforeClaim() {
        assertFalse(action.submitAllAnonymous(100L, List.of(answer(1, 11), answer(1, 11))));
        verifyNoInteractions(records, answers);
    }

    @Test
    void unclaimedRecordDoesNotWriteAnswers() {
        assertFalse(action.submitAllAnonymous(100L, List.of(answer(1, 11))));
        verify(records).claimAnonymousSubmission(eq(1L), eq(100L), eq("tenant-a"), eq(SubmitStatusEnum.CALCULATE));
        verifyNoInteractions(answers);
    }

    @Test
    void clientAnswerIdCannotReplaceDatabaseAnswerId() {
        AnswerDto request = answer(1, 11);
        request.setId(999L);
        request.setAnswer("A");
        Answer stored = new Answer();
        stored.setId(77L);
        stored.setExamRecordId(1L);
        stored.setSubjectId(11L);
        when(records.claimAnonymousSubmission(1L, 100L, "tenant-a", SubmitStatusEnum.CALCULATE))
                .thenReturn(true);
        when(answers.batchFindByRecordIdAndSubjectId(eq(1L), any(Long[].class)))
                .thenReturn(List.of(stored));
        doThrow(new IllegalStateException("Stop before grading"))
                .when(answers).batchUpdate(any());

        assertThrows(IllegalStateException.class, () -> action.submitAllAnonymous(100L, List.of(request)));
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Answer>> captured = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(answers).batchUpdate(captured.capture());
        assertEquals(77L, captured.getValue().get(0).getId());
    }

    private static AnswerDto answer(long recordId, long subjectId) {
        AnswerDto answer = new AnswerDto();
        answer.setExamRecordId(recordId);
        answer.setSubjectId(subjectId);
        return answer;
    }
}
