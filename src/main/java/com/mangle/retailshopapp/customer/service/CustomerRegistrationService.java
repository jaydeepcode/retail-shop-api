package com.mangle.retailshopapp.customer.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.mangle.retailshopapp.customer.exception.CustomerAlreadyRegisteredException;
import com.mangle.retailshopapp.customer.exception.CustomerAlreadyRegisteredForCheckException;
import com.mangle.retailshopapp.customer.exception.CustomerNotFoundException;
import com.mangle.retailshopapp.customer.exception.CustomerNotFoundForCheckException;
import com.mangle.retailshopapp.customer.exception.DuplicateUsernameException;
import com.mangle.retailshopapp.customer.exception.DuplicateVehicleNumberException;
import com.mangle.retailshopapp.customer.model.CustomerCheckResponse;
import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.model.CustomerRegisterDto;
import com.mangle.retailshopapp.customer.model.CustomerRegistrationRequest;
import com.mangle.retailshopapp.customer.model.RegistrationResponse;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;

@Service
public class CustomerRegistrationService {

    private final UserRepository userRepository;
    private final CustomerDetailsRepository customerDetailsRepository;
    private final WaterPurchasePartyRepo waterPurchasePartyRepo;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public CustomerRegistrationService(UserRepository userRepository,
                                       CustomerDetailsRepository customerDetailsRepository,
                                       WaterPurchasePartyRepo waterPurchasePartyRepo,
                                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.customerDetailsRepository = customerDetailsRepository;
        this.waterPurchasePartyRepo = waterPurchasePartyRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public CustomerCheckResponse checkMobileNumber(String mobile) {
        Optional<CustomerDetails> customerOpt = customerDetailsRepository.findByContactNum(mobile);

        if (customerOpt.isEmpty()) {
            throw new CustomerNotFoundForCheckException("Mobile number not found");
        }

        CustomerDetails customer = customerOpt.get();

        if (customer.getUserId() != null) {
            throw new CustomerAlreadyRegisteredForCheckException("This mobile number is already registered. Please login.");
        }

        Optional<WaterPurchaseParty> partyOpt = waterPurchasePartyRepo.findPartyDetailsByCustomerId(customer.getCustId());

        CustomerCheckResponse.CustomerCheckResponseBuilder builder = CustomerCheckResponse.builder()
                .success(true)
                .message("Customer found")
                .custId(customer.getCustId())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .contactNum(customer.getContactNum())
                .email(customer.getEmail())
                .hasUserAccount(false);

        partyOpt.ifPresent(party -> builder
                .storageType(party.getStorageType())
                .capacity(party.getCapacity())
                .vehicleNumber(party.getVehicleNumber())
                .address(party.getAddress())
                .location(party.getLocation()));

        return builder.build();
    }

    @Transactional
    public RegistrationResponse registerCustomer(CustomerRegistrationRequest request) {
        if (userRepository.findByUsername(request.getUsername()) != null) {
            throw new DuplicateUsernameException("Username already exists");
        }

        boolean isExistingCustomer = request.getCustomerId() != null;
        RegistrationResponse response;
        if (isExistingCustomer) {
            response = registerExistingCustomer(request);
        } else {
            response = registerNewCustomer(request);
        }

        return response;
    }

    private RegistrationResponse registerExistingCustomer(CustomerRegistrationRequest request) {
        CustomerDetails customer = customerDetailsRepository.findByCustId(request.getCustomerId())
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found"));

        if (customer.getUserId() != null) {
            throw new CustomerAlreadyRegisteredException("Customer already has a user account. Please login.");
        }

        WaterPurchaseParty existingParty = waterPurchasePartyRepo.findPartyDetailsByCustomerId(customer.getCustId())
                .orElse(null);

        validateVehicleNumber(request.getVehicleNumber(),
                existingParty != null ? existingParty.getId() : null);

        User user = createCustomerUser(request);

        customer.setUserId(user.getId());
        customer.setUpdDttm(LocalDateTime.now());
        customerDetailsRepository.save(customer);

        WaterPurchaseParty party = createOrUpdateWaterParty(customer, request, existingParty);
        waterPurchasePartyRepo.save(party);

        return RegistrationResponse.builder()
                .success(true)
                .message("Registration submitted successfully. Please wait for admin approval.")
                .build();
    }

    private RegistrationResponse registerNewCustomer(CustomerRegistrationRequest request) {
        validateVehicleNumber(request.getVehicleNumber(), null);

        User user = createCustomerUser(request);

        CustomerDetails customer = new CustomerDetails();
        customer.setFirstName(request.getFirstName());
        customer.setLastName(request.getLastName());
        customer.setContactNum(request.getContactNumber());
        customer.setUserId(user.getId());
        customer.setActive(false);
        customer.setCreDttm(LocalDateTime.now());
        customer = customerDetailsRepository.save(customer);

        WaterPurchaseParty party = createOrUpdateWaterParty(customer, request, null);
        waterPurchasePartyRepo.save(party);

        return RegistrationResponse.builder()
                .success(true)
                .message("Registration submitted successfully. Please wait for admin approval.")
                .build();
    }

    private User createCustomerUser(CustomerRegistrationRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRoles(List.of("ROLE_CUSTOMER"));
        user.setAccountStatus("PENDING");
        user.setCreatedDate(LocalDateTime.now());
        return userRepository.save(user);
    }

    private WaterPurchaseParty createOrUpdateWaterParty(CustomerDetails customer,
                                                        CustomerRegistrationRequest request,
                                                        WaterPurchaseParty existingParty) {
        WaterPurchaseParty party = existingParty != null ? existingParty : new WaterPurchaseParty();

        if (existingParty == null) {
            party.setCustomerId(customer.getCustId());
            party.setRegistrationDate(LocalDate.now());
            party.setActive(false);
        }

        party.setStorageType(request.getStorageType());
        party.setCapacity(request.getTankerCapacity());
        party.setVehicleNumber(request.getVehicleNumber());
        party.setAddress(request.getAddress());
        party.setContactNumber(request.getContactNumber());
        party.setLocation(request.getLocation());

        return party;
    }

    private void validateVehicleNumber(String vehicleNumber, Integer existingPartyId) {
        if (vehicleNumber == null || vehicleNumber.isBlank()) {
            return;
        }

        Optional<WaterPurchaseParty> existingPartyWithVehicle = waterPurchasePartyRepo.findByVehicleNumber(vehicleNumber);

        if (existingPartyWithVehicle.isPresent()) {
            int partyId = existingPartyWithVehicle.get().getId();
            if (existingPartyId == null || partyId != existingPartyId) {
                throw new DuplicateVehicleNumberException("Vehicle number already registered");
            }
        }
    }

    // Legacy endpoints (used by PurchasePartyController)
    public void registerCustomer(CustomerRegisterDto customerRegisterDto) {
        CustomerDetails customerDetail = saveCustomerDetails(customerRegisterDto, null);
        saveWaterPurchase(customerDetail, customerRegisterDto, null);
    }

    public void updateCustomer(CustomerRegisterDto customerRegisterDto, String custId) {
        CustomerDetails custDetails = saveCustomerDetails(customerRegisterDto, custId);
        saveWaterPurchase(custDetails, customerRegisterDto, custId);
    }

    public boolean isContactRegistered(String contactNum) {
        return customerDetailsRepository.existsByContactNum(contactNum);
    }

    private void saveWaterPurchase(CustomerDetails customerDetail,
                                   CustomerRegisterDto customerRegisterDto,
                                   String custId) {
        WaterPurchaseParty waterPurchaseParty;
        if (StringUtils.hasLength(custId)) {
            waterPurchaseParty = waterPurchasePartyRepo.findPartyDetailsByCustomerId(Integer.parseInt(custId))
                    .orElseThrow(() -> new RuntimeException("Customer not found"));
        } else {
            waterPurchaseParty = new WaterPurchaseParty();
        }

        waterPurchaseParty.setCustomerId(customerDetail.getCustId());
        waterPurchaseParty.setStorageType(customerRegisterDto.getStorageType());
        waterPurchaseParty.setCapacity(customerRegisterDto.getCapacity());
        waterPurchaseParty.setRegistrationDate(LocalDate.now());
        waterPurchaseParty.setAddress(customerRegisterDto.getAddress());
        waterPurchaseParty.setVehicleNumber(customerRegisterDto.getVehicleNumber());
        waterPurchaseParty.setNotes(customerRegisterDto.getNotes());
        waterPurchasePartyRepo.save(waterPurchaseParty);
    }

    private CustomerDetails saveCustomerDetails(CustomerRegisterDto customerRegisterDto, String custId) {
        CustomerDetails customerDetails = new CustomerDetails();
        if (StringUtils.hasLength(custId)) {
            customerDetails.setCustId(Integer.parseInt(custId));
        }
        if (StringUtils.hasText(customerRegisterDto.getFirstName()) && StringUtils.hasText(customerRegisterDto.getLastName())) {
            customerDetails.setFirstName(customerRegisterDto.getFirstName());
            customerDetails.setLastName(customerRegisterDto.getLastName());
        } else {
            customerDetails.setCustomerName(customerRegisterDto.getCustomerName());
        }
        customerDetails.setContactNum(customerRegisterDto.getContactNum());
        customerDetails.setCreDttm(LocalDateTime.now());
        customerDetails.setStatusCode("PENDING");
        return customerDetailsRepository.save(customerDetails);
    }
}
