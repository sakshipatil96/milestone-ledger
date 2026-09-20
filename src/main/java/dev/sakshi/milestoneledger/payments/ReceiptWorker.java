package dev.sakshi.milestoneledger.payments;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ReceiptWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReceiptWorker.class);
    private final ReceiptProcessor processor;
    private final ReceiptFailureRecorder failures;
    public ReceiptWorker(ReceiptProcessor processor, ReceiptFailureRecorder failures) { this.processor = processor; this.failures = failures; }
    @Scheduled(fixedDelayString = "${app.receipt-worker.fixed-delay-ms:250}", initialDelayString = "${app.receipt-worker.initial-delay-ms:100}")
    public void poll() {
        try { processor.processOneDueEvent(); }
        catch (ReceiptProcessor.ProcessingFailure failure) {
            try {
                failures.record(failure.eventId(), failure.getCause() instanceof ReceiptProcessor.InvalidPersistedEvent);
            } catch (RuntimeException recorderFailure) {
                // The processing transaction already rolled back. A pending row stays recoverable.
                LOG.error("Receipt failure metadata unavailable inboxEventId={} errorCode=FAILURE_RECORDING_FAILED",
                        failure.eventId());
            }
        }
    }
}
