package com.gbujak.kanalarz;

import com.gbujak.kanalarz.annotations.RollbackOnly;
import com.gbujak.kanalarz.annotations.Step;
import com.gbujak.kanalarz.annotations.StepsHolder;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KanalarzStepExecutionStateGuardTests {

    private static final Method HANDLE_IN_CONTEXT_METHOD = handleInContextMethod();

    private static final KanalarzSerialization NOOP_SERIALIZATION = new KanalarzSerialization() {
        @Override
        public String serializeStepCalled(List<SerializeParameterInfo> parametersInfo, SerializeReturnInfo returnInfo) {
            return "";
        }

        @Override
        public DeserializeParametersResult deserializeParameters(
            String serialized,
            List<DeserializeParameterInfo> parametersInfo,
            Type returnType
        ) {
            return new DeserializeParametersResult(Map.of(), null, null);
        }

        @Override
        public boolean parametersAreEqualIgnoringReturn(String left, String right) {
            return left.equals(right);
        }
    };

    private static final KanalarzPersistence NOOP_PERSISTENCE = new KanalarzPersistence() {
        @Override
        public void stepStarted(StepStartedEvent stepStartedEvent) { }

        @Override
        public void stepCompleted(StepCompletedEvent stepCompletedEvent) { }

        @Override
        public List<StepExecutedInfo> getExecutedStepsInContextInOrderOfExecutionStarted(UUID contextId) {
            return List.of();
        }
    };

    @Test
    void shouldRejectStepExecutionWhenContextWasCancelled() {
        var context = new KanalarzContext(UUID.randomUUID(), EnumSet.noneOf(Kanalarz.Option.class), null, null);
        context.moveState(KanalarzContext.State.RUNNING, KanalarzContext.State.CANCELLED);

        assertThatThrownBy(() -> invokeHandleInContextMethodExecution(context))
            .isExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class)
            .satisfies(ex -> assertThat(((KanalarzException.KanalarzContextCancelledException) ex)
                .forceDeferRollback()).isFalse());
    }

    @Test
    void shouldRejectStepExecutionWhenContextWasCancelledWithForcedDeferredRollback() {
        var context = new KanalarzContext(UUID.randomUUID(), EnumSet.noneOf(Kanalarz.Option.class), null, null);
        context.moveState(KanalarzContext.State.RUNNING, KanalarzContext.State.CANCELLED_FORCE_DEFER_ROLLBACK);

        assertThatThrownBy(() -> invokeHandleInContextMethodExecution(context))
            .isExactlyInstanceOf(KanalarzException.KanalarzContextCancelledException.class)
            .satisfies(ex -> assertThat(((KanalarzException.KanalarzContextCancelledException) ex)
                .forceDeferRollback()).isTrue());
    }

    private static Method handleInContextMethod() {
        try {
            var method = Kanalarz.class.getDeclaredMethod(
                "handleInContextMethodExecution",
                MethodInvocation.class,
                StepsHolder.class,
                Step.class,
                RollbackOnly.class,
                KanalarzContext.class
            );
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object invokeHandleInContextMethodExecution(KanalarzContext context) {
        var kanalarz = new Kanalarz(new KanalarzStepsRegistry(), NOOP_SERIALIZATION, NOOP_PERSISTENCE);

        try {
            return HANDLE_IN_CONTEXT_METHOD.invoke(kanalarz, null, null, null, null, context);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
