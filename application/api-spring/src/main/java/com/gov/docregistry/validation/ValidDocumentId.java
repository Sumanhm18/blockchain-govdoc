package com.gov.docregistry.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = DocumentIdValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidDocumentId {
    String message() default "Invalid document ID format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
