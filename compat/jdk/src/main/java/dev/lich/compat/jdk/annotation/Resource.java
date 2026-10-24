package dev.lich.compat.jdk.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Look-alike of JSR-250's {@code javax.annotation.Resource}, which left the JDK in Java 11 (JEP 320). The
 * compat pass renames every reference to it, so annotated classes and code that looks the annotation up
 * agree on this one. Mixin 0.7 does: its {@code LegacyTransformerHandle} asks every LaunchWrapper
 * transformer whether it carries {@code @Resource}, and fails to load the game without the class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Resource {
    String name() default "";

    String lookup() default "";

    Class<?> type() default Object.class;

    AuthenticationType authenticationType() default AuthenticationType.CONTAINER;

    boolean shareable() default true;

    String mappedName() default "";

    String description() default "";

    enum AuthenticationType {
        CONTAINER,
        APPLICATION
    }
}
