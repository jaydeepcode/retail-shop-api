package com.mangle.retailshopapp.water.model;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class CustomerPayment {
    BigDecimal paymentAmount;
    PaymentMethod paymentMode;
}
