package com.example.projection.api;

import com.example.projection.application.ProjectionService;
import com.example.projection.application.FastLookupPlanService;
import com.example.projection.application.FastLookupPlanService.FastLookupPlan;
import com.example.projection.application.ProjectionService.MemoptimizedStatus;
import com.example.projection.application.ProjectionService.ProjectionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/projection")
@Tag(name = "Projection Admin")
public class ProjectionAdminController {

    private final ProjectionService projectionService;
    private final FastLookupPlanService planService;

    public ProjectionAdminController(
            ProjectionService projectionService,
            FastLookupPlanService planService) {
        this.projectionService = projectionService;
        this.planService = planService;
    }

    @GetMapping("/status")
    @Operation(summary = "Status da projeção", description = "activeSlot (A/B), contagens de linhas fonte/projeção, mismatchCount, e o status do rebuild (READY/BUILDING/NEEDS_REBUILD).")
    public ProjectionStatus status() {
        return projectionService.getStatus();
    }

    @GetMapping("/memoptimized")
    @Operation(summary = "Status do Memoptimize Pool", description = "Confirma se o pool está alocado (poolEnabled) e se a tabela ativa tem MEMOPTIMIZE_READ=ENABLED -- não confirma, por si só, que o Fast Lookup está sendo usado (ver /plan/{id}).")
    public MemoptimizedStatus memoptimized() {
        return projectionService.getMemoptimizedStatus();
    }

    @GetMapping("/plan/{calculationId}")
    @Operation(summary = "Plano de execução real de uma leitura por PK", description = "Lê DBMS_XPLAN.DISPLAY_CURSOR do cursor que acabou de executar e procura a operação READ OPTIM -- a única prova válida de que o Fast Lookup está ativo, ver docs/testes.md Seção 2.")
    public FastLookupPlan plan(@org.springframework.web.bind.annotation.PathVariable long calculationId) {
        return planService.inspect(calculationId);
    }

    @PostMapping("/validate")
    @Operation(summary = "Valida a projeção contra a fonte, sem reconstruir", description = "MINUS bidirecional entre calculation_transactional_view e calculation_read_projection. Não trava nenhuma tabela.")
    public ProjectionStatus validate() {
        return projectionService.validate();
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Reconstrói a projeção (blue/green)", description = "build_inactive + converge_inactive em loop + cutover. Só a etapa cutover pausa escritores, e só na tabela de projeção -- ver docs/architecture.md.")
    public ProjectionStatus rebuild() {
        return projectionService.rebuild(true);
    }
}
