package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;
import java.util.List;

import com.mangle.retailshopapp.customer.model.CustomerTripLedger;

import lombok.Data;

@Data
public class WaterPurchaseTransactionDTO {
    String customerName;
    WaterPurchaseParty waterPurchaseParty;
    List<CustomerTripLedger> rcCreditReqList;
    BigDecimal balanceAmount;
}
