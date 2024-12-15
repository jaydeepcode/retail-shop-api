package com.mangle.retailshopapp.credit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mangle.retailshopapp.credit.model.RcTxnDetail;
import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.credit.repo.RcTxnDetailRepository;
import com.mangle.retailshopapp.credit.repo.RcTxnHeaderRepository;

import java.util.List;

@Service
public class RcTxnHeaderService {

    @Autowired
    private RcTxnHeaderRepository rcTxnHeaderRepository;

    @Autowired
    private RcTxnDetailRepository rcTxnDetailRepository;

    public List<RcTxnHeader> getAllTransactions() {
        return rcTxnHeaderRepository.findAll();
    }

    public RcTxnHeader getTransactionById(int id) {
        return rcTxnHeaderRepository.findById(id).orElse(null);
    }

    public RcTxnHeader saveTransaction(RcTxnHeader transaction) {
        return rcTxnHeaderRepository.save(transaction);
    }

    public RcTxnDetail saveTransactionDetails(RcTxnDetail txnDetail){
        return rcTxnDetailRepository.save(txnDetail);
    }

    public void deleteTransaction(int id) {
        rcTxnHeaderRepository.deleteById(id);
    }
}
