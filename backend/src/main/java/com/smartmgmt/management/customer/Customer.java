package com.smartmgmt.management.customer;

import com.smartmgmt.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "customers")
public class Customer extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String email;

    private String phone;

    private String company;

    @Column(columnDefinition = "text")
    private String notes;
}
