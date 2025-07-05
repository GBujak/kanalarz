package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.lang.NonNull;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Inherited
@Reflective
@Documented
public @interface Arg {

    @NonNull
    String value();
}
