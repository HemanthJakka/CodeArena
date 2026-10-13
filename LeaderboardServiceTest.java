package com.codearena.service;

import com.codearena.entity.LeaderboardEntry;
import com.codearena.entity.User;
import com.codearena.repository.LeaderboardEntryRepository;
import com.codearena.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class LeaderboardServiceTest {

    @Mock
    private LeaderboardEntryRepository leaderboardEntryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private LeaderboardService leaderboardService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void testGetLeaderboard() {
        // Mock two users
        User user1 = new User();
        user1.setId(10L);
        user1.setName("Alice");

        User user2 = new User();
        user2.setId(20L);
        user2.setName("Bob");

        when(userRepository.findById(10L)).thenReturn(Optional.of(user1));
        when(userRepository.findById(20L)).thenReturn(Optional.of(user2));

        // Mock database leaderboard entries
        LeaderboardEntry entry1 = new LeaderboardEntry();
        entry1.setUserId(10L);
        entry1.setSolvedCount(2L);
        entry1.setAccuracy(100.0);
        entry1.setTotalTimeMs(42L);

        LeaderboardEntry entry2 = new LeaderboardEntry();
        entry2.setUserId(20L);
        entry2.setSolvedCount(1L);
        entry2.setAccuracy(50.0);
        entry2.setTotalTimeMs(84L);

        when(leaderboardEntryRepository.findAllByOrderBySolvedCountDescAccuracyDescTotalTimeMsAsc())
                .thenReturn(List.of(entry1, entry2));

        List<LeaderboardService.LeaderboardEntry> result = leaderboardService.getLeaderboard();

        // Alice has 2 distinct solved, Bob has 1. Alice should be ranked first.
        assertEquals(2, result.size());

        LeaderboardService.LeaderboardEntry le1 = result.get(0);
        assertEquals(10L, le1.userId());
        assertEquals("Alice", le1.name());
        assertEquals(2L, le1.solvedCount());
        assertEquals(1, le1.rank());

        LeaderboardService.LeaderboardEntry le2 = result.get(1);
        assertEquals(20L, le2.userId());
        assertEquals("Bob", le2.name());
        assertEquals(1L, le2.solvedCount());
        assertEquals(2, le2.rank());
    }

    @Test
    public void testGetLeaderboardEmpty() {
        when(leaderboardEntryRepository.findAllByOrderBySolvedCountDescAccuracyDescTotalTimeMsAsc())
                .thenReturn(Collections.emptyList());

        List<LeaderboardService.LeaderboardEntry> result = leaderboardService.getLeaderboard();
        assertTrue(result.isEmpty());
    }
}
