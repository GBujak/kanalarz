package com.gbujak.kanalarz;

public sealed abstract class KanalarzException extends RuntimeException permits
    KanalarzException.KanalarzStepFailedException,
    KanalarzException.KanalarzThrownOutsideOfStepException,
    KanalarzException.KanalarzRollbackStepFailedException,
    KanalarzException.KanalarzInternalError,
    KanalarzException.KanalarzContextCancelledException,
    KanalarzException.KanalarzIllegalUsageException,
    KanalarzException.KanalarzSerializationException
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
        private final Throwable initialStepFailedException;
        KanalarzInternalError(String message, Throwable cause) {
            super("Internal unexpected error in the library implementation: " + message, cause);
            initialStepFailedException = cause;
        }

        public Throwable getInitialStepFailedException() {
            return initialStepFailedException;
        }
    }

    public final static class KanalarzContextCancelledException extends KanalarzException {
        KanalarzContextCancelledException() {
            super("Context was cancelled", null);
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
}
