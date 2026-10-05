package com.mangle.retailshopapp.customer.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.service.CustDetailsRechargeService;

@RestController
@RequestMapping("customers")
public class CustomerDetailsController {

    @Autowired 
    private CustDetailsRechargeService service;
    
    @GetMapping("/search")
    public List<CustomerDetails> searchCustomers(@RequestParam String customerName){
        return service.getCustomerByName(customerName);
    }

    @GetMapping("/customer-profile")
    public List<CustomerDetails> getCustomerProfile(@RequestParam String customerName){
        return service.getCustomerByName(customerName);
    }
}
