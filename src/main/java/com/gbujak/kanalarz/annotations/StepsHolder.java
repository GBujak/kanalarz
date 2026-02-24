package com.gbujak.kanalarz.annotations;

import org.jspecify.annotations.NullMarked;
import org.springframework.aot.hint.annotation.Reflective;

import java.lang.annotation.*;

/**
 * Mark a Spring bean as a holder of Kanalarz step/rollback methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
@Reflective
@NullMarked
public @interface StepsHolder {

    /**
     * Globally unique identifier for this holder.
     * <br><b>If step history is persisted, changing this identifier may require a data migration.</b>
     * @return steps holder identifier
     */
    String value();
}
