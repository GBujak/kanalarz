package com.gbujak.kanalarz;


import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

/**
 * Wrapper used by fallible Kanalarz steps to return either a value or an error.
 * @param <T> wrapped successful value type
 */
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

    /**
     * Create successful result.
     * @param value successful value
     * @param <T> value type
     * @return successful StepOut
     */
    public static <T> StepOut<T> of(T value) {
        return StepOut.ofNonNullOrThrow(value);
    }

    static <T> StepOut<T> ofNonNullOrThrow(@Nullable T value) {
        return new StepOut<T>(Objects.requireNonNull(value), null);
    }

    /**
     * Create successful optional result from nullable value.
     * @param value nullable value
     * @param <T> inner value type
     * @return successful StepOut of Optional
     */
    public static <T> StepOut<Optional<T>> ofNullable(@Nullable T value) {
        return StepOut.of(Optional.ofNullable(value));
    }

    /**
     * Create successful empty optional result.
     * @param <T> inner value type
     * @return successful StepOut containing Optional.empty()
     */
    public static <T> StepOut<Optional<T>> empty() {
        return StepOut.of(Optional.empty());
    }

    /**
     * Create failed result.
     * @param error failure
     * @param <T> value type
     * @return failed StepOut
     */
    public static <T> StepOut<T> err(Throwable error) {
        Objects.requireNonNull(
            error,
            "Can't use null error for StepOut. If you need an empty result, use StepOut<Optional<T>>"
        );
        return new StepOut<>(null, error);
    }

    /**
     * Get successful value as optional.
     * @return optional value
     */
    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    /**
     * Get value or fallback when this result is failed.
     * @param other fallback value
     * @return value or fallback
     */
    @Nullable
    public T valueOrElse(@Nullable T other) {
        return value == null ? other : value;
    }

    /**
     * Get value or null.
     * @return value or null
     */
    @Nullable
    public T valueOrNull() {
        return value;
    }

    /**
     * Get failure as optional.
     * @return optional error
     */
    public Optional<Throwable> error() {
        return Optional.ofNullable(error);
    }

    /**
     * Get failure or null.
     * @return error or null
     */
    @Nullable
    public Throwable errorOrNull() {
        return error;
    }

    /**
     * Get value or throw stored error.
     * @return successful value
     */
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

    /**
     * Check whether this result carries a successful value.
     * @return true when this result carries a value
     */
    public boolean isOk() {
        return this.value != null;
    }

    /**
     * Check whether this result carries an error.
     * @return true when this result carries an error
     */
    public boolean isErr() {
        return this.value == null;
    }

    /**
     * Check whether a type is {@link StepOut} or {@code StepOut<...>}.
     * @param type type to inspect
     * @return true if type represents StepOut
     */
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
     * @param stepOutType type to unwrap
     * @return wrapped type argument, or original type when not StepOut
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
