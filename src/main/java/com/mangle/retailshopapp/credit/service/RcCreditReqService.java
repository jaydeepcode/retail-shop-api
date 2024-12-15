package com.mangle.retailshopapp.credit.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.model.RcCreditReqDTO;
import com.mangle.retailshopapp.credit.repo.RcCreditReqRepository;

@Service
public class RcCreditReqService {
    @Autowired
    private RcCreditReqRepository repository;

    public List<RcCreditReqDTO> getTransactionHistory(int custId) {
        return repository.findTransactionHistory(custId);
    }

    public RcCreditReq saveCreditRequest(RcCreditReq recharge) {
        return repository.save(recharge);
    }
}
