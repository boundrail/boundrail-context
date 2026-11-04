package io.github.boundrail.context;

/** Classifies outbound calls for every outbound adapter (DEST-01). */
public interface DestinationClassifier {

    /**
     * {@return the destination of {@code request}}
     *
     * @param request the outbound call
     */
    Destination classify(OutboundRequest request);
}
