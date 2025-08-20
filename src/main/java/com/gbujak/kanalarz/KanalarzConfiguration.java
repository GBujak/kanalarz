package com.gbujak.kanalarz;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class KanalarzConfiguration {

    @Bean
    KanalarzStepsRegistry kanalarzStepsRegistry() {
        return new KanalarzStepsRegistry();
    }

    @Bean
    Kanalarz kanalarz(
        KanalarzStepsRegistry stepsRegistry,
        @Lazy KanalarzSerialization serialization,
        @Lazy KanalarzPersistence persistence
    ) {
        return new Kanalarz(stepsRegistry, serialization, persistence);
    }

    @Bean
    KanalarzBeanPostProcessor kanalarzBeanPostProcessor(
        @Lazy Kanalarz kanalarz,
        @Lazy KanalarzStepsRegistry stepsRegistry
    ) {
        return new KanalarzBeanPostProcessor(kanalarz, stepsRegistry);
    }
}
