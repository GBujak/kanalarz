package com.gbujak.kanalarz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KanalarzConfiguration {

    @Bean
    KanalarzStepsRegistry kanalarzStepsRegistry() {
        return new KanalarzStepsRegistry();
    }

    @Bean
    Kanalarz kanalarz(
        KanalarzStepsRegistry stepsRegistry,
        KanalarzSerialization serialization,
        KanalarzPersistence persistence
    ) {
        return new Kanalarz(stepsRegistry, serialization, persistence);
    }

    @Bean
    KanalarzBeanPostProcessor kanalarzBeanPostProcessor(
        Kanalarz kanalarz,
        KanalarzStepsRegistry stepsRegistry
    ) {
        return new KanalarzBeanPostProcessor(kanalarz, stepsRegistry);
    }
}
