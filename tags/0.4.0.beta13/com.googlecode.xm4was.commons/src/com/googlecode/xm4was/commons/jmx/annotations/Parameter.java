package com.googlecode.xm4was.commons.jmx.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(value=ElementType.PARAMETER)
@Retention(value=RetentionPolicy.RUNTIME)
public @interface Parameter {
    String name();
    String description();
}
