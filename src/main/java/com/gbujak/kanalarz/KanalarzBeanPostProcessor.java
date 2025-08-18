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
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class KanalarzBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KanalarzBeanPostProcessor.class);

    private final Kanalarz kanalarz;

    public KanalarzBeanPostProcessor(Kanalarz kanalarz) {
        this.kanalarz = kanalarz;
    }

    @Override
    public Object postProcessAfterInitialization(Object target, @NonNull String beanName) throws BeansException {
        var stepsComponent = target.getClass().getAnnotation(StepsHolder.class);
        if (stepsComponent == null) {
            return BeanPostProcessor.super.postProcessAfterInitialization(target, beanName);
        }

        log.info("KANALARZ processing bean [{}] with step identifier [{}]", beanName, stepsComponent.identifier());

        // Should not be necessary, spring will fail on its own on a final class component, just in case
        if (Modifier.isFinal(target.getClass().getModifiers())) {
            throw new RuntimeException(
                "Class [%s] of bean [%s] if Final, can't use it as a steps container!"
                    .formatted(target.getClass().getName(), beanName)
            );
        }

        try {
            validateAndRegisterSteps(target, stepsComponent);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to validate step in bean [%s] with identifier [%s]"
                    .formatted(beanName, stepsComponent.identifier()),
                e
            );
        }

        var proxyFactory = new ProxyFactory();

        proxyFactory.setTargetClass(target.getClass());
        proxyFactory.setInterfaces(target.getClass().getInterfaces());
        proxyFactory.setTarget(target);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {
            var method = invocation.getMethod();
            Step step = method.getAnnotation(Step.class);
            if (step == null) {
                return method.invoke(target, invocation.getArguments());
            } else {
                return kanalarz.handleMethodInvocation(target, invocation, stepsComponent, step);
            }
        });

        return proxyFactory.getProxy(getClass().getClassLoader());
    }

    private void validateAndRegisterSteps(Object target, StepsHolder stepsHolder) {
        List<Method> methods = new ArrayList<>();
        ReflectionUtils.doWithMethods(target.getClass(), methods::add);

        // sort so rollbacks are at the back so it's easier to register them in the KanalarzContext class
        methods.sort(Comparator.comparing(it -> it.isAnnotationPresent(Rollback.class)));
        
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
                            target.getClass().getName(),
                            Stream.concat(
                                Optional.ofNullable(step).map(Step::identifier).stream(),
                                Optional.ofNullable(rollback).map(Rollback::forStep).stream()
                            ).findAny()
                                .orElse("n/a")
                        )
                );
            }

            if (step != null) {
                kanalarz.registerRollforwardStep(target, method, stepsHolder, step, returnIsSecret);
            }

            if (rollback != null) {
                kanalarz.registerRollbackStep(target, method, stepsHolder, rollback, returnIsSecret);
            }
        }
    }
}
