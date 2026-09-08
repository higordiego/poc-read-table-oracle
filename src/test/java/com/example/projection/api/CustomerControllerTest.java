package com.example.projection.api;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.projection.application.CustomerService;
import com.example.projection.application.CustomerService.CustomerResponse;
import com.example.projection.support.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void create_withValidBody_returns201WithTheCreatedCustomer() throws Exception {
        given(customerService.create(2L, "Ada Lovelace", "ada@example.com", "ACTIVE"))
                .willReturn(new CustomerResponse(1000L, 2L, "Ada Lovelace", "ada@example.com", "ACTIVE"));

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"segmentId":2,"name":"Ada Lovelace","email":"ada@example.com","status":"ACTIVE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(1000)))
                .andExpect(jsonPath("$.name", is("Ada Lovelace")));
    }

    @Test
    void create_withInvalidEmail_returns400WithFieldError() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"segmentId":2,"name":"Ada Lovelace","email":"not-an-email","status":"ACTIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.email").exists());
    }

    @Test
    void update_whenCustomerDoesNotExist_returns404() throws Exception {
        given(customerService.update(anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .willThrow(new NotFoundException("Customer 999 was not found"));

        mockMvc.perform(put("/api/customers/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"segmentId":1,"name":"Ghost","email":"ghost@example.com","status":"ACTIVE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Customer 999 was not found")));
    }
}
