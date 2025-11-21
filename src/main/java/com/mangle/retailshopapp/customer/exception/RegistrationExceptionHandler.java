package com.mangle.retailshopapp.customer.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.mangle.retailshopapp.customer.model.CustomerCheckResponse;
import com.mangle.retailshopapp.customer.model.RegistrationResponse;

@ControllerAdvice(assignableTypes = com.mangle.retailshopapp.customer.controller.CustomerRegistrationController.class)
public class RegistrationExceptionHandler {

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<RegistrationResponse> handleDuplicateUsername(DuplicateUsernameException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(DuplicateVehicleNumberException.class)
    public ResponseEntity<RegistrationResponse> handleDuplicateVehicle(DuplicateVehicleNumberException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(CustomerAlreadyRegisteredException.class)
    public ResponseEntity<RegistrationResponse> handleCustomerAlreadyRegistered(CustomerAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<RegistrationResponse> handleCustomerNotFound(CustomerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<RegistrationResponse> handleRegistrationException(RegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RegistrationResponse> handleGenericException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(RegistrationResponse.builder()
                        .success(false)
                        .message("Registration failed: " + ex.getMessage())
                        .build());
    }

    // Specific handler for check-mobile responses to preserve response structure
    @ExceptionHandler(CustomerAlreadyRegisteredForCheckException.class)
    public ResponseEntity<CustomerCheckResponse> handleCustomerAlreadyRegisteredForCheck(
            CustomerAlreadyRegisteredForCheckException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CustomerCheckResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .hasUserAccount(true)
                        .build());
    }

    @ExceptionHandler(CustomerNotFoundForCheckException.class)
    public ResponseEntity<CustomerCheckResponse> handleCustomerNotFoundForCheck(
            CustomerNotFoundForCheckException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(CustomerCheckResponse.builder()
                        .success(false)
                        .message(ex.getMessage())
                        .build());
    }
}




