package io.github.boundrail.context.test;

import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The extension of {@link BoundrailTest} (TEST-02): capture sites {@code on} for the class, with the previous mode and sample rate restored
 * after it, and a fresh violation recording for each test method.
 */
final class BoundrailTestExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(BoundrailTestExtension.class);
    private static final String MODE = "captureSites";
    private static final String RATE = "sampleRate";

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        double rate = ContextRuntime.sampleRate();
        store.put(MODE, ContextRuntime.captureSites());
        store.put(RATE, rate);
        ContextRuntime.captureSites(CaptureSites.ON, rate);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store store = context.getStore(NAMESPACE);
        ContextRuntime.captureSites(store.get(MODE, CaptureSites.class), store.get(RATE, Double.class));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        ViolationRecorder.start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ViolationRecorder.stop();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == RecordedViolations.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return (RecordedViolations) ViolationRecorder::all;
    }
}
