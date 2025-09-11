package com.gbujak.kanalarz;

public sealed abstract class KanalarzException extends RuntimeException permits
    KanalarzException.KanalarzStepFailedException,
    KanalarzException.KanalarzThrownOutsideOfStepException,
    KanalarzException.KanalarzRollbackStepFailedException,
    KanalarzException.KanalarzInternalError,
    KanalarzException.KanalarzContextCancelledException,
    KanalarzException.KanalarzIllegalUsageException,
    KanalarzException.KanalarzSerializationException,
    KanalarzException.KanalarzNewStepBeforeReplayEndedException,
    KanalarzException.KanalarzNotAllStepsReplayedException,
    KanalarzException.KanalarzStepsWereNotReplayedAndWillPartiallyRollbackException
{

    private KanalarzException(String message, Throwable cause) {
        super(message, cause);
    }

    public final static class KanalarzStepFailedException extends KanalarzException {
        private final Throwable initialStepFailedException;
        KanalarzStepFailedException(Throwable cause) {
            super("Pipeline step failed: " + cause.getMessage(), cause);
            initialStepFailedException = cause;
        }

        public Throwable getInitialStepFailedException() {
            return initialStepFailedException;
        }
    }

    public final static class KanalarzThrownOutsideOfStepException extends KanalarzException {
        KanalarzThrownOutsideOfStepException(Throwable cause) {
            super("Exception thrown outside of step: " + cause.getMessage(), cause);
        }
    }

    public final static class KanalarzRollbackStepFailedException extends KanalarzException {
        private final Throwable initialStepFailedException;
        private final Throwable rollbackStepFailedException;
        KanalarzRollbackStepFailedException(Throwable cause, Throwable rollbackCause) {
            super(
                "Pipeline step failed with message ["
                    + cause.getMessage() +
                    "] and then the pipeline rollback failed: " +
                    rollbackCause.getMessage(), rollbackCause
            );
            initialStepFailedException = cause;
            rollbackStepFailedException = rollbackCause;
        }

        public Throwable getInitialStepFailedException() {
            return initialStepFailedException;
        }

        public Throwable getRollbackStepFailedException() {
            return rollbackStepFailedException;
        }
    }

    public final static class KanalarzInternalError extends KanalarzException {
        KanalarzInternalError(String message, Throwable cause) {
            super("Internal unexpected error in the library implementation: " + message, cause);
        }
    }

    public final static class KanalarzContextCancelledException extends KanalarzException {

        private final boolean forceDeferRollback;

        public boolean forceDeferRollback() {
            return this.forceDeferRollback;
        }

        KanalarzContextCancelledException(boolean forceDeferRollback) {
            super("Context was cancelled", null);
            this.forceDeferRollback = forceDeferRollback;
        }
    }

    public final static class KanalarzIllegalUsageException extends KanalarzException {
        KanalarzIllegalUsageException(String message) {
            super("Illegal usage of kanalarz library: " + message, null);
        }
    }

    public final static class KanalarzSerializationException extends KanalarzException {
        KanalarzSerializationException(String message) {
            super("Serialization error: " + message, null);
        }
    }

    public final static class KanalarzNewStepBeforeReplayEndedException extends KanalarzException {
        KanalarzNewStepBeforeReplayEndedException(String message) {
            super("New step started before replay ended: " + message, null);
        }
    }

    public final static class KanalarzNotAllStepsReplayedException extends KanalarzException {
        KanalarzNotAllStepsReplayedException(String message) {
            super("Not all steps have been replayed: " + message, null);
        }
    }

    public final static class KanalarzStepsWereNotReplayedAndWillPartiallyRollbackException extends KanalarzException {
        KanalarzStepsWereNotReplayedAndWillPartiallyRollbackException() {
            super(
                "Some steps were not replayed and they will be rolled back " +
                    "but the entire context will not be rolled back.", null
            );
        }
    }
}
