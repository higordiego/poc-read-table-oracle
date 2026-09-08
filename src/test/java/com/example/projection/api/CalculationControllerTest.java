package com.example.projection.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.projection.application.CalculationCommandService;
import com.example.projection.application.CalculationCommandService.CreatedCalculation;
import com.example.projection.support.NotFoundException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CalculationController.class)
class CalculationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalculationCommandService calculationCommandService;

    @Test
    void create_withValidBody_returns201WithTheCreatedCalculation() throws Exception {
        given(calculationCommandService.create(anyLong(), anyLong(), any()))
                .willReturn(new CreatedCalculation(1000L, 2L, "CALCULATED", 1, new BigDecimal("330.24")));

        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":2,"priceTableId":2,"items":[
                                  {"priceTableItemId":4,"quantity":600,"ruleConditionValue":"PARTNER"}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calculationId", is(1000)))
                .andExpect(jsonPath("$.totalAmount", is(330.24)));
    }

    @Test
    void create_withEmptyItems_returns400() throws Exception {
        mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":2,"priceTableId":2,"items":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.items").exists());
    }

    @Test
    void updateStatus_withValidStatus_returns200WithTheNewStatus() throws Exception {
        willDoNothing().given(calculationCommandService).updateStatus(1000L, "CONFIRMED");

        mockMvc.perform(patch("/api/calculations/1000/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CONFIRMED")));
    }

    @Test
    void updateStatus_withUnknownStatusValue_returns400() throws Exception {
        mockMvc.perform(patch("/api/calculations/1000/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHIPPED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_whenCalculationDoesNotExist_returns404() throws Exception {
        doThrow(new NotFoundException("Calculation 999 was not found"))
                .when(calculationCommandService).delete(999L);

        mockMvc.perform(delete("/api/calculations/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Calculation 999 was not found")));
    }

    @Test
    void delete_whenCalculationExists_returns204() throws Exception {
        mockMvc.perform(delete("/api/calculations/1000"))
                .andExpect(status().isNoContent());
    }
}
