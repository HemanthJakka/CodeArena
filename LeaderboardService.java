package com.codearena.service;

import com.codearena.entity.Submission;
import com.codearena.entity.SubmissionStatus;
import com.codearena.entity.User;
import com.codearena.repository.LeaderboardEntryRepository;
import com.codearena.repository.SubmissionRepository;
import com.codearena.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;

    public record LeaderboardEntry(Long userId, String name, long solvedCount, int rank) {}

    /**
     * Score = number of DISTINCT problems solved (status = ACCEPTED) per user.
     * Fetches from cached database entries pre-sorted by solved count, accuracy and speed.
     */
    public List<LeaderboardEntry> getLeaderboard() {
        List<com.codearena.entity.LeaderboardEntry> dbEntries = leaderboardEntryRepository.findAllByOrderBySolvedCountDescAccuracyDescTotalTimeMsAsc();
        List<LeaderboardEntry> result = new ArrayList<>();
        int rank = 1;
        for (com.codearena.entity.LeaderboardEntry entry : dbEntries) {
            User user = userRepository.findById(entry.getUserId()).orElse(null);
            String name = user != null ? user.getName() : "Unknown";
            result.add(new LeaderboardEntry(entry.getUserId(), name, entry.getSolvedCount(), rank++));
        }
        return result;
    }

    /**
     * Ranks users by distinct problems solved (ACCEPTED) within the last 7 days.
     */
    public List<LeaderboardEntry> getWeeklyLeaderboard() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<Submission> recentAccepted = submissionRepository.findByStatusAndSubmittedAtAfter(SubmissionStatus.ACCEPTED, weekAgo);
        return rankBySolvedCount(recentAccepted);
    }

    /**
     * Ranks users by distinct problems solved (ACCEPTED) using a specific language.
     */
    public List<LeaderboardEntry> getLeaderboardByLanguage(String language) {
        List<Submission> accepted = submissionRepository.findByStatusAndLanguage(SubmissionStatus.ACCEPTED, language.toUpperCase());
        return rankBySolvedCount(accepted);
    }

    private List<LeaderboardEntry> rankBySolvedCount(List<Submission> acceptedSubmissions) {
        Map<Long, Long> solvedCountByUser = acceptedSubmissions.stream()
                .collect(Collectors.groupingBy(s -> s.getUserId(),
                        Collectors.mapping(s -> s.getProblemId(), Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));

        List<LeaderboardEntry> result = new ArrayList<>();
        int rank = 1;
        List<Map.Entry<Long, Long>> sorted = solvedCountByUser.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<Long, Long> e) -> e.getValue()).reversed())
                .toList();

        for (Map.Entry<Long, Long> entry : sorted) {
            User user = userRepository.findById(entry.getKey()).orElse(null);
            String name = user != null ? user.getName() : "Unknown";
            result.add(new LeaderboardEntry(entry.getKey(), name, entry.getValue(), rank++));
        }
        return result;
    }
}
