package com.example.projection.api;

import com.example.projection.application.CustomerService;
import com.example.projection.application.CustomerService.CustomerResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um cliente", description = "Vincula o cliente a um segmento (customer_segments), que determina qual tabela de preço se aplica a ele.")
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        return customerService.create(
                request.segmentId(), request.name(), request.email(), request.status());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza um cliente", description = "Nome/e-mail atualizados aqui propagam para a projeção de leitura via trg_customers_calc_projection.")
    public CustomerResponse update(
            @PathVariable long id,
            @Valid @RequestBody CustomerRequest request) {
        return customerService.update(
                id, request.segmentId(), request.name(), request.email(), request.status());
    }

    public record CustomerRequest(
            @NotNull @Min(1) Long segmentId,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }
}
