package com.example.projection.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.projection.support.NotFoundException;
import com.example.projection.support.OracleIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerServiceIT extends OracleIntegrationTestBase {

    @Autowired
    private CustomerService customerService;

    @Test
    void update_changesTheStoredCustomerAndReturnsIt() {
        long id = customerService.create(1, "Original Name", "original@example.com", "ACTIVE").id();

        CustomerService.CustomerResponse updated =
                customerService.update(id, 2, "Updated Name", "updated@example.com", "INACTIVE");

        assertThat(updated.segmentId()).isEqualTo(2);
        assertThat(updated.name()).isEqualTo("Updated Name");
        assertThat(updated.status()).isEqualTo("INACTIVE");
    }

    @Test
    void update_whenCustomerDoesNotExist_throwsNotFoundException() {
        assertThrows(NotFoundException.class,
                () -> customerService.update(999_999, 1, "Ghost", "ghost@example.com", "ACTIVE"));
    }
}
