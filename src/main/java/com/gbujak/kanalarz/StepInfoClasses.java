package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@NullMarked
class StepInfoClasses {

    static class StepInfo {
        @Nullable Method method;
        @Nullable Object target;
        @Nullable Step step;
        @Nullable Rollback rollback;
        @Nullable RollbackOnly rollbackOnly;
        @Nullable ParameterizedStepDescription description;
        StepsHolder stepsHolder;
        Type returnType;
        boolean isReturnTypeNonNullable;
        List<ParamInfo> paramsInfo;
        boolean returnIsSecret;
        boolean rollbackMarker;

        private StepInfo() {}

        public static StepInfo createNew(
            Object target,
            Method method,
            StepsHolder stepsHolder,
            Step step,
            boolean returnIsSecret
        ) {
            return doCreateNew(
                target,
                method,
                stepsHolder,
                step,
                null,
                null,
                returnIsSecret,
                false
            );
        }

        public static StepInfo createNew(
            Object target,
            Method method,
            StepsHolder stepsHolder,
            Rollback rollback,
            boolean returnIsSecret
        ) {
            return doCreateNew(
                target,
                method,
                stepsHolder,
                null,
                rollback,
                null,
                returnIsSecret,
                false
            );
        }

        public static  StepInfo[] createNew(
            Object target,
            Method method,
            StepsHolder stepsHolder,
            RollbackOnly rollbackOnly,
            boolean returnIsSecret
        ) {
            return new StepInfo[] {
                doCreateNew(
                    target,
                    method,
                    stepsHolder,
                    null,
                    null,
                    rollbackOnly,
                    returnIsSecret,
                    true
                ),
                doCreateNew(
                    target,
                    method,
                    stepsHolder,
                    null,
                    null,
                    rollbackOnly,
                    returnIsSecret,
                    false
                )
            };
        }

        private static StepInfo doCreateNew(
            Object target,
            Method method,
            StepsHolder stepsHolder,
            @Nullable Step step,
            @Nullable Rollback rollback,
            @Nullable RollbackOnly rollbackOnly,
            boolean returnIsSecret,
            boolean rollbackMarker
        ) {
            var stepInfo = new StepInfo();
            stepInfo.stepsHolder = stepsHolder;
            stepInfo.target = target;
            stepInfo.method = method;
            stepInfo.step = step;
            stepInfo.rollback = rollback;
            stepInfo.rollbackOnly = rollbackOnly;
            stepInfo.returnIsSecret = returnIsSecret;
            stepInfo.rollbackMarker = rollbackMarker;

            if (!rollbackMarker) {
                stepInfo.description =
                    Optional.ofNullable(method.getAnnotation(StepDescription.class))
                        .map(StepDescription::value)
                        .map(ParameterizedStepDescription::parse)
                        .orElse(null);
            }

            stepInfo.returnType = method.getGenericReturnType();
            stepInfo.isReturnTypeNonNullable = Utils.isReturnTypeNonNullable(method);
            stepInfo.paramsInfo = new ArrayList<>(method.getParameterCount());
            for (var param : method.getParameters()) {
                stepInfo.paramsInfo.add(ParamInfo.createNew(param));
            }
            return stepInfo;
        }
    }

    static class ParamInfo {
        String paramName;
        Type type;
        boolean secret;
        boolean isNonNullable;
        boolean isRollforwardOutput = false;

        private ParamInfo() {}

        private static ParamInfo createNew(Parameter param) {
            var paramInfo = new ParamInfo();
            paramInfo.paramName =
                Optional.ofNullable(param.getAnnotation(Arg.class))
                    .map(Arg::value)
                    .orElseGet(param::getName);
            paramInfo.secret = param.getAnnotation(Secret.class) != null;
            paramInfo.type = param.getParameterizedType();
            paramInfo.isNonNullable = Utils.isNonNullable(param);
            if (param.getAnnotation(RollforwardOut.class) != null) {
                paramInfo.isRollforwardOutput = true;
            }
            return paramInfo;
        }
    }
}
