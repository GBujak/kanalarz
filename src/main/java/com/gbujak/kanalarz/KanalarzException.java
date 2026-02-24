package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Base runtime exception type for Kanalarz.
 */
@NullMarked
public sealed abstract class KanalarzException extends RuntimeException permits
    KanalarzException.KanalarzStepFailedException,
    KanalarzException.KanalarzThrownOutsideOfStepException,
    KanalarzException.KanalarzRollbackStepFailedException,
    KanalarzException.KanalarzInternalError,
    KanalarzException.KanalarzContextCancelledException,
    KanalarzException.KanalarzContextPoisonedException,
    KanalarzException.KanalarzIllegalUsageException,
    KanalarzException.KanalarzSerializationException,
    KanalarzException.KanalarzPersistenceException,
    KanalarzException.KanalarzNewStepBeforeReplayEndedException,
    KanalarzException.KanalarzNotAllStepsReplayedException,
    KanalarzException.KanalarzNoContextException
{

    private KanalarzException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Step method failed during pipeline execution.
     */
    public final static class KanalarzStepFailedException extends KanalarzException {
        /** Original exception thrown by the step. */
        @Nullable private final Throwable initialStepFailedException;
        KanalarzStepFailedException(@Nullable Throwable cause) {
            super("Pipeline step failed: " +
                Optional.ofNullable(cause).map(Throwable::getMessage).orElse("No message"), cause);
            initialStepFailedException = cause;
        }

        /**
         * Get original exception thrown by the failed step.
         * @return original exception thrown by the failed step
         */
        @Nullable
        public Throwable getInitialStepFailedException() {
            return initialStepFailedException;
        }
    }

    /**
     * Exception thrown by user code outside of a step method.
     */
    public final static class KanalarzThrownOutsideOfStepException extends KanalarzException {
        KanalarzThrownOutsideOfStepException(Throwable cause) {
            super("Exception thrown outside of step: " + cause.getMessage(), cause);
        }
    }

    /**
     * Step failed and rollback failed as well.
     */
    public final static class KanalarzRollbackStepFailedException extends KanalarzException {
        /** Original step failure. */
        @Nullable private final Throwable initialStepFailedException;
        /** Rollback failure that happened while unwinding. */
        @Nullable private final Throwable rollbackStepFailedException;
        KanalarzRollbackStepFailedException(@Nullable Throwable cause, @Nullable Throwable rollbackCause) {
            super(
                "Pipeline step failed with message ["
                    + Optional.ofNullable(cause).map(Throwable::getMessage).orElse("n/a") +
                    "] and then the pipeline rollback failed: " +
                    Optional.ofNullable(rollbackCause).map(Throwable::getMessage).orElse("n/a"),
                rollbackCause
            );
            initialStepFailedException = cause;
            rollbackStepFailedException = rollbackCause;
        }

        /**
         * Get original step failure.
         * @return original step failure
         */
        @Nullable
        public Throwable getInitialStepFailedException() {
            return initialStepFailedException;
        }

        /**
         * Get rollback failure.
         * @return rollback failure
         */
        @Nullable
        public Throwable getRollbackStepFailedException() {
            return rollbackStepFailedException;
        }
    }

    /**
     * Unexpected internal library error.
     */
    public final static class KanalarzInternalError extends KanalarzException {
        KanalarzInternalError(String message, @Nullable Throwable cause) {
            super("Internal unexpected error in the library implementation: " + message, cause);
        }
    }

    /**
     * Context has been cancelled.
     */
    public final static class KanalarzContextCancelledException extends KanalarzException {

        /** Whether cancellation forces deferred rollback mode. */
        private final boolean forceDeferRollback;

        /**
         * Check whether cancellation forces deferred rollback behavior.
         * @return true when cancellation forces deferred rollback behavior
         */
        public boolean forceDeferRollback() {
            return this.forceDeferRollback;
        }

        KanalarzContextCancelledException(boolean forceDeferRollback) {
            super("Context was cancelled", null);
            this.forceDeferRollback = forceDeferRollback;
        }
    }

    /**
     * Context cannot continue because replay state became invalid.
     */
    public final static class KanalarzContextPoisonedException extends KanalarzException {
        KanalarzContextPoisonedException() {
            super("Context was poisoned! Some thread failed it's resume-replay. Will unwind.", null);
        }
    }

    /**
     * Illegal API usage by caller.
     */
    public final static class KanalarzIllegalUsageException extends KanalarzException {
        KanalarzIllegalUsageException(String message) {
            super("Illegal usage of kanalarz library: " + message, null);
        }
    }

    /**
     * User-provided serialization adapter failed.
     */
    public final static class KanalarzSerializationException extends KanalarzException {
        KanalarzSerializationException(RuntimeException e) {
            super("Provided serialization bean threw an exception: " + e.getMessage(), e);
        }
    }

    /**
     * User-provided persistence adapter failed.
     */
    public final static class KanalarzPersistenceException extends KanalarzException {
        KanalarzPersistenceException(RuntimeException e) {
            super("Provided persistence bean threw an exception: " + e.getMessage(), e);
        }
    }

    /**
     * Replay tried to execute a step before replay requirements were satisfied.
     */
    public final static class KanalarzNewStepBeforeReplayEndedException extends KanalarzException {
        KanalarzNewStepBeforeReplayEndedException(String message) {
            super("New step started before replay ended: " + message, null);
        }
    }

    /**
     * Replay finished while some previously executed steps remained unreplayed.
     */
    public final static class KanalarzNotAllStepsReplayedException extends KanalarzException {
        KanalarzNotAllStepsReplayedException(String message) {
            super("Not all steps have been replayed: " + message, null);
        }
    }

    /**
     * API requiring active context was called outside of context.
     */
    public final static class KanalarzNoContextException extends KanalarzException {
        KanalarzNoContextException() {
            super("Trying to so something that requires a context outside of any active context", null);
        }
    }
}
