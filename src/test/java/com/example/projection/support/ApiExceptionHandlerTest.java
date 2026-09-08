package com.example.projection.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void notFoundException_mapsTo404WithTheExceptionMessage() {
        ResponseEntity<ApiExceptionHandler.Problem> response =
                handler.notFound(new NotFoundException("Calculation 999 was not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().message()).isEqualTo("Calculation 999 was not found");
        assertThat(response.getBody().fields()).isEmpty();
    }

    @Test
    void illegalArgumentException_mapsTo400WithTheExceptionMessage() {
        ResponseEntity<ApiExceptionHandler.Problem> response =
                handler.badRequest(new IllegalArgumentException("mode must be 'projection' or 'transactional'"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("mode must be 'projection' or 'transactional'");
    }

    @Test
    void validationException_mapsTo400WithOneEntryPerInvalidField() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "segmentId", "must not be null"));
        bindingResult.addError(new FieldError("request", "email", "must be a well-formed email address"));
        MethodParameter parameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("validationException_mapsTo400WithOneEntryPerInvalidField"),
                -1);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiExceptionHandler.Problem> response = handler.validation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        assertThat(response.getBody().fields())
                .containsEntry("segmentId", "must not be null")
                .containsEntry("email", "must be a well-formed email address");
    }

    @Test
    void validationException_keepsOnlyTheFirstMessagePerField() throws NoSuchMethodException {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "status", "must not be blank"));
        bindingResult.addError(new FieldError("request", "status", "must match ACTIVE|INACTIVE"));
        MethodParameter parameter = new MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("validationException_keepsOnlyTheFirstMessagePerField"),
                -1);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiExceptionHandler.Problem> response = handler.validation(exception);

        assertThat(response.getBody().fields()).hasSize(1);
        assertThat(response.getBody().fields().get("status")).isEqualTo("must not be blank");
    }
}
