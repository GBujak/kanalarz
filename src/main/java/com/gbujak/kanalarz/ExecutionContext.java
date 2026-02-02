package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

@NullMarked
class ExecutionContext {
    private final String path;
    private int sequenceCounter = 0;

    public ExecutionContext() { this.path = "r"; }
    public ExecutionContext(String path) { this.path = Objects.requireNonNull(path); }

    public String nextStepId() {
        return path + ".s" + (sequenceCounter++);
    }

    public ForkJoinExecutionContext forkJoinContext() {
        return new ForkJoinExecutionContext(path, (sequenceCounter++));
    }

    public ExecutionContext spawnSubContext(@Nullable UUID subcontextId) {
        String subcontextSuffix = subcontextId != null ? "-" + subcontextId : (sequenceCounter++) + "";
        return new ExecutionContext(path + ".c" + subcontextSuffix);
    }

    public ExecutionContext forNestedSteps() {
        return new ExecutionContext(path + ".s" + (sequenceCounter));
    }

    static class ForkJoinExecutionContext {

        private final String path;
        private final int forkBaseId;

        public ForkJoinExecutionContext(String path, int forkBaseId) {
            this.path = path;
            this.forkBaseId = forkBaseId;
        }

        public ExecutionContext forTask(int taskIndex) {
            return new ExecutionContext(path  + ".f" + forkBaseId + "-" + taskIndex);
        }
    }
}

