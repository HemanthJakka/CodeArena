package com.codearena.service;

import com.codearena.dto.EditorialResponseDto;
import com.codearena.dto.ProblemAdminResponseDto;
import com.codearena.dto.ProblemRequestDto;
import com.codearena.entity.Hint;
import com.codearena.entity.Problem;
import com.codearena.entity.SubmissionStatus;
import com.codearena.entity.TestCase;
import com.codearena.repository.HintRepository;
import com.codearena.repository.ProblemRepository;
import com.codearena.repository.SubmissionRepository;
import com.codearena.repository.TestCaseRepository;
import com.codearena.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProblemService {

    private final ProblemRepository problemRepository;
    private final TestCaseRepository testCaseRepository;
    private final HintRepository hintRepository;
    private final SubmissionRepository submissionRepository;

    @Value("${app.editorial.unlock-attempts:3}")
    private int editorialUnlockAttempts;

    public List<Problem> getAllProblems() {
        return problemRepository.findAll();
    }

    /**
     * Percentage (0-100) of all submissions for this problem that were ACCEPTED.
     * Returns null (rather than 0) if the problem has never been attempted, so
     * the frontend can render "—" instead of a misleading 0%.
     */
    public Double getAcceptanceRate(Long problemId) {
        long total = submissionRepository.countByProblemId(problemId);
        if (total == 0) {
            return null;
        }
        long accepted = submissionRepository.countByProblemIdAndStatus(problemId, SubmissionStatus.ACCEPTED);
        return Math.round((accepted * 1000.0) / total) / 10.0; // one decimal place
    }

    public Problem getProblemById(Long id) {
        return problemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Problem not found with id: " + id));
    }

    public ProblemAdminResponseDto getProblemForAdmin(Long id) {
        Problem problem = getProblemById(id);
        List<TestCase> testCases = testCaseRepository.findByProblemId(id);
        List<Hint> hints = hintRepository.findByProblemIdOrderByOrderIndexAsc(id);
        return new ProblemAdminResponseDto(problem, testCases, hints);
    }

    /**
     * Ordered hint contents for a problem. Hints are revealed on demand by the
     * frontend (one at a time); this endpoint simply returns them in order.
     */
    public List<String> getHints(Long id) {
        return hintRepository.findByProblemIdOrderByOrderIndexAsc(id)
                .stream()
                .map(h -> h.getContent())
                .toList();
    }

    /**
     * Returns the full editorial (solution) only once the user has made at
     * least {@code editorialUnlockAttempts} non-accepted submissions for this
     * problem, encouraging independent problem-solving first.
     */
    public EditorialResponseDto getEditorial(Long id, Long userId) {
        Problem problem = getProblemById(id);
        long failedAttempts = submissionRepository.countByUserIdAndProblemIdAndStatusNot(
                userId, id, SubmissionStatus.ACCEPTED);

        if (failedAttempts >= editorialUnlockAttempts) {
            String solution = (problem.getSolution() == null || problem.getSolution().isBlank())
                    ? "No editorial has been written for this problem yet."
                    : problem.getSolution();
            return new EditorialResponseDto(false, (int) failedAttempts, editorialUnlockAttempts, solution);
        }
        return new EditorialResponseDto(true, (int) failedAttempts, editorialUnlockAttempts, null);
    }

    @Transactional
    public Problem createProblem(ProblemRequestDto dto) {
        Problem problem = new Problem();
        problem.setTitle(dto.getTitle());
        problem.setDescription(dto.getDescription());
        problem.setDifficulty(dto.getDifficulty());
        problem.setSampleInput(dto.getSampleInput());
        problem.setSampleOutput(dto.getSampleOutput());
        problem.setConstraints(dto.getConstraints());
        problem.setTags(dto.getTags());
        problem.setStarterCodeJava(dto.getStarterCodeJava());
        problem.setStarterCodePython(dto.getStarterCodePython());
        problem.setStarterCodeCpp(dto.getStarterCodeCpp());
        problem.setExpectedTimeComplexity(dto.getExpectedTimeComplexity());
        problem.setExpectedSpaceComplexity(dto.getExpectedSpaceComplexity());
        problem.setSolution(dto.getSolution());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal) {
            problem.setCreatedBy(((UserPrincipal) auth.getPrincipal()).getId());
        }

        Problem savedProblem = problemRepository.save(problem);

        if (dto.getTestCases() != null) {
            for (ProblemRequestDto.TestCaseDto tcDto : dto.getTestCases()) {
                TestCase tc = new TestCase();
                tc.setProblemId(savedProblem.getId());
                tc.setInput(tcDto.getInput());
                tc.setExpectedOutput(tcDto.getExpectedOutput());
                tc.setSample(tcDto.isSample());
                tc.setTimeLimitOverride(tcDto.getTimeLimitOverride());
                testCaseRepository.save(tc);
            }
        }

        saveHints(savedProblem.getId(), dto.getHints());

        return savedProblem;
    }

    private void saveHints(Long problemId, List<String> hints) {
        if (hints == null) {
            return;
        }
        int order = 0;
        for (String content : hints) {
            if (content == null || content.isBlank()) {
                continue;
            }
            Hint hint = new Hint();
            hint.setProblemId(problemId);
            hint.setOrderIndex(order++);
            hint.setContent(content);
            hintRepository.save(hint);
        }
    }

    @Transactional
    public Problem updateProblem(Long id, ProblemRequestDto dto) {
        Problem problem = problemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Problem not found with id: " + id));

        problem.setTitle(dto.getTitle());
        problem.setDescription(dto.getDescription());
        problem.setDifficulty(dto.getDifficulty());
        problem.setSampleInput(dto.getSampleInput());
        problem.setSampleOutput(dto.getSampleOutput());
        problem.setConstraints(dto.getConstraints());
        problem.setTags(dto.getTags());
        problem.setStarterCodeJava(dto.getStarterCodeJava());
        problem.setStarterCodePython(dto.getStarterCodePython());
        problem.setStarterCodeCpp(dto.getStarterCodeCpp());
        problem.setExpectedTimeComplexity(dto.getExpectedTimeComplexity());
        problem.setExpectedSpaceComplexity(dto.getExpectedSpaceComplexity());
        problem.setSolution(dto.getSolution());

        Problem savedProblem = problemRepository.save(problem);

        List<TestCase> oldTestCases = testCaseRepository.findByProblemId(id);
        testCaseRepository.deleteAll(oldTestCases);

        if (dto.getTestCases() != null) {
            for (ProblemRequestDto.TestCaseDto tcDto : dto.getTestCases()) {
                TestCase tc = new TestCase();
                tc.setProblemId(id);
                tc.setInput(tcDto.getInput());
                tc.setExpectedOutput(tcDto.getExpectedOutput());
                tc.setSample(tcDto.isSample());
                tc.setTimeLimitOverride(tcDto.getTimeLimitOverride());
                testCaseRepository.save(tc);
            }
        }

        List<Hint> oldHints = hintRepository.findByProblemIdOrderByOrderIndexAsc(id);
        hintRepository.deleteAll(oldHints);
        saveHints(id, dto.getHints());

        return savedProblem;
    }

    @Transactional
    public void deleteProblem(Long id) {
        Problem problem = problemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Problem not found with id: " + id));

        List<TestCase> testCases = testCaseRepository.findByProblemId(id);
        testCaseRepository.deleteAll(testCases);
        List<Hint> hints = hintRepository.findByProblemIdOrderByOrderIndexAsc(id);
        hintRepository.deleteAll(hints);
        problemRepository.delete(problem);
    }
}
