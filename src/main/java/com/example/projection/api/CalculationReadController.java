package com.example.projection.api;

import com.example.projection.application.CalculationReadRepository;
import com.example.projection.application.CalculationReadRepository.CalculationReadResult;
import com.example.projection.application.CalculationReadRepository.CalculationSearchResult;
import com.example.projection.support.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/read/calculations")
@Tag(name = "Calculations — read")
public class CalculationReadController {

    private final CalculationReadRepository repository;

    public CalculationReadController(CalculationReadRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{calculationId}")
    @Operation(summary = "Leitura por PK", description = "mode=projection (padrão) é o único caminho elegível a Memoptimized Rowstore Fast Lookup: um único predicado de igualdade pela chave primária. mode=transactional lê o mesmo dado direto de calculation_transactional_view, para comparação.")
    public CalculationReadResult findById(
            @PathVariable long calculationId,
            @Parameter(description = "projection ou transactional") @RequestParam(defaultValue = "projection") String mode) {
        CalculationReadResult result = switch (mode.toLowerCase()) {
            case "projection" -> repository.findProjectionById(calculationId);
            case "transactional" -> repository.findTransactionalById(calculationId);
            default -> throw new IllegalArgumentException(
                    "mode must be 'projection' or 'transactional'");
        };
        if (result == null) {
            throw new NotFoundException("Calculation " + calculationId + " was not found");
        }
        return result;
    }

    @GetMapping
    @Operation(summary = "Busca filtrada por keyset", description = "Nunca elegível a Fast Lookup. Duas queries: COUNT(*) para o total e um SELECT paginado por keyset (requested_at, calculation_id) — não OFFSET, para não degradar em páginas fundas sobre milhões de linhas. Use o nextCursor da resposta anterior no parâmetro cursor para avançar.")
    public CalculationSearchResult search(
            @RequestParam(defaultValue = "projection") String mode,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String cursor) {
        return repository.search(mode, customerId, status, email, limit, cursor);
    }
}
