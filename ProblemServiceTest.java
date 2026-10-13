package com.codearena.service;

import com.codearena.dto.EditorialResponseDto;
import com.codearena.entity.Difficulty;
import com.codearena.entity.Hint;
import com.codearena.entity.Problem;
import com.codearena.entity.SubmissionStatus;
import com.codearena.repository.HintRepository;
import com.codearena.repository.ProblemRepository;
import com.codearena.repository.SubmissionRepository;
import com.codearena.repository.TestCaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Milestone 2 - Hints & gated editorial:
 * - Hints are returned in order, revealed on demand.
 * - The full editorial (solution) stays locked until the user has made
 *   enough non-accepted attempts on the problem.
 */
public class ProblemServiceTest {

    @Mock
    private ProblemRepository problemRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private HintRepository hintRepository;

    @Mock
    private SubmissionRepository submissionRepository;

    @InjectMocks
    private ProblemService problemService;

    private Problem problem;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        // Default unlock threshold used by tests, matching application.properties default.
        setField("editorialUnlockAttempts", 3);

        problem = new Problem();
        problem.setId(1L);
        problem.setTitle("Two Sum");
        problem.setDescription("desc");
        problem.setDifficulty(Difficulty.EASY);
        problem.setSolution("Use a hash map to track complements.");
    }

    private void setField(String name, Object value) {
        try {
            var field = ProblemService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(problemService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testGetHintsReturnsOrderedContent() {
        Hint h1 = new Hint(1L, 1L, 0, "Think about complements.");
        Hint h2 = new Hint(2L, 1L, 1, "Try a hash map for O(n).");
        when(hintRepository.findByProblemIdOrderByOrderIndexAsc(1L)).thenReturn(List.of(h1, h2));

        List<String> hints = problemService.getHints(1L);

        assertEquals(2, hints.size());
        assertEquals("Think about complements.", hints.get(0));
        assertEquals("Try a hash map for O(n).", hints.get(1));
    }

    @Test
    public void testEditorialLockedBeforeThreshold() {
        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));
        when(submissionRepository.countByUserIdAndProblemIdAndStatusNot(100L, 1L, SubmissionStatus.ACCEPTED))
                .thenReturn(1L);

        EditorialResponseDto response = problemService.getEditorial(1L, 100L);

        assertTrue(response.isLocked());
        assertNull(response.getSolution());
        assertEquals(1, response.getAttemptsMade());
        assertEquals(3, response.getAttemptsRequired());
    }

    @Test
    public void testEditorialUnlockedAtThreshold() {
        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));
        when(submissionRepository.countByUserIdAndProblemIdAndStatusNot(100L, 1L, SubmissionStatus.ACCEPTED))
                .thenReturn(3L);

        EditorialResponseDto response = problemService.getEditorial(1L, 100L);

        assertFalse(response.isLocked());
        assertEquals("Use a hash map to track complements.", response.getSolution());
    }
}
