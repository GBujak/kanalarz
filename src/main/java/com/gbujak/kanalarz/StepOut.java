package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.Optional;

public class StepOut<T> {

    @Nullable final private T value;
    @Nullable final private Throwable error;

    private StepOut(@Nullable T value, @Nullable Throwable error) {
        this.value = value;
        this.error = error;
    }

    @NonNull
    public static <T> StepOut<T> newOk(@NonNull T value) {
        return new StepOut<>(value, null);
    }

    @NonNull
    public static <T> StepOut<T> newErr(@NonNull Throwable error) {
        return new StepOut<>(null, error);
    }

    @NonNull
    public boolean ok() {
        return this.value != null;
    }

    @Nullable
    public T value() {
        return this.value;
    }

    @NonNull
    public T getOrThrow() {
        return getOrThrow("");
    }

    @NonNull
    public T getOrThrow(String errorMessage) {
        if (this.error != null) {
            throw new RuntimeException("Asserted step value but the step failed: " + errorMessage, this.error);
        }
        if (this.value == null) {
            throw new RuntimeException(
                "Asserted step value but the value was null without any errors: " + errorMessage
            );
        }
        return this.value;
    }

    @Nullable
    public T getOrThrowError() {
        throwError();
        return this.value;
    }

    @NonNull
    public boolean isError() {
        return this.error != null;
    }

    @NonNull
    public boolean isOkNull() {
        return this.value == null && this.error == null;
    }

    @Nullable
    public Throwable error() {
        return this.error;
    }

    @NonNull
    public Optional<T> optional() {
        return Optional.ofNullable(this.value);
    }

    @NonNull
    public Optional<T> optionalThrowError() {
        throwError();
        return this.optional();
    }

    private void throwError() {
        if (this.error != null) {
            throw new RuntimeException("Asserted the step didn't fail but it failed", this.error);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        StepOut<?> stepOut = (StepOut<?>) o;
        return Objects.equals(value, stepOut.value) && Objects.equals(error, stepOut.error);
    }

    @Override
    public int hashCode() {
        int result = Objects.hashCode(value);
        result = 31 * result + Objects.hashCode(error);
        return result;
    }
}
