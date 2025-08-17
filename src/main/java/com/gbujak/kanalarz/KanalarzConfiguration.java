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
    Kanalarz kanalarz() {
        return new Kanalarz();
    }

    @Bean
    KanalarzBeanPostProcessor kanalarzBeanPostProcessor(Kanalarz kanalarz) {
        return new KanalarzBeanPostProcessor(kanalarz);
    }
}
