package com.idvp.plugins.postprocess.aop.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Oleg Zinoviev
 * @since 26.02.18.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.CLASS)
public @interface VisibleForAop {
    boolean removeFinal() default true;

    boolean transformPackageToProtected() default false;

    boolean inherited() default false;
}
