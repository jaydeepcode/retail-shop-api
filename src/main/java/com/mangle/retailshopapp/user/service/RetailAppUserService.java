package com.mangle.retailshopapp.user.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.config.SecurityConstants;
import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.user.model.RegisterUserVo;
import com.mangle.retailshopapp.user.model.RetailAppUser;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

@Service
public class RetailAppUserService implements UserDetailsService {

    UserRepository userRepository;
    PasswordEncoder passwordEncoder;
    CustomerDetailsRepository customerDetailsRepository;

    @Autowired
    public void setUserRepository(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Autowired
    public void setCustomerDetailsRepository(CustomerDetailsRepository customerDetailsRepository) {
        this.customerDetailsRepository = customerDetailsRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }
        return new RetailAppUser(user);
    }

    public User saveUser(RegisterUserVo authenticationRequest) {
        // Check if username already exists
        User existingUser = userRepository.findByUsername(authenticationRequest.getUsername());
        if (existingUser != null) {
            throw new IllegalArgumentException("Username already exists. Please choose a different username.");
        }
        
        // Create and save User entity
        User userDto = new User();
        userDto.setUsername(authenticationRequest.getUsername());
        userDto.setPassword(passwordEncoder.encode(authenticationRequest.getPassword()));
        userDto.setCreatedDate(LocalDateTime.now());
        userDto.setAccountStatus("APPROVED");
        userDto.setApprovedBy(0);
        userDto.setApprovedDate(LocalDateTime.now());
        userDto.setLastLoginDate(LocalDateTime.now());
        userDto.setCreatedDate(LocalDateTime.now());
        userDto.setCreatedDate(LocalDateTime.now());
        
        List<String> roles = new ArrayList<>();
        if(authenticationRequest.getRole().equals(SecurityConstants.ADMIN_USER))
        {
            roles.addAll(Arrays.asList(SecurityConstants.ADMIN_USER, SecurityConstants.NORMAL_USER));
        }else {
            roles.add(SecurityConstants.NORMAL_USER);
        }
        userDto.setRoles(roles);
        User savedUser = userRepository.save(userDto);
        
        // Create and save CustomerDetails to store firstName and lastName
        if (authenticationRequest.getFirstName() != null && authenticationRequest.getLastName() != null) {
            CustomerDetails customerDetails = new CustomerDetails();
            customerDetails.setFirstName(authenticationRequest.getFirstName());
            customerDetails.setLastName(authenticationRequest.getLastName());
            customerDetails.setUserId(savedUser.getId());
            
            // Use contactNumber if provided, otherwise fallback to N/A
            String contactNum = authenticationRequest.getContactNumber() != null 
                ? authenticationRequest.getContactNumber() 
                : "N/A";
            customerDetails.setContactNum(contactNum);
            
            customerDetails.setCreDttm(LocalDateTime.now());
            customerDetails.setActive(true);
            customerDetails.setStatusCode("ACTIVE");
            
            // Set isAdmin based on role
            customerDetails.setAdmin(authenticationRequest.getRole().equals(SecurityConstants.ADMIN_USER));
            
            customerDetailsRepository.save(customerDetails);
        }
        
        return savedUser;
    }

}
