package com.gbujak.kanalarz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
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
