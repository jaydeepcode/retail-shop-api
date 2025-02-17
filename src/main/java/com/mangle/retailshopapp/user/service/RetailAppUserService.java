package com.mangle.retailshopapp.user.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.config.SecurityConstants;
import com.mangle.retailshopapp.user.model.RegisterUserVo;
import com.mangle.retailshopapp.user.model.RetailAppUser;
import com.mangle.retailshopapp.user.model.User;
import com.mangle.retailshopapp.user.repo.UserRepository;

@Service
public class RetailAppUserService implements UserDetailsService {

    UserRepository userRepository;
    PasswordEncoder passwordEncoder;

    @Autowired
    public void setUserRepository(UserRepository userRepository,PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
        User userDto = new User();
        userDto.setUsername(authenticationRequest.getUsername());
        userDto.setPassword(passwordEncoder.encode(authenticationRequest.getPassword()));
        userDto.setFirstName(authenticationRequest.getFirstName());
        userDto.setLastName(authenticationRequest.getLastName());
        
        List<String> roles = new ArrayList<>();
        if(authenticationRequest.getRole().equals(SecurityConstants.ADMIN_USER))
        {
            roles.addAll(Arrays.asList(SecurityConstants.ADMIN_USER, SecurityConstants.NORMAL_USER));
        }else {
            roles.add(SecurityConstants.NORMAL_USER);
        }
        userDto.setRoles(roles);
        return userRepository.save(userDto);
    }

}
