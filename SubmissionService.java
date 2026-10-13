package com.codearena.service;

import com.codearena.dto.SubmissionRequest;
import com.codearena.dto.CheckResponse;
import com.codearena.entity.Submission;
import com.codearena.entity.SubmissionStatus;
import com.codearena.entity.SubmissionResult;
import com.codearena.entity.LeaderboardEntry;
import com.codearena.entity.TestCase;
import com.codearena.repository.SubmissionRepository;
import com.codearena.repository.TestCaseRepository;
import com.codearena.repository.SubmissionResultRepository;
import com.codearena.repository.LeaderboardEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SubmissionService {

    private final SubmissionRepository submissionRepository;
    private final TestCaseRepository testCaseRepository;
    private final SubmissionResultRepository submissionResultRepository;
    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final JudgeService judgeService;

    @Transactional
    public Submission submit(Long userId, SubmissionRequest request) {
        // Enforce submission rate limiting — a maximum of 5 submissions per problem per minute per user
        LocalDateTime oneMinuteAgo = LocalDateTime.now().minusMinutes(1);
        long submissionCount = submissionRepository.countByUserIdAndProblemIdAndSubmittedAtAfter(userId, request.getProblemId(), oneMinuteAgo);
        if (submissionCount >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Submission rate limit exceeded. Max 5 submissions per problem per minute.");
        }

        List<TestCase> testCases = testCaseRepository.findByProblemId(request.getProblemId());

        Submission submission = new Submission();
        submission.setUserId(userId);
        submission.setProblemId(request.getProblemId());
        submission.setCode(request.getCode());
        submission.setLanguage(request.getLanguage());

        if (testCases.isEmpty()) {
            submission.setStatus(SubmissionStatus.RUNTIME_ERROR);
            submission.setOutput("No test cases configured for this problem yet.");
            submission.setExecutionTimeMs(0L);
            return submissionRepository.save(submission);
        }

        // Convert test cases to inputs
        List<JudgeService.TestCaseInput> inputs = testCases.stream()
                .map(tc -> new JudgeService.TestCaseInput(tc.getInput(), tc.getExpectedOutput(), tc.getTimeLimitOverride()))
                .toList();

        // Run evaluation
        List<JudgeService.TestCaseVerdict> verdicts = judgeService.evaluate(request.getCode(), request.getLanguage(), inputs);

        SubmissionStatus overallStatus = SubmissionStatus.ACCEPTED;
        long totalExecutionTime = 0;
        String overallOutput = "All test cases passed";

        // Save submission first to get an ID for results foreign key
        submission.setStatus(SubmissionStatus.PENDING);
        submission.setOutput("");
        submission.setExecutionTimeMs(0L);
        Submission savedSubmission = submissionRepository.save(submission);

        List<SubmissionResult> results = new ArrayList<>();

        if (!verdicts.isEmpty() && "COMPILATION_ERROR".equals(verdicts.get(0).status)) {
            overallStatus = SubmissionStatus.COMPILATION_ERROR;
            overallOutput = verdicts.get(0).errorMessage;
            totalExecutionTime = verdicts.get(0).executionTimeMs;

            SubmissionResult res = new SubmissionResult();
            res.setSubmission(savedSubmission);
            res.setTestCaseId(-1L);
            res.setStatus(SubmissionStatus.COMPILATION_ERROR);
            res.setExecutionTimeMs(totalExecutionTime);
            res.setMemoryUsedBytes(0L);
            res.setErrorMessage(overallOutput);
            submissionResultRepository.save(res);
            results.add(res);
        } else {
            for (int i = 0; i < testCases.size(); i++) {
                TestCase tc = testCases.get(i);
                JudgeService.TestCaseVerdict v = i < verdicts.size() ? verdicts.get(i) : 
                        new JudgeService.TestCaseVerdict("RUNTIME_ERROR", "", "Internal judge error", 0, 0);

                SubmissionResult res = new SubmissionResult();
                res.setSubmission(savedSubmission);
                res.setTestCaseId(tc.getId());
                
                SubmissionStatus caseStatus;
                try {
                    caseStatus = SubmissionStatus.valueOf(v.status);
                } catch (Exception e) {
                    caseStatus = SubmissionStatus.RUNTIME_ERROR;
                }
                
                res.setStatus(caseStatus);
                res.setExecutionTimeMs(v.executionTimeMs);
                res.setMemoryUsedBytes(v.memoryUsedBytes);
                res.setActualOutput(v.actualOutput);
                res.setErrorMessage(v.errorMessage);
                submissionResultRepository.save(res);
                results.add(res);

                totalExecutionTime += v.executionTimeMs;

                // Determine overall status based on first failure
                if (overallStatus == SubmissionStatus.ACCEPTED && caseStatus != SubmissionStatus.ACCEPTED) {
                    overallStatus = caseStatus;
                    overallOutput = v.errorMessage != null && !v.errorMessage.isEmpty() ? v.errorMessage : v.actualOutput;
                }
            }
        }

        savedSubmission.setStatus(overallStatus);
        savedSubmission.setOutput(overallOutput);
        savedSubmission.setExecutionTimeMs(totalExecutionTime);
        savedSubmission.setTestCaseResults(results);

        Submission finalSaved = submissionRepository.save(savedSubmission);

        // Update user stats in leaderboard
        updateLeaderboard(userId);

        return finalSaved;
    }

    private void updateLeaderboard(Long userId) {
        List<Submission> allSubmissions = submissionRepository.findByUserIdOrderBySubmittedAtDesc(userId);
        if (allSubmissions.isEmpty()) return;

        long totalSubmissions = allSubmissions.size();
        List<Submission> acceptedSubmissions = allSubmissions.stream()
                .filter(s -> SubmissionStatus.ACCEPTED == s.getStatus())
                .toList();

        long solvedCount = acceptedSubmissions.stream()
                .map(Submission::getProblemId)
                .distinct()
                .count();

        double accuracy = totalSubmissions > 0 ? (acceptedSubmissions.size() * 100.0) / totalSubmissions : 0.0;
        long totalTimeMs = acceptedSubmissions.stream()
                .mapToLong(Submission::getExecutionTimeMs)
                .sum();

        LeaderboardEntry entry = leaderboardEntryRepository.findByUserId(userId)
                .orElse(new LeaderboardEntry());
        entry.setUserId(userId);
        entry.setSolvedCount(solvedCount);
        entry.setAccuracy(accuracy);
        entry.setTotalTimeMs(totalTimeMs);
        entry.setUpdatedAt(LocalDateTime.now());

        leaderboardEntryRepository.save(entry);
    }

    @Transactional(readOnly = true)
    public CheckResponse check(SubmissionRequest request) {
        List<TestCase> testCases = testCaseRepository.findByProblemId(request.getProblemId());
        List<TestCase> sampleTestCases = testCases.stream().filter(TestCase::isSample).toList();
        if (sampleTestCases.isEmpty()) {
            sampleTestCases = testCases.isEmpty() ? List.of() : List.of(testCases.get(0));
        }

        List<CheckResponse.TestCaseResult> results = new ArrayList<>();
        boolean overallPassed = true;

        for (TestCase tc : sampleTestCases) {
            JudgeService.JudgeVerdict verdict = judgeService.run(request.getCode(), request.getLanguage(), tc.getInput(), tc.getExpectedOutput());
            boolean passed = "ACCEPTED".equals(verdict.status);
            if (!passed) {
                overallPassed = false;
            }
            results.add(new CheckResponse.TestCaseResult(
                    tc.getInput(),
                    tc.getExpectedOutput(),
                    verdict.actualOutput,
                    verdict.status,
                    verdict.executionTimeMs
            ));
        }

        return new CheckResponse(overallPassed && !results.isEmpty(), results);
    }

    @Transactional(readOnly = true)
    public List<Submission> getSubmissionsForUser(Long userId) {
        return submissionRepository.findByUserIdOrderBySubmittedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Submission getSubmissionById(Long id) {
        return submissionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Submission not found"));
    }

    @Transactional(readOnly = true)
    public List<Submission> getSubmissionsForProblem(Long problemId) {
        return submissionRepository.findByProblemIdOrderBySubmittedAtDesc(problemId);
    }
}
