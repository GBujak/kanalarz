package com.gbujak.kanalarz;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class KanalarzConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    KanalarzContext kanalarzContext() {
        return new KanalarzContext();
    }

    @Bean
    KanalarzBeanPostProcessor kanalarzBeanPostProcessor(KanalarzContext context) {
        return new KanalarzBeanPostProcessor(context);
    }
}
