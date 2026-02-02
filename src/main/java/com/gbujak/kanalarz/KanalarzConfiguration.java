package com.gbujak.kanalarz;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that registers Kanalarz core beans.
 */
@Configuration
public class KanalarzConfiguration {

    /** Create configuration instance. */
    public KanalarzConfiguration() { }

    @Bean
    static KanalarzStepsRegistry kanalarzStepsRegistry() {
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
    static KanalarzBeanPostProcessor kanalarzBeanPostProcessor(
        ObjectProvider<Kanalarz> kanalarz,
        ObjectProvider<KanalarzStepsRegistry> stepsRegistry
    ) {
        return new KanalarzBeanPostProcessor(kanalarz, stepsRegistry);
    }
}
