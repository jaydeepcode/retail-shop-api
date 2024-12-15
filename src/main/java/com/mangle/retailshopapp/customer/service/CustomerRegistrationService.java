package com.mangle.retailshopapp.customer.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.model.CustomerRegisterDto;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@Service
public class CustomerRegistrationService {
    @Autowired
    private CustomerDetailsRepository customerDetailsRepository;
    @Autowired
    private WaterPurchasePartyRepo waterPurchasePartyRepository; // Methods to handle business logic

    public void registerCustomer(CustomerRegisterDto customerRegisterDto) {

        CustomerDetails customerDetails = new CustomerDetails();
        customerDetails.setCustomerName(customerRegisterDto.getCustomerName());
        customerDetails.setContactNum(customerRegisterDto.getContactNum());
        customerDetails.setCreDttm(LocalDateTime.now());
        CustomerDetails customerDetail = customerDetailsRepository.save(customerDetails);

        WaterPurchaseParty waterPurchaseParty = new WaterPurchaseParty();
        waterPurchaseParty.setCustomerId(customerDetail.getCustId());
        waterPurchaseParty.setStorageType(customerRegisterDto.getStorageType());
        waterPurchaseParty.setCapacity(customerRegisterDto.getCapacity());
        waterPurchaseParty.setRegistrationDate(LocalDate.now());
        waterPurchaseParty.setAddress(customerRegisterDto.getAddress());
        waterPurchaseParty.setVehicleNumber(customerRegisterDto.getVehicleNumber());
        waterPurchaseParty.setNotes(customerRegisterDto.getNotes());
        waterPurchasePartyRepository.save(waterPurchaseParty);
    }

    public boolean isContactRegistered(String contactNum) {
        return customerDetailsRepository.existsByContactNum(contactNum);
    }
}
