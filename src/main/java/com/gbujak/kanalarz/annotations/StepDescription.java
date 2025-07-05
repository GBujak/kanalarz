package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited
@Reflective
public @interface StepDescription {

    @NonNull
    String value();
}
