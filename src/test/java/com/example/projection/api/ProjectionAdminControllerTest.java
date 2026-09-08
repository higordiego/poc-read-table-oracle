package com.example.projection.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.projection.application.FastLookupPlanService;
import com.example.projection.application.FastLookupPlanService.FastLookupPlan;
import com.example.projection.application.ProjectionService;
import com.example.projection.application.ProjectionService.MemoptimizedStatus;
import com.example.projection.application.ProjectionService.ProjectionStatus;
import com.example.projection.support.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProjectionAdminController.class)
class ProjectionAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectionService projectionService;
    @MockitoBean
    private FastLookupPlanService planService;

    private static ProjectionStatus readyStatus() {
        return new ProjectionStatus(
                "CALCULATION_READ_PROJECTION", "READY", 1, "A",
                null, null, null, 5L, 5L, 0L, null, null);
    }

    @Test
    void status_returnsWhatProjectionServiceReports() throws Exception {
        given(projectionService.getStatus()).willReturn(readyStatus());

        mockMvc.perform(get("/api/admin/projection/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("READY")))
                .andExpect(jsonPath("$.mismatchCount", is(0)));
    }

    @Test
    void memoptimized_returnsPoolAndTableStatus() throws Exception {
        given(projectionService.getMemoptimizedStatus())
                .willReturn(new MemoptimizedStatus(
                        true, 268435456L, "ENABLED",
                        "SELECT ... FROM CALCULATION_READ_PROJECTION WHERE CALCULATION_ID = :id",
                        "INDEX UNIQUE SCAN READ OPTIM"));

        mockMvc.perform(get("/api/admin/projection/memoptimized"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poolEnabled", is(true)))
                .andExpect(jsonPath("$.tableMemoptimizeRead", is("ENABLED")));
    }

    @Test
    void plan_whenCalculationExists_returnsThePlanLines() throws Exception {
        given(planService.inspect(1L)).willReturn(new FastLookupPlan(1L, false, List.of("INDEX UNIQUE SCAN")));

        mockMvc.perform(get("/api/admin/projection/plan/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fastLookupActive", is(false)));
    }

    @Test
    void plan_whenCalculationDoesNotExist_returns404() throws Exception {
        given(planService.inspect(999L)).willThrow(new NotFoundException("Calculation 999 was not found"));

        mockMvc.perform(get("/api/admin/projection/plan/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validate_returnsTheValidatedStatus() throws Exception {
        given(projectionService.validate()).willReturn(readyStatus());

        mockMvc.perform(post("/api/admin/projection/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mismatchCount", is(0)));
    }

    @Test
    void rebuild_forcesARebuildAndReturnsTheResultingStatus() throws Exception {
        given(projectionService.rebuild(true)).willReturn(readyStatus());

        mockMvc.perform(post("/api/admin/projection/rebuild"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSlot", is("A")));
    }
}
