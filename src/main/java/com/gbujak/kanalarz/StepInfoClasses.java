package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Arg;
import com.gbujak.kanalarz.annotations.Secret;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

class StepInfoClasses {

    static class StepInfo {
        Method method;
        Object target;
        Type returnType;
        boolean isReturnTypeNonNullable;
        List<ParamInfo> paramsInfo;
    }

    static class ParamInfo {
        String paramName;
        Type type;
        boolean secret;
        boolean isNonNullable;
        boolean isRollforwardOutput = false;

        public static ParamInfo fromParam(Parameter param) {
            var paramInfo = new ParamInfo();
            paramInfo.paramName =
                Optional.ofNullable(param.getAnnotation(Arg.class))
                    .map(Arg::value)
                    .orElseGet(param::getName);
            paramInfo.secret = param.getAnnotation(Secret.class) != null;
            paramInfo.type = param.getParameterizedType();
            paramInfo.isNonNullable = NullabilityUtils.isNonNullable(param);
            return paramInfo;
        }
    }
}
