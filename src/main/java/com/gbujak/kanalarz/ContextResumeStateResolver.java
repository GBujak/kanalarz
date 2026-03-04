package com.gbujak.kanalarz;

import com.gbujak.kanalarz.KanalarzPersistence.StepExecutedInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@NullMarked
class ContextResumeStateResolver {

    private final List<StepExecutedInfo> replayableSteps;

    ContextResumeStateResolver(List<StepExecutedInfo> steps) {
        Set<UUID> rolledBack =
            steps.stream()
                .flatMap(it -> it.wasRollbackFor().stream())
                .collect(Collectors.toSet());

        this.replayableSteps = steps.stream()
            .filter(it -> it.wasRollbackFor().isEmpty())
            .filter(it -> !rolledBack.contains(it.stepId()))
            .toList();
    }

    List<StepExecutedInfo> replayable() {
        return replayableSteps;
    }

    @Nullable
    String resolveBasePath(UUID contextId) {
        String result = null;
        boolean allNull = true;

        for (var step : replayableSteps) {
            var path = step.executionPath();
            var contextSegmentIndex = findContextSegmentIndex(path, contextId);
            if (contextSegmentIndex == -1) {
                if (allNull) {
                    continue;
                }
                throw inconsistentRootPaths(contextId);
            }
            allNull = false;

            var basePath = reconstructBasePath(path, contextSegmentIndex);
            if (result != null && !result.equals(basePath)) {
                throw inconsistentRootPaths(contextId);
            }

            result = basePath;
        }

        return result;
    }

    private int findContextSegmentIndex(String path, UUID contextId) {
        var targetSegment = "c-" + contextId;
        var pathSegments = path.split("\\.");
        int result = -1;

        for (int i = 0; i < pathSegments.length; i++) {
            if (!pathSegments[i].equals(targetSegment)) {
                continue;
            }
            if (result != -1) {
                throw new KanalarzException.KanalarzIllegalUsageException(
                    ("Tried to resume-replay context [%s] but its execution path is ambiguous because the same " +
                        "context id appears multiple times in a single path!")
                        .formatted(contextId)
                );
            }
            result = i;
        }

        return result;
    }

    private String reconstructBasePath(String path, int contextSegmentIndex) {
        var pathSegments = path.split("\\.");
        return String.join(".", Arrays.copyOfRange(pathSegments, 0, contextSegmentIndex + 1));
    }

    private KanalarzException.KanalarzIllegalUsageException inconsistentRootPaths(UUID contextId) {
        return new KanalarzException.KanalarzIllegalUsageException(
            "Tried to resume-replay context [%s] but the list of steps had inconsistent root paths!"
                .formatted(contextId)
        );
    }
}
