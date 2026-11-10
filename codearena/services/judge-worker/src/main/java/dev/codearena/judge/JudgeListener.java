package dev.codearena.judge;

import dev.codearena.common.events.Queues;
import dev.codearena.common.events.SubmissionCreated;
import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * Returning normally deletes the message. Throwing leaves it on the queue; SQS redelivers it after the
 * visibility timeout. On the third attempt we record SYSTEM_ERROR and stop, so a poison submission can
 * neither block the queue nor keep crashing workers. (elasticmq.conf also moves it to a DLQ after 3 receives.)
 */
@Component
class JudgeListener {
    private static final int MAX_ATTEMPTS = 3;
    private final JudgeService judge;

    JudgeListener(JudgeService judge) {
        this.judge = judge;
    }

    @SqsListener(Queues.CONTEST_SUBMISSIONS)
    void contest(Message<SubmissionCreated> message) throws Exception {
        handle(message);
    }

    @SqsListener(Queues.PRACTICE_SUBMISSIONS)
    void practice(Message<SubmissionCreated> message) throws Exception {
        handle(message);
    }

    private void handle(Message<SubmissionCreated> message) throws Exception {
        try {
            judge.judge(message.getPayload());
        } catch (Exception e) {
            if (attempt(message) >= MAX_ATTEMPTS) judge.giveUp(message.getPayload(), e);
            else throw e;
        }
    }

    private static int attempt(Message<?> message) {
        Object v = message.getHeaders().get(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT);
        try {
            return v == null ? 1 : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}