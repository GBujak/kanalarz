package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class StepInfoClasses {

    static class StepInfo {
        Method method;
        Object target;
        @Nullable Step step;
        @Nullable Rollback rollback;
        @Nullable String description;
        Type returnType;
        boolean isReturnTypeNonNullable;
        List<ParamInfo> paramsInfo;

        private StepInfo() {}

        public static StepInfo createNew(Object target, Method method, Step step) {
            return doCreateNew(target, method, step, null);
        }

        public static StepInfo createNew(Object target, Method method, Rollback rollback) {
            return doCreateNew(target, method, null, rollback);
        }

        private static StepInfo doCreateNew(
            Object target,
            Method method,
            @Nullable Step step,
            @Nullable Rollback rollback
        ) {
            var stepInfo = new StepInfo();
            stepInfo.target = target;
            stepInfo.method = method;
            stepInfo.step = step;
            stepInfo.rollback = rollback;
            stepInfo.description =
                Optional.ofNullable(method.getAnnotation(StepDescription.class))
                    .map(StepDescription::value)
                    .orElse(null);
            stepInfo.returnType = method.getGenericReturnType();
            stepInfo.isReturnTypeNonNullable = NullabilityUtils.isReturnTypeNonNullable(method);
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
            paramInfo.isNonNullable = NullabilityUtils.isNonNullable(param);
            if (param.getAnnotation(RollforwardOut.class) != null) {
                paramInfo.isRollforwardOutput = true;
            }
            return paramInfo;
        }
    }
}
