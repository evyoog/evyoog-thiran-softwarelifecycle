package com.vyoog.api.config;

import com.vyoog.identity.AccessRule;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * VYB-0906: the role rule a write endpoint needs, declared next to the endpoint and enforced by
 * {@link AccessInterceptor} before the request body is read or any argument is bound — so a caller
 * without the role gets a 403 whatever they send.
 *
 * <p>{@link #target()} says where the rule is checked: against the scope of the resource named in the
 * URL, or "anywhere" when the target is only in the body. A body-targeted endpoint that needs a
 * scoped check does it in the handler as well (see {@code RequirementController#create}).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresAccess {

    AccessRule value();

    Scope scope() default Scope.NONE;

    /** The path variable holding the id of the {@link #scope()} resource. */
    String idVar() default "id";

    enum Scope {
        /** Nothing to resolve: the rule is checked at platform level only ({@link AccessRule#PERSON}, {@link AccessRule#ADMIN}). */
        NONE,
        /** The caller must hold the role at some scope; the target is in the body. */
        ANYWHERE,
        /** The path variable is a requirement id: checked at that requirement's capability, application or product. */
        REQUIREMENT,
        /** The path variable is an acceptance-criterion id: checked at its requirement's scope. */
        CRITERION
    }
}
