package com.codearena.controller;

import com.codearena.entity.Problem;
import com.codearena.entity.TestCase;
import com.codearena.service.ProblemService;
import com.codearena.dto.ProblemAdminResponseDto;
import com.codearena.dto.ProblemPublicDto;
import com.codearena.dto.ProblemRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class ProblemControllerTest {

    @Mock
    private ProblemService problemService;

    @InjectMocks
    private ProblemController problemController;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetAllProblems() {
        Problem problem = new Problem();
        problem.setId(1L);
        problem.setTitle("Two Sum");

        when(problemService.getAllProblems()).thenReturn(List.of(problem));

        ResponseEntity<List<ProblemPublicDto>> response = problemController.getAllProblems();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Two Sum", response.getBody().get(0).getTitle());
    }

    @Test
    public void testGetProblemByIdSuccess() {
        Problem problem = new Problem();
        problem.setId(1L);
        problem.setTitle("Two Sum");

        when(problemService.getProblemById(1L)).thenReturn(problem);

        ResponseEntity<?> response = problemController.getProblem(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertInstanceOf(ProblemPublicDto.class, response.getBody());
        assertEquals("Two Sum", ((ProblemPublicDto) response.getBody()).getTitle());
    }

    @Test
    public void testGetProblemByIdNotFound() {
        when(problemService.getProblemById(99L)).thenThrow(new IllegalArgumentException("Problem not found with id: 99"));

        ResponseEntity<?> response = problemController.getProblem(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    public void testGetProblemForAdminSuccess() {
        Problem problem = new Problem();
        problem.setId(1L);
        problem.setTitle("Two Sum");
        List<TestCase> testCases = List.of(new TestCase(1L, 1L, "1 2", "3", true, null));
        ProblemAdminResponseDto responseDto = new ProblemAdminResponseDto(problem, testCases, List.of());

        when(problemService.getProblemForAdmin(1L)).thenReturn(responseDto);

        ResponseEntity<?> response = problemController.getProblemForAdmin(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(responseDto, response.getBody());
    }

    @Test
    public void testCreateProblem() {
        ProblemRequestDto dto = new ProblemRequestDto();
        dto.setTitle("New Problem");

        Problem savedProblem = new Problem();
        savedProblem.setId(10L);
        savedProblem.setTitle("New Problem");

        when(problemService.createProblem(dto)).thenReturn(savedProblem);

        ResponseEntity<Problem> response = problemController.createProblem(dto);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10L, response.getBody().getId());
    }

    @Test
    public void testUpdateProblem() {
        ProblemRequestDto dto = new ProblemRequestDto();
        dto.setTitle("Updated Title");

        Problem savedProblem = new Problem();
        savedProblem.setId(10L);
        savedProblem.setTitle("Updated Title");

        when(problemService.updateProblem(10L, dto)).thenReturn(savedProblem);

        ResponseEntity<Problem> response = problemController.updateProblem(10L, dto);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Updated Title", response.getBody().getTitle());
    }

    @Test
    public void testDeleteProblemSuccess() {
        doNothing().when(problemService).deleteProblem(1L);

        ResponseEntity<?> response = problemController.deleteProblem(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(problemService, times(1)).deleteProblem(1L);
    }
}
