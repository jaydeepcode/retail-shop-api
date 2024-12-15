package com.mangle.retailshopapp.customer.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;

@Service
public class CustDetailsRechargeService {

    @Autowired
    private CustomerDetailsRepository repository;

    public List<CustomerDetails> getAllCustomers() {
        return repository.findAll();
    }

    public CustomerDetails saveCustomerDetails(CustomerDetails recharge) {
        return repository.save(recharge);
    }

    public List<CustomerDetails> getCustomerByName(String name) {
        return repository.findCustomersByName(name);
    }

    public CustomerDetails getBankByName(String name) {
        return repository.findBankByName(name);
    }
}
