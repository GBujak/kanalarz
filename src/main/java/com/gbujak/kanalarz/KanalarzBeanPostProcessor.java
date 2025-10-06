package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Secret;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

class KanalarzBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KanalarzBeanPostProcessor.class);

    private final ObjectProvider<Kanalarz> kanalarzProvider;
    private final AtomicReference<Kanalarz> kanalarzAtomicRef = new AtomicReference<>();

    private final ObjectProvider<KanalarzStepsRegistry> stepsRegistryProvider;

    public KanalarzBeanPostProcessor(
        ObjectProvider<Kanalarz> kanalarzProvider,
        ObjectProvider<KanalarzStepsRegistry> stepsRegistryProvider
    ) {
        this.kanalarzProvider = kanalarzProvider;
        this.stepsRegistryProvider = stepsRegistryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(
        @NonNull Object target,
        @NonNull String beanName
    ) throws BeansException {

        var targetClass = ClassUtils.getUserClass(target);

        var stepsComponent = targetClass.getAnnotation(StepsHolder.class);
        if (stepsComponent == null) {
            return BeanPostProcessor.super.postProcessAfterInitialization(target, beanName);
        }

        log.info("KANALARZ processing bean [{}] with step identifier [{}]", beanName, stepsComponent.identifier());

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
                    .formatted(beanName, stepsComponent.identifier()),
                e
            );
        }

        var proxyFactory = new ProxyFactory();

        proxyFactory.setTargetClass(targetClass);
        proxyFactory.setInterfaces(targetClass.getInterfaces());
        proxyFactory.setTarget(target);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            var method = invocation.getMethod();
            Step step = method.getAnnotation(Step.class);
            if (step == null) {
                return method.invoke(target, invocation.getArguments());
            } else {
                var kanalarz = kanalarzAtomicRef.get();
                if (kanalarz == null) {
                    kanalarz = kanalarzProvider.getObject();
                    kanalarzAtomicRef.compareAndSet(null, kanalarz);
                }
                return kanalarz.handleMethodInvocation(target, invocation, stepsComponent, step);
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
        methods.sort(Comparator.comparing(it -> (Boolean) it.isAnnotationPresent(Rollback.class)));
        
        for (var method : methods) {
            var step = method.getAnnotation(Step.class);
            var rollback = method.getAnnotation(Rollback.class);
            var returnIsSecret = method.getAnnotation(Secret.class) != null;

            if (step == null && rollback == null) {
                continue;
            }

            if (step != null && rollback != null) {
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
                                Optional.ofNullable(step).map(Step::identifier).stream(),
                                Optional.ofNullable(rollback).map(Rollback::forStep).stream()
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
        }
    }
}
