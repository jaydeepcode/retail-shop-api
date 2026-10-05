package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;

import lombok.Data;

@Data
public class WaterPurchaseTransactionDTO {
    private Integer purchaseId;
    private String customerName;
    private WaterPurchaseParty waterPurchaseParty;
    private List<CustomerTripLedger> rcCreditReqList;
    private BigDecimal balanceAmount;
}
