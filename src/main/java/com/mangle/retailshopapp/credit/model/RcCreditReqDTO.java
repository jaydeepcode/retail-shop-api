package com.mangle.retailshopapp.credit.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RcCreditReqDTO {
    private LocalDateTime credDttm;
    private BigDecimal credit;
    private BigDecimal debit;
    private BigDecimal balance;
}