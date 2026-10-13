package com.codearena.service;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class JudgeService {

    private static final long TIME_LIMIT_SECONDS = 5;
    private static final boolean DOCKER_AVAILABLE;

    static {
        boolean available = false;
        try {
            Process p = new ProcessBuilder("docker", "ps").start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            available = finished && p.exitValue() == 0;
        } catch (Exception e) {
            available = false;
        }
        DOCKER_AVAILABLE = available;
        if (!DOCKER_AVAILABLE) {
            System.err.println("WARNING: Docker is not running or not installed. Falling back to local execution.");
        }
    }

    public static class TestCaseVerdict {
        public String status;       // ACCEPTED, WRONG_ANSWER, TLE, MLE, RUNTIME_ERROR, COMPILATION_ERROR
        public String actualOutput;
        public String errorMessage;
        public long executionTimeMs;
        public long memoryUsedBytes;

        public TestCaseVerdict(String status, String actualOutput, String errorMessage, long executionTimeMs, long memoryUsedBytes) {
            this.status = status;
            this.actualOutput = actualOutput;
            this.errorMessage = errorMessage;
            this.executionTimeMs = executionTimeMs;
            this.memoryUsedBytes = memoryUsedBytes;
        }
    }

    public static class JudgeVerdict {
        public String status;
        public String actualOutput;
        public long executionTimeMs;

        public JudgeVerdict(String status, String actualOutput, long executionTimeMs) {
            this.status = status;
            this.actualOutput = actualOutput;
            this.executionTimeMs = executionTimeMs;
        }
    }

    public record TestCaseInput(String input, String expectedOutput, Integer timeLimitOverrideSeconds) {
        public TestCaseInput(String input, String expectedOutput) {
            this(input, expectedOutput, null);
        }
    }

    /**
     * Compatibility method: executes code against a single testcase.
     */
    public JudgeVerdict run(String code, String language, String input, String expectedOutput) {
        List<TestCaseVerdict> verdicts = evaluate(code, language, List.of(new TestCaseInput(input, expectedOutput)));
        if (verdicts.isEmpty()) {
            return new JudgeVerdict("RUNTIME_ERROR", "Internal judge error", 0);
        }
        TestCaseVerdict v = verdicts.get(0);
        String status = v.status;
        if ("COMPILATION_ERROR".equals(status)) {
            return new JudgeVerdict("COMPILATION_ERROR", v.errorMessage, v.executionTimeMs);
        }
        String outputVal = v.actualOutput;
        if (outputVal == null || (outputVal.isEmpty() && v.errorMessage != null && !v.errorMessage.isEmpty())) {
            outputVal = v.errorMessage;
        }
        return new JudgeVerdict(status, outputVal, v.executionTimeMs);
    }

    /**
     * Compatibility method: executes code against all test cases.
     */
    public JudgeVerdict runAll(String code, String language, List<TestCaseInput> testCases) {
        List<TestCaseVerdict> verdicts = evaluate(code, language, testCases);
        long totalTime = 0;
        
        if (!verdicts.isEmpty() && "COMPILATION_ERROR".equals(verdicts.get(0).status)) {
            return new JudgeVerdict("COMPILATION_ERROR", verdicts.get(0).errorMessage, verdicts.get(0).executionTimeMs);
        }

        for (TestCaseVerdict v : verdicts) {
            totalTime += v.executionTimeMs;
            if (!"ACCEPTED".equals(v.status)) {
                return new JudgeVerdict(v.status, v.errorMessage != null ? v.errorMessage : v.actualOutput, totalTime);
            }
        }
        return new JudgeVerdict("ACCEPTED", "All test cases passed", totalTime);
    }

    /**
     * Core evaluation method: compiles code once and runs it against all testcases.
     */
    public List<TestCaseVerdict> evaluate(String code, String language, List<TestCaseInput> testCases) {
        String lang = language.toUpperCase();
        if (!List.of("PYTHON3", "PYTHON", "JAVASCRIPT", "JAVA", "C", "CPP", "C++").contains(lang)) {
            return List.of(new TestCaseVerdict("RUNTIME_ERROR", "", "Unsupported language: " + language, 0, 0));
        }

        // Make sure the image this language needs is actually present *before* any
        // timed compile/run step starts. Without this, a submission's very first
        // run in a language whose image isn't cached yet races a multi-hundred-MB
        // download against a 5-10 second time limit and always loses — which reads
        // exactly like "correct code doesn't pass," inconsistently, depending on
        // what happens to be cached. Pulling has its own generous timeout instead.
        if (DOCKER_AVAILABLE) {
            String requiredImage = switch (lang) {
                case "JAVA" -> "eclipse-temurin:17-jdk";
                case "C", "CPP", "C++" -> "gcc:latest";
                case "JAVASCRIPT" -> "node:latest";
                default -> "python:3.11";
            };
            String pullError = ensureImagePulled(requiredImage);
            if (pullError != null) {
                return List.of(new TestCaseVerdict("RUNTIME_ERROR", "", pullError, 0, 0));
            }
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("codearena_judge_");
            String fileName;
            switch (lang) {
                case "JAVA" -> fileName = "Solution.java";
                case "JAVASCRIPT" -> fileName = "solution.js";
                case "C" -> fileName = "solution.c";
                case "CPP", "C++" -> fileName = "solution.cpp";
                default -> fileName = "solution.py";
            }
            Path sourceFile = tempDir.resolve(fileName);
            Files.writeString(sourceFile, code);

            // 1. Compilation Phase
            String executableName = null;
            if (lang.equals("C") || lang.equals("CPP") || lang.equals("C++") || lang.equals("JAVA")) {
                long compileStart = System.currentTimeMillis();
                List<String> compileCmd = new ArrayList<>();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                
                if (DOCKER_AVAILABLE) {
                    compileCmd.addAll(List.of("docker", "run", "--rm", 
                            "-v", tempDir.toAbsolutePath().toString() + ":/app", 
                            "-w", "/app"));
                    if (lang.equals("JAVA")) {
                        compileCmd.addAll(List.of("eclipse-temurin:17-jdk", "javac", "Solution.java"));
                    } else { // C/C++
                        String compiler = lang.equals("C") ? "gcc" : "g++";
                        compileCmd.addAll(List.of("gcc:latest", compiler, fileName, "-o", "solution"));
                        executableName = "./solution";
                    }
                } else {
                    // Local fallback compilation
                    if (lang.equals("C")) {
                        executableName = tempDir.resolve(isWindows ? "solution.exe" : "solution").toString();
                        compileCmd.addAll(List.of("gcc", fileName, "-o", executableName));
                    } else if (lang.equals("CPP") || lang.equals("C++")) {
                        executableName = tempDir.resolve(isWindows ? "solution.exe" : "solution").toString();
                        compileCmd.addAll(List.of("g++", fileName, "-o", executableName));
                    } else { // JAVA
                        compileCmd.addAll(List.of("javac", fileName));
                    }
                }

                ProcessBuilder compilePb = new ProcessBuilder(compileCmd);
                if (!DOCKER_AVAILABLE) {
                    compilePb.directory(tempDir.toFile());
                }
                
                Process compileProcess;
                try {
                    compileProcess = compilePb.start();
                } catch (IOException e) {
                    return List.of(new TestCaseVerdict("RUNTIME_ERROR", "", 
                            "Compiler execution failed. Details: " + e.getMessage(), 
                            System.currentTimeMillis() - compileStart, 0));
                }

                StreamGobbler compileStdout = new StreamGobbler(compileProcess.getInputStream());
                StreamGobbler compileStderr = new StreamGobbler(compileProcess.getErrorStream());
                Thread stdoutThread = new Thread(compileStdout);
                Thread stderrThread = new Thread(compileStderr);
                stdoutThread.start();
                stderrThread.start();

                boolean compileFinished = compileProcess.waitFor(10, TimeUnit.SECONDS);
                long compileTime = System.currentTimeMillis() - compileStart;

                if (!compileFinished) {
                    compileProcess.destroyForcibly();
                    return List.of(new TestCaseVerdict("COMPILATION_ERROR", "", "Compilation timed out", compileTime, 0));
                }

                stdoutThread.join(5000);
                stderrThread.join(5000);

                if (compileProcess.exitValue() != 0) {
                    String err = compileStderr.getResult().isBlank() ? compileStdout.getResult() : compileStderr.getResult();
                    return List.of(new TestCaseVerdict("COMPILATION_ERROR", "", err, compileTime, 0));
                }
            }

            // 2. Execution Phase
            List<TestCaseVerdict> results = new ArrayList<>();
            for (TestCaseInput tc : testCases) {
                String containerName = "codearena_run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                List<String> runCmd = new ArrayList<>();

                if (DOCKER_AVAILABLE) {
                    runCmd.addAll(List.of("docker", "run", "--rm", "-i",
                            "--name", containerName,
                            "--memory=512m",
                            "--memory-swap=512m",
                            "--network", "none",
                            "-v", tempDir.toAbsolutePath().toString() + ":/app",
                            "-w", "/app"));
                    
                    if (lang.equals("JAVA")) {
                        runCmd.addAll(List.of("eclipse-temurin:17-jdk", "java", "Solution"));
                    } else if (lang.equals("CPP") || lang.equals("C++") || lang.equals("C")) {
                        runCmd.addAll(List.of("gcc:latest", "./solution"));
                    } else if (lang.equals("JAVASCRIPT")) {
                        runCmd.addAll(List.of("node:latest", "node", "solution.js"));
                    } else { // PYTHON3
                        runCmd.addAll(List.of("python:3.11", "python", "-u", "solution.py"));
                    }
                } else {
                    // Local fallback run
                    if (lang.equals("JAVA")) {
                        runCmd.addAll(List.of("java", "Solution"));
                    } else if (lang.equals("CPP") || lang.equals("C++") || lang.equals("C")) {
                        runCmd.add(executableName);
                    } else if (lang.equals("JAVASCRIPT")) {
                        runCmd.addAll(List.of("node", "solution.js"));
                    } else { // PYTHON3
                        String pythonCmd = getPythonCommand();
                        runCmd.addAll(List.of(pythonCmd, "solution.py"));
                    }
                }

                long runStart = System.currentTimeMillis();
                ProcessBuilder runPb = new ProcessBuilder(runCmd);
                if (!DOCKER_AVAILABLE) {
                    runPb.directory(tempDir.toFile());
                }

                Process process = runPb.start();

                // Feed input to process
                if (tc.input != null && !tc.input.isEmpty()) {
                    try (OutputStream os = process.getOutputStream()) {
                        os.write(tc.input.getBytes());
                        os.flush();
                    }
                } else {
                    process.getOutputStream().close();
                }

                StreamGobbler stdoutGobbler = new StreamGobbler(process.getInputStream());
                StreamGobbler stderrGobbler = new StreamGobbler(process.getErrorStream());
                Thread stdoutThread = new Thread(stdoutGobbler);
                Thread stderrThread = new Thread(stderrGobbler);
                stdoutThread.start();
                stderrThread.start();

                long effectiveTimeLimit = (tc.timeLimitOverrideSeconds() != null && tc.timeLimitOverrideSeconds() > 0)
                        ? tc.timeLimitOverrideSeconds()
                        : TIME_LIMIT_SECONDS;
                boolean finished = process.waitFor(effectiveTimeLimit, TimeUnit.SECONDS);
                long elapsed = System.currentTimeMillis() - runStart;

                if (!finished) {
                    process.destroyForcibly();
                    if (DOCKER_AVAILABLE) {
                        // Kill container forcibly
                        try {
                            new ProcessBuilder("docker", "kill", containerName).start().waitFor(2, TimeUnit.SECONDS);
                        } catch (Exception ignored) {}
                    }
                    results.add(new TestCaseVerdict("TLE", "", "Time Limit Exceeded", elapsed, 0));
                    continue;
                }

                stdoutThread.join(5000);
                stderrThread.join(5000);

                int exitValue = process.exitValue();
                String stdout = stdoutGobbler.getResult();
                String stderr = stderrGobbler.getResult();

                // Detect Memory Limit Exceeded
                // Docker container exit code 137 means it was OOM-killed (SIGKILL 9)
                if (DOCKER_AVAILABLE && exitValue == 137) {
                    results.add(new TestCaseVerdict("MLE", "", "Memory Limit Exceeded", elapsed, 512 * 1024 * 1024L));
                    continue;
                }

                // Detect local or python OOM
                if (stderr.contains("OutOfMemoryError") || stderr.contains("MemoryError")) {
                    results.add(new TestCaseVerdict("MLE", "", "Memory Limit Exceeded: " + stderr.trim(), elapsed, 512 * 1024 * 1024L));
                    continue;
                }

                if (exitValue != 0) {
                    results.add(new TestCaseVerdict("RUNTIME_ERROR", stdout, stderr.isBlank() ? "Exit code " + exitValue : stderr.trim(), elapsed, 0));
                    continue;
                }

                String normalizedActual = normalizeOutput(stdout);
                String normalizedExpected = normalizeOutput(tc.expectedOutput);

                String status = normalizedActual.equals(normalizedExpected) ? "ACCEPTED" : "WRONG_ANSWER";
                long estimatedMemory = 15 * 1024 * 1024L; // Mock standard memory usage (approx 15MB)
                results.add(new TestCaseVerdict(status, stdout, null, elapsed, estimatedMemory));
            }

            return results;

        } catch (Exception e) {
            return List.of(new TestCaseVerdict("RUNTIME_ERROR", "", "Judge error: " + e.getMessage(), 0, 0));
        } finally {
            cleanup(tempDir);
        }
    }

    private static String normalizeOutput(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] lines = s.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String normalizedLine = trimmed.replaceAll("[\\s\\t]+", " ");
            sb.append(normalizedLine).append("\n");
        }
        return sb.toString().trim();
    }

    private static final Set<String> KNOWN_PRESENT_IMAGES = ConcurrentHashMap.newKeySet();

    /**
     * Returns null if the image is present (or was just pulled successfully),
     * or an error message if the pull failed/timed out. Checked once per image
     * per JVM lifetime via KNOWN_PRESENT_IMAGES — after the first successful
     * check, subsequent submissions skip straight to execution.
     */
    private String ensureImagePulled(String image) {
        if (KNOWN_PRESENT_IMAGES.contains(image)) {
            return null;
        }
        try {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", image).start();
            boolean finished = inspect.waitFor(5, TimeUnit.SECONDS);
            if (finished && inspect.exitValue() == 0) {
                KNOWN_PRESENT_IMAGES.add(image);
                return null;
            }
        } catch (Exception ignored) {
            // fall through to attempting a pull
        }

        try {
            Process pull = new ProcessBuilder("docker", "pull", image).start();
            // A cold pull of a several-hundred-MB image can genuinely take minutes
            // on a slow connection — this is a one-time cost per image, not a
            // per-submission one, so it gets a real timeout instead of 5-10 seconds.
            boolean finished = pull.waitFor(5, TimeUnit.MINUTES);
            if (finished && pull.exitValue() == 0) {
                KNOWN_PRESENT_IMAGES.add(image);
                return null;
            }
            if (!finished) {
                pull.destroyForcibly();
                return "Judge is still downloading the " + image + " execution environment " +
                        "(first use only) — this can take a few minutes. Please try submitting again shortly.";
            }
            return "Failed to pull required execution image: " + image;
        } catch (Exception e) {
            return "Could not prepare execution environment for " + image + ": " + e.getMessage();
        }
    }

    private static class StreamGobbler implements Runnable {
        private final InputStream is;
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        public StreamGobbler(InputStream is) {
            this.is = is;
        }

        @Override
        public void run() {
            try (is) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
            } catch (IOException ignored) {}
        }

        public String getResult() {
            return bos.toString();
        }
    }

    private void cleanup(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    private String detectedPythonCmd = null;

    private synchronized String getPythonCommand() {
        if (detectedPythonCmd != null) {
            return detectedPythonCmd;
        }

        String[] candidates;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            candidates = new String[]{"py", "python", "python3"};
        } else {
            candidates = new String[]{"python3", "python", "py"};
        }

        for (String cmd : candidates) {
            if (isCommandAvailable(cmd)) {
                detectedPythonCmd = cmd;
                return cmd;
            }
        }

        detectedPythonCmd = System.getProperty("os.name").toLowerCase().contains("win") ? "python" : "python3";
        return detectedPythonCmd;
    }

    private boolean isCommandAvailable(String cmd) {
        try {
            Process process = new ProcessBuilder(cmd, "--version").start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
