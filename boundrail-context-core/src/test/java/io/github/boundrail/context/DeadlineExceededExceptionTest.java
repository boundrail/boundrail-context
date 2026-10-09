package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.boundrail.context.DeadlineExceededException.Stage;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeadlineExceededExceptionTest {

    /** Every stage with its expected message, written out by hand. */
    private static final Map<Stage, String> MESSAGES = Map.of(
            Stage.INBOUND, "Deadline exceeded at stage INBOUND",
            Stage.BEFORE_SEND, "Deadline exceeded at stage BEFORE_SEND",
            Stage.TIMEOUT, "Deadline exceeded at stage TIMEOUT",
            Stage.EXECUTOR, "Deadline exceeded at stage EXECUTOR",
            Stage.APPLICATION, "Deadline exceeded at stage APPLICATION");

    @Test
    @DisplayName("DL-12 the (Stage) constructor is public and sets the message and the stage")
    void theStageConstructorIsPublicAndSetsTheMessageAndTheStage() throws NoSuchMethodException {
        assertThat(Modifier.isPublic(DeadlineExceededException.class.getConstructor(Stage.class).getModifiers())).isTrue();
        assertThat(MESSAGES).hasSize(Stage.values().length);

        MESSAGES.forEach((stage, message) -> {
            DeadlineExceededException exception = new DeadlineExceededException(stage);

            assertThat(exception).hasMessage(message);
            assertThat(exception.stage()).isEqualTo(stage);
            assertThat(exception.getCause()).isNull();
        });
    }

    @Test
    @DisplayName("DL-12 the (Stage, Throwable) constructor is public and also sets the cause")
    void theStageAndCauseConstructorIsPublicAndAlsoSetsTheCause() throws NoSuchMethodException {
        assertThat(Modifier.isPublic(DeadlineExceededException.class.getConstructor(Stage.class, Throwable.class).getModifiers())).isTrue();

        MESSAGES.forEach((stage, message) -> {
            Throwable cause = new IOException("Request was interrupted");
            DeadlineExceededException exception = new DeadlineExceededException(stage, cause);

            assertThat(exception).hasMessage(message);
            assertThat(exception.stage()).isEqualTo(stage);
            assertThat(exception.getCause()).isSameAs(cause);
        });
    }

    @Test
    @DisplayName("CORE-15 a null stage or cause throws NullPointerException")
    void aNullStageOrCauseThrowsNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> new DeadlineExceededException(null));
        assertThatNullPointerException().isThrownBy(() -> new DeadlineExceededException(null, new IOException("cause")));
        assertThatNullPointerException().isThrownBy(() -> new DeadlineExceededException(Stage.TIMEOUT, null));
    }
}
