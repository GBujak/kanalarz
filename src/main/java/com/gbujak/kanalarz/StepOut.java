package com.gbujak.kanalarz;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Objects;
import java.util.Optional;

public class StepOut<T> {

    @Nullable final private T value;
    @Nullable final private Throwable error;

    private StepOut(@Nullable T value, @Nullable Throwable error) {
        if (value == null && error == null) {
            throw new IllegalStateException(
                "Tried to create a StepOut with a null value and error! That should never happen!"
            );
        }
        this.value = value;
        this.error = error;
    }

    @NonNull
    public static <T> StepOut<T> of(@NonNull T value) {
        Objects.requireNonNull(
            value,
            "Can't use null value for StepOut. If you need an empty result, use StepOut<Optional<T>>"
        );
        return new StepOut<>(value, null);
    }

    @NonNull
    public static <T> StepOut<Optional<T>> ofNullable(@Nullable T value) {
        return StepOut.of(Optional.ofNullable(value));
    }

    @NonNull
    public static <T> StepOut<T> err(@NonNull Throwable error) {
        Objects.requireNonNull(
            error,
            "Can't use null error for StepOut. If you need an empty result, use StepOut<Optional<T>>"
        );
        return new StepOut<>(null, error);
    }

    @NonNull
    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    @Nullable
    public T valueOrElse(@Nullable T other) {
        return value == null ? other : value;
    }

    @Nullable
    public T valueOrNull() {
        return value;
    }

    @NonNull
    public T valueOrThrow() {
        if (error != null) {
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            } else {
                throw new RuntimeException(error);
            }
        }
        if (value == null) {
            throw new IllegalStateException("Illegal StepOut state! The error and value are both null!");
        }
        return value;
    }

    public boolean isOk() {
        return this.value != null;
    }

    public boolean isErr() {
        return this.value == null;
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
