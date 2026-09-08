package com.example.projection.application;

import com.example.projection.support.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final JdbcClient jdbc;

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public CustomerResponse create(long segmentId, String name, String email, String status) {
        long id = jdbc.sql("SELECT customer_seq.NEXTVAL FROM dual")
                .query(Long.class)
                .single();

        jdbc.sql("""
                INSERT INTO customers (id, segment_id, name, email, status)
                VALUES (:id, :segmentId, :name, :email, :status)
                """)
                .param("id", id)
                .param("segmentId", segmentId)
                .param("name", name)
                .param("email", email)
                .param("status", status)
                .update();

        return new CustomerResponse(id, segmentId, name, email, status);
    }

    @Transactional
    public CustomerResponse update(long id, long segmentId, String name, String email, String status) {
        int changed = jdbc.sql("""
                UPDATE customers
                   SET segment_id = :segmentId,
                       name = :name,
                       email = :email,
                       status = :status,
                       updated_at = SYSTIMESTAMP
                 WHERE id = :id
                """)
                .param("id", id)
                .param("segmentId", segmentId)
                .param("name", name)
                .param("email", email)
                .param("status", status)
                .update();

        if (changed == 0) {
            throw new NotFoundException("Customer " + id + " was not found");
        }
        return new CustomerResponse(id, segmentId, name, email, status);
    }

    public record CustomerResponse(long id, long segmentId, String name, String email, String status) {
    }
}
