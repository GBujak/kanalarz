package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
@Reflective
public @interface StepsHolder {

    @NonNull
    String identifier();
}
