package com.gbujak.kanalarz;

import kotlin.Metadata;
import kotlin.jvm.internal.Reflection;
import kotlin.reflect.KClass;
import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

class Utils {

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static boolean isNonNullable(Parameter parameter) {
        if (isAnnotatedWithNullable(parameter.getAnnotations())) {
            return false;
        }

        if (isAnnotatedWithNonNullable(parameter.getAnnotations())) {
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
        if (isAnnotatedWithNullable(method.getAnnotations())) {
            return false;
        }

        if (isAnnotatedWithNonNullable(method.getAnnotations())) {
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
            KClass<?> kClass = Reflection.getOrCreateKotlinClass(method.getDeclaringClass());

            KFunction<?> kFunction = (KFunction<?>) kClass.getMembers().stream()
                .filter(m -> m.getName().equals(method.getName()))
                .findFirst()
                .orElse(null);

            if (kFunction != null) {
                int parameterIndex = java.util.Arrays.asList(method.getParameters()).indexOf(parameter);
                KParameter kParameter = kFunction.getParameters().get(parameterIndex + 1); // +1 to account for the instance parameter
                return !kParameter.getType().isMarkedNullable();
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a kotlin parameter is nullable", e);
        }
        return false;
    }

    private static boolean isKotlinNonNullableReturnType(Method method) {
        try {
            KClass<?> kClass = Reflection.getOrCreateKotlinClass(method.getDeclaringClass());

            // Get the Kotlin function corresponding to the method
            KFunction<?> kFunction = (KFunction<?>) kClass.getMembers().stream()
                .filter(m -> m.getName().equals(method.getName()))
                .findFirst()
                .orElse(null);

            if (kFunction != null) {
                return !kFunction.getReturnType().isMarkedNullable();
            }
        } catch (Exception e) {
            log.warn("Error trying to determine if a kotlin return type is nullable", e);
        }
        return false;
    }

    static boolean isStepOut(Type type) {
        if (type instanceof ParameterizedType pt) {
            return pt.getRawType().equals(StepOut.class);
        } else if (type instanceof Class<?> clazz) {
            return clazz.equals(StepOut.class);
        }
        return false;
    }

    @NonNull
    static Type getTypeFromStepOut(Type stepOutType) {
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
            throw new RuntimeException(
                "Given type [%s] is a Class, not a parameterized type. Can't get the type parameter"
                    .formatted(stepOutType.getTypeName())
            );
        } else {
            throw new RuntimeException(
                "Given type [%s] is not a StepOut type reference".formatted(stepOutType.getTypeName())
            );
        }
    }
}
