package dev.codearena.judge;

import dev.codearena.common.events.VerdictReady;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.stereotype.Component;

/**
 * Fan-out of verdict events. Locally: one SQS message per subscriber queue.
 * On AWS replace the body with a single EventBridge PutEvents call; subscribers are then EventBridge rules.
 */
@Component
class VerdictPublisher {
    private final SqsTemplate sqs;
    private final JudgeProps props;

    VerdictPublisher(SqsTemplate sqs, JudgeProps props) {
        this.sqs = sqs;
        this.props = props;
    }

    void publish(VerdictReady event) {
        for (String queue : props.verdictQueues()) sqs.send(queue, event);
    }
}