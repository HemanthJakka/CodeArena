package com.codearena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CodearenaApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodearenaApplication.class, args);
    }
}
