package com.mangle.retailshopapp.user.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mangle.retailshopapp.user.model.User;

public interface UserRepository extends JpaRepository<User, Long> {
    
    User findByUsername(String username);
}
