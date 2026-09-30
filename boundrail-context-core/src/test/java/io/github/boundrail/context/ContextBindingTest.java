package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextBindingTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("acme.region", String.class, "eu");

    @Test
    @DisplayName("CORE-05 find returns the bound value, else the default, else empty")
    void findReturnsTheBoundValueElseTheDefaultElseEmpty() {
        assertThat(TENANT.find()).isEmpty();
        assertThat(REGION.find()).contains("eu");

        List<Optional<String>> inside = new ArrayList<>();
        Context.with(TENANT, "t1").with(REGION, "us").run(() -> {
            inside.add(TENANT.find());
            inside.add(REGION.find());
        });

        assertThat(inside).containsExactly(Optional.of("t1"), Optional.of("us"));
    }

    @Test
    @DisplayName("CORE-05 get throws MissingContextException naming the key")
    void getThrowsMissingContextExceptionNamingTheKey() {
        MissingContextException missing = catchThrowableOfType(MissingContextException.class, TENANT::get);

        assertThat(missing.key()).isSameAs(TENANT);
        assertThat(missing).hasMessageContaining("acme.tenant");
    }

    @Test
    @DisplayName("CORE-05 get returns the bound value, else the default")
    void getReturnsTheBoundValueElseTheDefault() {
        assertThat(REGION.get()).isEqualTo("eu");

        List<String> inside = new ArrayList<>();
        Context.with(TENANT, "t1").run(() -> inside.add(TENANT.get()));

        assertThat(inside).containsExactly("t1");
    }

    @Test
    @DisplayName("CORE-05 isBound ignores defaults")
    void isBoundIgnoresDefaults() {
        assertThat(REGION.isBound()).isFalse();

        List<Boolean> inside = new ArrayList<>();
        Context.with(REGION, "us").run(() -> inside.add(REGION.isBound()));

        assertThat(inside).containsExactly(true);
    }

    @Test
    @DisplayName("CORE-07 run binds for the scope and restores afterwards")
    void runBindsForTheScopeAndRestoresAfterwards() {
        List<Optional<String>> inside = new ArrayList<>();
        List<Optional<String>> nested = new ArrayList<>();

        Context.with(TENANT, "t1").run(() -> {
            inside.add(TENANT.find());
            Context.with(REGION, "us").run(() -> {
                nested.add(TENANT.find());
                nested.add(REGION.find());
            });
        });

        assertThat(inside).containsExactly(Optional.of("t1"));
        assertThat(nested).containsExactly(Optional.of("t1"), Optional.of("us"));
        assertThat(TENANT.isBound()).isFalse();
    }

    @Test
    @DisplayName("CORE-02 keys with the same name are different keys")
    void keysWithTheSameNameAreDifferentKeys() {
        ContextKey<String> first = ContextKey.of("acme.shared", String.class);
        ContextKey<String> second = ContextKey.of("acme.shared", String.class);
        List<String> inside = new ArrayList<>();

        Context.with(first, "one").with(second, "two").run(() -> {
            inside.add(first.get());
            inside.add(second.get());
        });

        assertThat(inside).containsExactly("one", "two");
    }

    @Test
    @DisplayName("RT-01 each thread has its own current snapshot")
    void eachThreadHasItsOwnCurrentSnapshot() throws InterruptedException {
        AtomicReference<Optional<String>> seenByOtherThread = new AtomicReference<>();
        AtomicReference<Optional<String>> seenAfterOtherThreadBound = new AtomicReference<>();

        Context.with(TENANT, "main").run(() -> {
            Thread other = new Thread(() -> {
                seenByOtherThread.set(TENANT.find());
                Context.with(TENANT, "other").run(() -> { });
            });
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            seenAfterOtherThreadBound.set(TENANT.find());
        });

        assertThat(seenByOtherThread.get()).isEmpty();
        assertThat(seenAfterOtherThreadBound.get()).contains("main");
    }

    @Test
    @DisplayName("CORE-03 with rejects a null key with NullPointerException at the with call")
    void withRejectsANullKeyAtTheWithCall() {
        ContextBinding binding = Context.with(TENANT, "t1");

        assertThatNullPointerException().isThrownBy(() -> Context.with((ContextKey<String>) null, "v"));
        assertThatNullPointerException().isThrownBy(() -> binding.with((ContextKey<String>) null, "v"));
    }

    @Test
    @DisplayName("CORE-03 run rejects a null operation with NullPointerException and leaves the current snapshot unchanged")
    void runRejectsANullOperationAndLeavesTheCurrentSnapshotUnchanged() {
        assertThatNullPointerException().isThrownBy(() -> Context.with(TENANT, "t1").run(null));

        assertThat(TENANT.isBound()).isFalse();
    }
}
