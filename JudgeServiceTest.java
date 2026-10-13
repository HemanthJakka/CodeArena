package com.codearena.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class JudgeServiceTest {

    private final JudgeService judgeService = new JudgeService();

    private boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "ps").start();
            return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLanguageAvailable(String lang) {
        if (isDockerAvailable()) return true;
        try {
            if ("PYTHON3".equals(lang)) {
                for (String cmd : List.of("python3", "python", "py")) {
                    try {
                        Process p = new ProcessBuilder(cmd, "--version").start();
                        if (p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
                    } catch (Exception ignored) {}
                }
            } else if ("JAVA".equals(lang)) {
                Process p = new ProcessBuilder("javac", "-version").start();
                if (p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
            } else if ("CPP".equals(lang)) {
                Process p = new ProcessBuilder("g++", "--version").start();
                if (p.waitFor(1, TimeUnit.SECONDS) && p.exitValue() == 0) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Test
    public void testAcceptedPythonCode() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        String code = "import sys\nprint(sys.stdin.readline().strip())";
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "hello\n", "hello");
        assertEquals("ACCEPTED", verdict.status);
        assertNotNull(verdict.actualOutput);
        assertTrue(verdict.executionTimeMs >= 0);
    }

    @Test
    public void testWrongAnswerPythonCode() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        String code = "print('wrong')";
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "hello", "expected");
        assertEquals("WRONG_ANSWER", verdict.status);
    }

    @Test
    public void testRuntimeErrorPythonCode() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        String code = "raise ValueError('test error')";
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "hello", "expected");
        assertEquals("RUNTIME_ERROR", verdict.status);
        assertTrue(verdict.actualOutput.contains("ValueError") || verdict.actualOutput.contains("test error"));
    }

    @Test
    public void testTimeLimitExceeded() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        // Sleep for 6 seconds, which is above the 5 seconds limit
        String code = "import time\ntime.sleep(6)";
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "hello", "expected");
        assertEquals("TLE", verdict.status);
    }

    @Test
    public void testUnsupportedLanguage() {
        JudgeService.JudgeVerdict verdict = judgeService.run("print('hello')", "GO", "hello", "expected");
        assertEquals("RUNTIME_ERROR", verdict.status);
        assertTrue(verdict.actualOutput.contains("Unsupported language"));
    }

    @Test
    public void testRunAllAccepted() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        String code = "import sys\nprint(sys.stdin.readline().strip())";
        List<JudgeService.TestCaseInput> tcs = List.of(
            new JudgeService.TestCaseInput("a", "a"),
            new JudgeService.TestCaseInput("b", "b")
        );
        JudgeService.JudgeVerdict verdict = judgeService.runAll(code, "PYTHON3", tcs);
        assertEquals("ACCEPTED", verdict.status);
    }

    @Test
    public void testRunAllFailing() {
        Assumptions.assumeTrue(isLanguageAvailable("PYTHON3"), "Python is not available. Skipping test.");
        String code = "import sys\nprint('fixed_output')";
        List<JudgeService.TestCaseInput> tcs = List.of(
            new JudgeService.TestCaseInput("fixed_output", "fixed_output"),
            new JudgeService.TestCaseInput("different", "different")
        );
        JudgeService.JudgeVerdict verdict = judgeService.runAll(code, "PYTHON3", tcs);
        assertEquals("WRONG_ANSWER", verdict.status);
    }

    // ==========================================
    // SANDBOXED DOCKER ENGINE TESTS
    // ==========================================

    @Test
    public void testJavaCompilationAndRun() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker is not available. Skipping sandbox test.");
        String code = """
            public class Solution {
                public static void main(String[] args) {
                    java.util.Scanner sc = new java.util.Scanner(System.in);
                    if (sc.hasNext()) {
                        System.out.println("Hello " + sc.next());
                    } else {
                        System.out.println("Hello Java");
                    }
                }
            }
            """;
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "JAVA", "World", "Hello World");
        assertEquals("ACCEPTED", verdict.status);
    }

    @Test
    public void testCppCompilationAndRun() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker is not available. Skipping sandbox test.");
        String code = """
            #include <iostream>
            #include <string>
            int main() {
                std::string s;
                if (std::cin >> s) {
                    std::cout << "Hello " << s << std::endl;
                } else {
                    std::cout << "Hello C++" << std::endl;
                }
                return 0;
            }
            """;
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "CPP", "CppWorld", "Hello CppWorld");
        assertEquals("ACCEPTED", verdict.status);
    }

    @Test
    public void testJavaCompilationError() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker is not available. Skipping sandbox test.");
        String code = """
            public class Solution {
                public static void main(String[] args) {
                    // Missing semicolon
                    System.out.println("Oops")
                }
            }
            """;
        List<JudgeService.TestCaseVerdict> verdicts = judgeService.evaluate(code, "JAVA", List.of(new JudgeService.TestCaseInput("in", "out")));
        assertFalse(verdicts.isEmpty());
        assertEquals("COMPILATION_ERROR", verdicts.get(0).status);
        assertNotNull(verdicts.get(0).errorMessage);
    }

    @Test
    public void testDockerOomMemoryLimit() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker is not available. Skipping sandbox test.");
        // Python code that tries to allocate more than 512 MB memory
        // 600 million bytes is ~600MB which exceeds the 512MB container memory cap
        String code = "x = b'0' * (600 * 1024 * 1024)";
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "", "");
        assertEquals("MLE", verdict.status);
    }

    @Test
    public void testDockerIsolationHolds() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker is not available. Skipping sandbox test.");
        // Try writing a file to a folder outside the container's mounted /app directory
        String code = """
            with open('/leak_test.txt', 'w') as f:
                f.write('leak data')
            print('success')
            """;
        JudgeService.JudgeVerdict verdict = judgeService.run(code, "PYTHON3", "", "success");
        assertEquals("ACCEPTED", verdict.status);
        
        // Assert that the file did NOT leak onto the host filesystem
        File hostFile = new File("/leak_test.txt");
        assertFalse(hostFile.exists(), "Filesystem isolation failed! File written inside container leaked to host.");
    }
}
