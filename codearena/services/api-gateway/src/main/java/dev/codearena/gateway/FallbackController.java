package dev.codearena.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR;

/**
 * Answers when a circuit breaker is open or a downstream call fails or times out.
 * The client only sees a generic 503; the real cause is logged here so you can find it.
 */
@RestController
class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/problems")
    ResponseEntity<String> problems(ServerWebExchange exchange) {
        logCause(exchange);
        return busy("PROBLEMS_UNAVAILABLE", "problem service is temporarily unavailable");
    }

    @RequestMapping("/fallback/busy")
    ResponseEntity<String> busy(ServerWebExchange exchange) {
        logCause(exchange);
        return busy("TRY_AGAIN", "the service is busy, please retry in a few seconds");
    }

    private static void logCause(ServerWebExchange exchange) {
        Throwable cause = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        Object original = exchange.getAttribute(GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        log.warn("fallback for {} {}: {}", exchange.getRequest().getMethod(), original,
                cause == null ? "circuit open or unknown" : cause.toString());
    }

    private static ResponseEntity<String> busy(String code, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":503,\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}