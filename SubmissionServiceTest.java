package com.codearena.service;

import com.codearena.dto.SubmissionRequest;
import com.codearena.entity.Submission;
import com.codearena.entity.SubmissionStatus;
import com.codearena.entity.TestCase;
import com.codearena.repository.SubmissionRepository;
import com.codearena.repository.TestCaseRepository;
import com.codearena.repository.SubmissionResultRepository;
import com.codearena.repository.LeaderboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class SubmissionServiceTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private SubmissionResultRepository submissionResultRepository;

    @Mock
    private LeaderboardEntryRepository leaderboardEntryRepository;

    @Mock
    private JudgeService judgeService;

    @InjectMocks
    private SubmissionService submissionService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testSubmitNoTestCases() {
        SubmissionRequest request = new SubmissionRequest();
        request.setProblemId(1L);
        request.setCode("print(1)");
        request.setLanguage("PYTHON3");

        when(testCaseRepository.findByProblemId(1L)).thenReturn(Collections.emptyList());
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Mock rate limit check to return 0
        when(submissionRepository.countByUserIdAndProblemIdAndSubmittedAtAfter(eq(100L), eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);

        Submission submission = submissionService.submit(100L, request);

        assertEquals(SubmissionStatus.RUNTIME_ERROR, submission.getStatus());
        assertEquals("No test cases configured for this problem yet.", submission.getOutput());
        assertEquals(0L, submission.getExecutionTimeMs());
        verify(submissionRepository, times(1)).save(any(Submission.class));
    }

    @Test
    public void testSubmitSuccess() {
        SubmissionRequest request = new SubmissionRequest();
        request.setProblemId(1L);
        request.setCode("print(1)");
        request.setLanguage("PYTHON3");

        TestCase tc = new TestCase();
        tc.setId(10L);
        tc.setProblemId(1L);
        tc.setInput("input");
        tc.setExpectedOutput("expected");

        when(testCaseRepository.findByProblemId(1L)).thenReturn(List.of(tc));
        
        // Mock judge evaluation
        JudgeService.TestCaseVerdict verdict = new JudgeService.TestCaseVerdict("ACCEPTED", "expected", null, 42L, 1024L);
        when(judgeService.evaluate(eq("print(1)"), eq("PYTHON3"), anyList())).thenReturn(List.of(verdict));
        
        when(submissionRepository.save(any(Submission.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(submissionRepository.countByUserIdAndProblemIdAndSubmittedAtAfter(eq(100L), eq(1L), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(submissionRepository.findByUserIdOrderBySubmittedAtDesc(100L)).thenReturn(List.of());

        Submission submission = submissionService.submit(100L, request);

        assertEquals(SubmissionStatus.ACCEPTED, submission.getStatus());
        assertEquals("All test cases passed", submission.getOutput());
        assertEquals(42L, submission.getExecutionTimeMs());
        verify(submissionRepository, times(2)).save(any(Submission.class));
    }

    @Test
    public void testGetSubmissionsForUser() {
        Submission sub = new Submission();
        sub.setId(1L);
        sub.setUserId(100L);
        when(submissionRepository.findByUserIdOrderBySubmittedAtDesc(100L)).thenReturn(List.of(sub));

        List<Submission> result = submissionService.getSubmissionsForUser(100L);
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getUserId());
    }

    @Test
    public void testGetSubmissionsForProblem() {
        Submission sub = new Submission();
        sub.setId(1L);
        sub.setProblemId(2L);
        when(submissionRepository.findByProblemIdOrderBySubmittedAtDesc(2L)).thenReturn(List.of(sub));

        List<Submission> result = submissionService.getSubmissionsForProblem(2L);
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getProblemId());
    }
}
