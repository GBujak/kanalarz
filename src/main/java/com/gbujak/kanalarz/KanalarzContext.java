package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsComponent;
import org.aopalliance.intercept.MethodInvocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class KanalarzContext {

    private final Map<String, Method> steps = new HashMap<>();
    private final Map<String, String> rollbackSteps = new HashMap<>();


    Object handleMethodInvocation(
        Object target,
        MethodInvocation invocation,
        StepsComponent stepsComponent,
        Step step
    ) throws InvocationTargetException, IllegalAccessException {
        var method = invocation.getMethod();
        var arguments = invocation.getArguments();
        System.out.println("dupa");
        System.out.println(stepsComponent.identifier());
        System.out.println(step.identifier());
        System.out.println(step.fallible());
        return method.invoke(target, arguments);
    }
}

