package com.gbujak.kanalarz;

import kotlin.Metadata;
import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KClass;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@NullMarked
class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static boolean isNonNullable(Parameter parameter) {
        if (isAnnotatedWithNullable(parameter.getAnnotations())
                || isAnnotatedWithNullable(parameter.getAnnotatedType().getAnnotations())) {
            return false;
        }

        if (isAnnotatedWithNonNullable(parameter.getAnnotations())
                || isAnnotatedWithNonNullable(parameter.getAnnotatedType().getAnnotations())) {
            return true;
        }

        if (isPackageNullMarked(parameter.getDeclaringExecutable().getDeclaringClass())) {
            return true;
        }

        if (isClassNullMarked(parameter.getDeclaringExecutable().getDeclaringClass())) {
            return true;
        }

        Class<?> declaringClass = parameter.getDeclaringExecutable().getDeclaringClass();
        if (isKotlinClass(declaringClass)) {
            return isKotlinNonNullableType(parameter);
        }

        return false;
    }

    public static boolean isReturnTypeNonNullable(Method method) {
        if (isAnnotatedWithNullable(method.getAnnotations())
                || isAnnotatedWithNullable(method.getAnnotatedReturnType().getAnnotations())) {
            return false;
        }

        if (isAnnotatedWithNonNullable(method.getAnnotations())
                || isAnnotatedWithNonNullable(method.getAnnotatedReturnType().getAnnotations())) {
            return true;
        }

        if (isPackageNullMarked(method.getDeclaringClass())) {
            return true;
        }

        if (isClassNullMarked(method.getDeclaringClass())) {
            return true;
        }

        Class<?> declaringClass = method.getDeclaringClass();
        if (isKotlinClass(declaringClass)) {
            return isKotlinNonNullableReturnType(method);
        }

        return false;
    }

    private static boolean isKotlinClass(Class<?> clazz) {
        return clazz.getAnnotation(Metadata.class) != null;
    }

    private static boolean isAnnotatedWithNullable(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (isNullableAnnotation(annotation.annotationType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnnotatedWithNonNullable(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (isNonNullableAnnotation(annotation.annotationType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNullableAnnotation(Class<?> annotationType) {
        String annotationName = annotationType.getName();
        return
            annotationName.equals("javax.annotation.Nullable") ||
            annotationName.equals("org.jetbrains.annotations.Nullable") ||
            annotationName.equals("edu.umd.cs.findbugs.annotations.Nullable") ||
            annotationName.equals("org.checkerframework.checker.nullness.qual.Nullable") ||
            annotationName.equals("org.jspecify.annotations.Nullable") ||
            annotationName.equals("org.springframework.lang.Nullable");
    }

    private static boolean isNonNullableAnnotation(Class<?> annotationType) {
        String annotationName = annotationType.getName();
        return
            annotationName.equals("javax.annotation.Nonnull") ||
            annotationName.equals("org.jetbrains.annotations.NotNull") ||
            annotationName.equals("edu.umd.cs.findbugs.annotations.NonNull") ||
            annotationName.equals("org.checkerframework.checker.nullness.qual.NonNull") ||
            annotationName.equals("org.jspecify.annotations.NonNull") ||
            annotationName.equals("org.springframework.lang.NonNull");
    }

    private static boolean isPackageNullMarked(Class<?> clazz) {
        try {
            Package pkg = clazz.getPackage();
            if (pkg != null) {
                for (Annotation annotation : pkg.getAnnotations()) {
                    if (isNullMarkedAnnotation(annotation.annotationType())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a package is NullMarked", e);
        }
        return false;
    }

    private static boolean isClassNullMarked(Class<?> clazz) {
        try {
            for (Annotation annotation : clazz.getAnnotations()) {
                if (isNullMarkedAnnotation(annotation.annotationType())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a class is null marked", e);
        }
        return false;
    }

    private static boolean isNullMarkedAnnotation(Class<?> annotationType) {
        String annotationName = annotationType.getName();
        return
            annotationName.equals("javax.annotation.meta.NullMarked") ||
            annotationName.equals("org.jspecify.annotations.NullMarked");
    }

    private static boolean isKotlinNonNullableType(Parameter parameter) {
        try {
            Method method = (Method) parameter.getDeclaringExecutable();
            KFunction<?> kFunction = resolveKotlinFunction(method);

            if (kFunction != null) {
                int parameterIndex = java.util.Arrays.asList(method.getParameters()).indexOf(parameter);
                if (parameterIndex < 0) {
                    return false;
                }
                List<KParameter> kotlinValueParameters = kFunction.getParameters().stream()
                    .filter(p -> p.getKind() == KParameter.Kind.VALUE)
                    .toList();
                if (parameterIndex >= kotlinValueParameters.size()) {
                    return false;
                }
                KParameter kParameter = kotlinValueParameters.get(parameterIndex);
                return !kParameter.getType().isMarkedNullable();
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a kotlin parameter is nullable", e);
        }
        return false;
    }

    private static boolean isKotlinNonNullableReturnType(Method method) {
        try {
            KFunction<?> kFunction = resolveKotlinFunction(method);

            if (kFunction != null) {
                return !kFunction.getReturnType().isMarkedNullable();
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a kotlin return type is nullable", e);
        }
        return false;
    }

    @Nullable
    private static KFunction<?> resolveKotlinFunction(Method method) {
        KFunction<?> directMatch = ReflectJvmMapping.getKotlinFunction(method);
        if (directMatch != null) {
            return directMatch;
        }

        KClass<?> kClass = Reflection.getOrCreateKotlinClass(method.getDeclaringClass());
        return kClass.getMembers().stream()
            .filter(KFunction.class::isInstance)
            .map(KFunction.class::cast)
            .filter(it -> Objects.equals(ReflectJvmMapping.getJavaMethod(it), method))
            .findFirst()
            .orElse(null);
    }

    static ArrayList<KanalarzSerialization.SerializeParameterInfo> makeSerializeParametersInfo(
        @Nullable Object[] arguments,
        StepInfoClasses.StepInfo stepInfo
    ) {
        var serializeParametersInfo = new ArrayList<KanalarzSerialization.SerializeParameterInfo>(arguments.length);
        for (int i = 0; i < arguments.length; i++) {
            var arg = arguments[i];
            var paramInfo = stepInfo.paramsInfo.get(i);
            serializeParametersInfo.add(
                new KanalarzSerialization.SerializeParameterInfo(
                    paramInfo.paramName,
                    paramInfo.type,
                    arg,
                    paramInfo.secret
                )
            );
        }
        return serializeParametersInfo;
    }

    static List<KanalarzSerialization.DeserializeParameterInfo>
    makeDeserializeParamsInfo(List<StepInfoClasses.ParamInfo> paramsInfo) {
        return paramsInfo.stream()
            .map(it -> new KanalarzSerialization.DeserializeParameterInfo(it.paramName, it.type))
            .toList();
    }

    @Nullable
    static Object voidOrUnitValue(Type type) {
        if (type.equals(void.class) || type.equals(Void.class)) {
            return null;
        } else if (type.getTypeName().equals("kotlin.Unit")) {
            try {
                return Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            } catch (Exception e) {
                throw new RuntimeException("Error creating the kotlin.Unit value!", e);
            }
        } else {
            throw new IllegalArgumentException("Type is not void, Void, or kotlin.Unit! " + type.getTypeName());
        }
    }
}
