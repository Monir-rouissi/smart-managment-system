package com.smartmgmt.management.customer;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartmgmt.common.NotFoundException;
import com.smartmgmt.management.customer.dto.CustomerRequest;
import com.smartmgmt.management.customer.dto.CustomerResponse;

@Service
@Transactional
public class CustomerService {

    private final CustomerRepository repository;

    public CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(String q, Pageable pageable) {
        Specification<Customer> spec = CustomerSpecifications.matches(q);
        return repository.findAll(spec, pageable).map(CustomerResponse::from);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerResponse.from(findOrThrow(id));
    }

    public CustomerResponse create(CustomerRequest request) {
        Customer customer = new Customer();
        apply(customer, request);
        return CustomerResponse.from(repository.save(customer));
    }

    public CustomerResponse update(UUID id, CustomerRequest request) {
        Customer customer = findOrThrow(id);
        apply(customer, request);
        return CustomerResponse.from(repository.save(customer));
    }

    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("Customer", id);
        }
        repository.deleteById(id);
    }

    private Customer findOrThrow(UUID id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }

    private void apply(Customer customer, CustomerRequest request) {
        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setPhone(request.phone());
        customer.setCompany(request.company());
        customer.setNotes(request.notes());
    }
}
