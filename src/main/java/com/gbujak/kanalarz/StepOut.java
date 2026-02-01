package com.gbujak.kanalarz;


import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

@NullMarked
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

    public static <T> StepOut<T> of(T value) {
        return StepOut.ofNonNullOrThrow(value);
    }

    public static <T> StepOut<T> ofNonNullOrThrow(@Nullable T value) {
        Objects.requireNonNull(value);
        return (StepOut<T>) new StepOut<>(value, null);
    }

    public static <T> StepOut<Optional<T>> ofNullable(@Nullable T value) {
        return StepOut.of(Optional.ofNullable(value));
    }

    public static <T> StepOut<Optional<T>> empty() {
        return StepOut.of(Optional.empty());
    }

    public static <T> StepOut<T> err(Throwable error) {
        Objects.requireNonNull(
            error,
            "Can't use null error for StepOut. If you need an empty result, use StepOut<Optional<T>>"
        );
        return new StepOut<>(null, error);
    }

    public Optional<T> value() {
        return (Optional<T>) Optional.ofNullable(value);
    }

    @Nullable
    public T valueOrElse(@Nullable T other) {
        return value == null ? other : value;
    }

    @Nullable
    public T valueOrNull() {
        return value;
    }

    public Optional<Throwable> error() {
        return (Optional<Throwable>) Optional.ofNullable(error);
    }

    @Nullable
    public Throwable errorOrNull() {
        return error;
    }

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

    public static boolean isTypeStepOut(Type type) {
        if (type instanceof ParameterizedType pt) {
            return pt.getRawType().equals(StepOut.class);
        } else if (type instanceof Class<?> clazz) {
            return clazz.equals(StepOut.class);
        }
        return false;
    }

    /**
     * Get type wrapped in StepOut or the parameter back if the parameter is not a StepOut
     * @throws IllegalArgumentException if stepOutType is a Class reference and type parameters were erased
     */
    public static Type unwrapStepOutType(Type stepOutType) {
        if (!StepOut.isTypeStepOut(stepOutType)) {
            return stepOutType;
        }
        if (stepOutType instanceof ParameterizedType pt) {
            var arguments = pt.getActualTypeArguments();
            if (arguments.length != 1) {
                throw new RuntimeException(
                    "Given type [%s] has zero or more than one type parameters, can't determine type parameter"
                        .formatted(pt.getTypeName())
                );
            }
            return arguments[0];
        } else if (stepOutType instanceof Class<?> clazz && clazz.equals(StepOut.class)) {
            throw new IllegalArgumentException(
                "Given type [%s] is a Class, not a parameterized type. Can't get the type parameter"
                    .formatted(stepOutType.getTypeName())
            );
        } else {
            return stepOutType;
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
