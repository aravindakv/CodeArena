package dev.codearena.submission;

import dev.codearena.common.web.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class SubmissionApplication {
    public static void main(String[] args) {
        SpringApplication.run(SubmissionApplication.class, args);
    }
}