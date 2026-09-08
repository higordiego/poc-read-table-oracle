package com.example.projection.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void customerRequest_withAllFieldsValid_hasNoViolations() {
        var request = new CustomerController.CustomerRequest(1L, "Ada Lovelace", "ada@example.com", "ACTIVE");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void customerRequest_withMissingSegmentId_isRejected() {
        var request = new CustomerController.CustomerRequest(null, "Ada Lovelace", "ada@example.com", "ACTIVE");
        assertThat(propertyPaths(validator.validate(request))).contains("segmentId");
    }

    @Test
    void customerRequest_withInvalidEmail_isRejected() {
        var request = new CustomerController.CustomerRequest(1L, "Ada Lovelace", "not-an-email", "ACTIVE");
        assertThat(propertyPaths(validator.validate(request))).contains("email");
    }

    @Test
    void customerRequest_withStatusOutsideAllowedValues_isRejected() {
        var request = new CustomerController.CustomerRequest(1L, "Ada Lovelace", "ada@example.com", "DELETED");
        assertThat(propertyPaths(validator.validate(request))).contains("status");
    }

    @Test
    void createCalculationRequest_withEmptyItems_isRejected() {
        var request = new CalculationController.CreateCalculationRequest(1L, 1L, List.of());
        assertThat(propertyPaths(validator.validate(request))).contains("items");
    }

    @Test
    void createCalculationRequest_withValidSingleItem_hasNoViolations() {
        var item = new CalculationController.LineItemRequest(4L, 600, "PARTNER");
        var request = new CalculationController.CreateCalculationRequest(1L, 2L, List.of(item));
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void lineItemRequest_withZeroQuantity_isRejected() {
        var item = new CalculationController.LineItemRequest(4L, 0, null);
        assertThat(propertyPaths(validator.validate(item))).contains("quantity");
    }

    @Test
    void updateCalculationStatusRequest_withUnknownStatus_isRejected() {
        var request = new CalculationController.UpdateCalculationStatusRequest("SHIPPED");
        assertThat(propertyPaths(validator.validate(request))).contains("status");
    }

    @Test
    void updateCalculationStatusRequest_withEachDocumentedStatus_hasNoViolations() {
        for (String status : List.of("DRAFT", "CALCULATED", "CONFIRMED", "CANCELLED")) {
            var request = new CalculationController.UpdateCalculationStatusRequest(status);
            assertThat(validator.validate(request)).as("status=" + status).isEmpty();
        }
    }

    private static <T> Set<String> propertyPaths(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
