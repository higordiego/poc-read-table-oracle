package com.example.projection.api;

import java.util.List;
import java.util.Map;

import com.example.projection.application.CalculationCommandService;
import com.example.projection.application.CalculationCommandService.CreatedCalculation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calculations")
@Tag(name = "Calculations — write")
public class CalculationController {

    private final CalculationCommandService calculationService;

    public CalculationController(CalculationCommandService calculationService) {
        this.calculationService = calculationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um cálculo de preço", description = "Resolve tarifa por faixa de quantidade + regras por condição para cada item (PriceResolutionService), grava o cálculo e dispara os triggers que atualizam a projeção de leitura na mesma transação.")
    public CreatedCalculation create(@Valid @RequestBody CreateCalculationRequest request) {
        return calculationService.create(
                request.customerId(),
                request.priceTableId(),
                request.items().stream()
                        .map(item -> new CalculationCommandService.LineItemRequest(
                                item.priceTableItemId(), item.quantity(), item.ruleConditionValue()))
                        .toList());
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Atualiza o status de um cálculo", description = "DRAFT, CALCULATED, CONFIRMED ou CANCELLED — ver o fluxo de negócio em docs/fluxos-e-ciclo-de-vida.md.")
    public Map<String, Object> updateStatus(
            @PathVariable long id,
            @Valid @RequestBody UpdateCalculationStatusRequest request) {
        calculationService.updateStatus(id, request.status());
        return Map.of("calculationId", id, "status", request.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um cálculo", description = "ON DELETE CASCADE remove os line items; o trigger da projeção remove a linha correspondente na mesma transação.")
    public void delete(@PathVariable long id) {
        calculationService.delete(id);
    }

    public record CreateCalculationRequest(
            @NotNull @Min(1) Long customerId,
            @NotNull @Min(1) Long priceTableId,
            @NotEmpty List<@Valid LineItemRequest> items) {
    }

    public record LineItemRequest(
            @NotNull @Min(1) Long priceTableItemId,
            @Min(1) int quantity,
            String ruleConditionValue) {
    }

    public record UpdateCalculationStatusRequest(
            @NotBlank @Pattern(regexp = "DRAFT|CALCULATED|CONFIRMED|CANCELLED") String status) {
    }
}
