package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Rollback;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsComponent;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.lang.NonNull;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class KanalarzBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(KanalarzBeanPostProcessor.class);

    private final KanalarzContext kanalarzContext;

    public KanalarzBeanPostProcessor(KanalarzContext kanalarzContext) {
        this.kanalarzContext = kanalarzContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object target, @NonNull String beanName) throws BeansException {
        var stepsComponent = target.getClass().getAnnotation(StepsComponent.class);
        if (stepsComponent == null) {
            return BeanPostProcessor.super.postProcessAfterInitialization(target, beanName);
        }

        log.info("KANALARZ processing bean [{}] with step identifier [{}]", beanName, stepsComponent.identifier());

        try {
            validateSteps(target);
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to validate step bean [%s] with identifier [%s]"
                    .formatted(beanName, stepsComponent.identifier()),
                e
            );
        }

        var proxyFactory = new ProxyFactory();

        proxyFactory.setTargetClass(target.getClass());
        proxyFactory.setInterfaces(target.getClass().getInterfaces());
        proxyFactory.setTarget(target);
        proxyFactory.addAdvice((MethodInterceptor) invocation -> {

            System.out.println("intercepted method call");
            var method = invocation.getMethod();
            Step step = method.getAnnotation(Step.class);
            if (step == null) {
                return method.invoke(target, invocation.getArguments());
            } else {
                return kanalarzContext.handleMethodInvocation(target, invocation, stepsComponent, step);
            }
        });

        return proxyFactory.getProxy(getClass().getClassLoader());
    }

    private void validateSteps(Object target) {
        record MethodAndAnnotations(Method method, Step step) {}

        List<MethodAndAnnotations> methods = new ArrayList<>();
        ReflectionUtils.doWithMethods(target.getClass(), method -> {
            methods.add(new MethodAndAnnotations(method, method.getAnnotation(Step.class)));
        });

        for (var methodAndAnnotation : methods) {
            var method = methodAndAnnotation.method();
            var step = methodAndAnnotation.step();
            var rollback = method.getAnnotation(Rollback.class);

            if (step == null && rollback == null) {
                continue;
            }

            if (step != null && rollback != null) {
                throw new RuntimeException(
                    "Method [%s] can't be a step and a rollback at the same time!"
                        .formatted(method.getName())
                );
            }

            if (step != null) {
            }

            if (rollback != null) {
                boolean rollbackTargetExists = methods.stream()
                    .anyMatch(it ->
                        it.step() != null && it.step().identifier().equals(rollback.forStep())
                    );

                if (!rollbackTargetExists) {
                    throw new RuntimeException(
                        "Rollback method [%s] is for a non-existing step [%s]"
                            .formatted(method.getName(), rollback.forStep())
                    );
                }
            }
        }
    }
}
