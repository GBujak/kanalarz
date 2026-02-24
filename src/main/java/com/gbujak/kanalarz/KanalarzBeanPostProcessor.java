package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@NullMarked
class KanalarzBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KanalarzBeanPostProcessor.class);

    private final ObjectProvider<Kanalarz> kanalarzProvider;
    private final AtomicReference<@Nullable Kanalarz> kanalarzAtomicRef = new AtomicReference<>();

    private final ObjectProvider<KanalarzStepsRegistry> stepsRegistryProvider;

    public KanalarzBeanPostProcessor(
        ObjectProvider<Kanalarz> kanalarzProvider,
        ObjectProvider<KanalarzStepsRegistry> stepsRegistryProvider
    ) {
        this.kanalarzProvider = kanalarzProvider;
        this.stepsRegistryProvider = stepsRegistryProvider;
    }

    @Override
    @Nullable
    public Object postProcessAfterInitialization(
        Object target,
        String beanName
    ) throws BeansException {

        var targetClass = ClassUtils.getUserClass(target);

        var stepsComponent = targetClass.getAnnotation(StepsHolder.class);
        if (stepsComponent == null) {
            return BeanPostProcessor.super.postProcessAfterInitialization(target, beanName);
        }

        log.info("KANALARZ processing bean [{}] with step identifier [{}]", beanName, stepsComponent.value());

        // Should not be necessary, spring will fail on its own on a final class component, just in case
        if (Modifier.isFinal(targetClass.getModifiers())) {
            throw new RuntimeException(
                "Class [%s] of bean [%s] if Final, can't use it as a steps container!"
                    .formatted(targetClass.getName(), beanName)
            );
        }

        try {
            validateAndRegisterSteps(target, targetClass, stepsComponent);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to validate step in bean [%s] with identifier [%s]"
                    .formatted(beanName, stepsComponent.value()),
                e
            );
        }

        var proxyFactory = new ProxyFactory();

        proxyFactory.setTargetClass(targetClass);
        proxyFactory.setInterfaces(targetClass.getInterfaces());
        proxyFactory.setTarget(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            var method = invocation.getMethod();
            Step step = getMergedMethodAnnotation(method, targetClass, Step.class);
            RollbackOnly rollbackOnly = getMergedMethodAnnotation(method, targetClass, RollbackOnly.class);
            if (step == null && rollbackOnly == null) {
                return invocation.proceed();
            } else {
                var kanalarz = kanalarzAtomicRef.get();
                if (kanalarz == null) {
                    kanalarz = kanalarzProvider.getObject();
                    kanalarzAtomicRef.compareAndSet(null, kanalarz);
                }
                return kanalarz.handleMethodInvocation(invocation, stepsComponent, step, rollbackOnly);
            }
        });

        var classLoader = targetClass.getClassLoader();
        if (classLoader == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        }

        return proxyFactory.getProxy(classLoader);
    }

    private void validateAndRegisterSteps(
        Object target,
        Class<?> targetClass,
        StepsHolder stepsHolder
    ) {

        List<Method> methods = new ArrayList<>();
        ReflectionUtils.doWithMethods(targetClass, methods::add);

        // sort so rollbacks are at the back so it's easier to register them in the KanalarzContext class
        methods.sort(Comparator.comparing(method -> hasMethodAnnotation(method, targetClass, Rollback.class)));
        
        for (var method : methods) {
            var step = getMergedMethodAnnotation(method, targetClass, Step.class);
            var rollback = getMergedMethodAnnotation(method, targetClass, Rollback.class);
            var rollbackOnly = getMergedMethodAnnotation(method, targetClass, RollbackOnly.class);
            var returnIsSecret = hasMethodAnnotation(method, targetClass, Secret.class);

            if (step == null && rollback == null && rollbackOnly == null) {
                continue;
            }

            if (Stream.of(step, rollback, rollbackOnly).filter(Objects::nonNull).count() > 1) {
                throw new RuntimeException(
                    "Method [%s] can't be a step and a rollback at the same time!"
                        .formatted(method.getName())
                );
            }

            // Should not be necessary, spring will fail on its own on a final method, just in case
            if (Modifier.isFinal(method.getModifiers())) {
                throw new RuntimeException(
                    "Method [%s] in class [%s] annotated as step of rollback step [%s] is final which is not allowed!"
                        .formatted(
                            method.getName(),
                            targetClass,
                            Stream.concat(
                                Optional.ofNullable(step).map(Step::value).stream(),
                                Optional.ofNullable(rollback).map(Rollback::value).stream()
                            ).findAny()
                                .orElse("n/a")
                        )
                );
            }

            if (step != null) {
                stepsRegistryProvider
                    .getObject()
                    .registerRollforwardStep(target, method, stepsHolder, step, returnIsSecret);
            }

            if (rollback != null) {
                stepsRegistryProvider
                    .getObject()
                    .registerRollbackStep(target, method, stepsHolder, rollback, returnIsSecret);
            }

            if (rollbackOnly != null) {
                stepsRegistryProvider
                    .getObject()
                    .registerRollbackOnlyStep(target, method, stepsHolder, rollbackOnly, returnIsSecret);
            }
        }
    }

    private static <T extends Annotation> boolean hasMethodAnnotation(
        Method method,
        Class<?> targetClass,
        Class<T> annotationType
    ) {
        return getMergedMethodAnnotation(method, targetClass, annotationType) != null;
    }

    @Nullable
    private static <T extends Annotation> T getMergedMethodAnnotation(
        Method method,
        Class<?> targetClass,
        Class<T> annotationType
    ) {
        var mergedAnnotation = AnnotatedElementUtils.getMergedAnnotation(method, annotationType);
        if (mergedAnnotation != null) {
            return mergedAnnotation;
        }

        var mostSpecificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
        if (!mostSpecificMethod.equals(method)) {
            mergedAnnotation = AnnotatedElementUtils.getMergedAnnotation(mostSpecificMethod, annotationType);
            if (mergedAnnotation != null) {
                return mergedAnnotation;
            }
        }

        for (var iface : ClassUtils.getAllInterfacesForClassAsSet(targetClass)) {
            var interfaceMethod = ReflectionUtils.findMethod(iface, method.getName(), method.getParameterTypes());
            if (interfaceMethod == null) {
                continue;
            }
            mergedAnnotation = AnnotatedElementUtils.getMergedAnnotation(interfaceMethod, annotationType);
            if (mergedAnnotation != null) {
                return mergedAnnotation;
            }
        }

        return null;
    }
}
