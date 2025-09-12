package com.gov.docregistry.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

public class DocumentIdValidator implements ConstraintValidator<ValidDocumentId, String> {
    
    private static final Pattern DOCUMENT_ID_PATTERN = Pattern.compile("^[A-Z0-9-]{3,50}$");
    
    @Override
    public boolean isValid(String documentId, ConstraintValidatorContext context) {
        if (documentId == null || documentId.trim().isEmpty()) {
            return false;
        }
        return DOCUMENT_ID_PATTERN.matcher(documentId).matches();
    }
}
