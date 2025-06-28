package com.gbujak.kanalarz.annotations;

import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@Inherited
@Reflective
public @interface Secret {
}
