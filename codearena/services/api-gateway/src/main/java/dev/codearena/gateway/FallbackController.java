package dev.codearena.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Answers when a circuit breaker is open or a downstream call times out. */
@RestController
class FallbackController {

    @RequestMapping("/fallback/problems")
    ResponseEntity<String> problems() {
        return busy("PROBLEMS_UNAVAILABLE", "problem service is temporarily unavailable");
    }

    @RequestMapping("/fallback/busy")
    ResponseEntity<String> busy() {
        return busy("TRY_AGAIN", "the service is busy, please retry in a few seconds");
    }

    private static ResponseEntity<String> busy(String code, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":503,\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}