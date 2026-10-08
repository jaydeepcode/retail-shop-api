package com.mangle.retailshopapp.accounting.repo;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.mangle.retailshopapp.accounting.model.AccAccount;

@Repository
public interface AccAccountRepository extends JpaRepository<AccAccount, String> {

    List<AccAccount> findByAccountCodeIn(Collection<String> accountCodes);
}
