package com.example.projection.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.projection.application.CalculationReadRepository;
import com.example.projection.application.CalculationReadRepository.CalculationReadResult;
import com.example.projection.application.CalculationReadRepository.CalculationSearchResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CalculationReadController.class)
class CalculationReadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalculationReadRepository repository;

    private static CalculationReadResult sampleResult(long id) {
        return new CalculationReadResult(
                id, 2L, "Ada Lovelace", "ada@example.com", "ENTERPRISE", "Enterprise",
                2L, "Enterprise Standard 2026", "CALCULATED", 2,
                new BigDecimal("330.24"), 1, new BigDecimal("5.76"),
                OffsetDateTime.parse("2026-09-04T21:17:33Z"), null);
    }

    @Test
    void findById_defaultsToProjectionMode() throws Exception {
        given(repository.findProjectionById(1000L)).willReturn(sampleResult(1000L));

        mockMvc.perform(get("/api/read/calculations/1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calculationId", is(1000)))
                .andExpect(jsonPath("$.totalAmount", is(330.24)));
    }

    @Test
    void findById_withTransactionalMode_readsFromTheView() throws Exception {
        given(repository.findTransactionalById(1000L)).willReturn(sampleResult(1000L));

        mockMvc.perform(get("/api/read/calculations/1000").param("mode", "transactional"))
                .andExpect(status().isOk());
    }

    @Test
    void findById_whenNotFound_returns404() throws Exception {
        given(repository.findProjectionById(999L)).willReturn(null);

        mockMvc.perform(get("/api/read/calculations/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void findById_withInvalidMode_returns400() throws Exception {
        given(repository.findProjectionById(1000L))
                .willThrow(new IllegalArgumentException("mode must be 'projection' or 'transactional'"));

        mockMvc.perform(get("/api/read/calculations/1000").param("mode", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_returnsTotalResultsAndNextCursor() throws Exception {
        given(repository.search("projection", 2L, null, null, 50, null))
                .willReturn(new CalculationSearchResult(1, List.of(sampleResult(1000L)), "next-cursor-token"));

        mockMvc.perform(get("/api/read/calculations").param("customerId", "2").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(1)))
                .andExpect(jsonPath("$.nextCursor", is("next-cursor-token")))
                .andExpect(jsonPath("$.results[0].calculationId", is(1000)));
    }
}
