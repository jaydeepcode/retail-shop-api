package com.mangle.retailshopapp.water.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.credit.model.CreditRequestType;
import com.mangle.retailshopapp.credit.model.RcCreditReq;
import com.mangle.retailshopapp.credit.model.RcTxnDetail;
import com.mangle.retailshopapp.credit.model.RcTxnHeader;
import com.mangle.retailshopapp.credit.service.RcCreditReqService;
import com.mangle.retailshopapp.credit.service.RcTxnHeaderService;
import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.model.CustomerTripLedger;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.customer.repo.CustomerTripLedgerRepository;
import com.mangle.retailshopapp.customer.service.CustDetailsRechargeService;
import com.mangle.retailshopapp.water.model.CustomerPayment;
import com.mangle.retailshopapp.water.model.CustomerPendingTripsDTO;
import com.mangle.retailshopapp.water.model.PaymentMethod;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.model.WaterPurchaseTransactionDTO;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;
import com.mangle.retailshopapp.water.model.TripStatus;
import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripStateDto;

@Service
public class WaterTransactionService {

    @Autowired
    private WaterPurchasePartyRepo waterPurchasePartyRepo;

    @Autowired
    private RcCreditReqService creditReqService;

    @Autowired
    private RcTxnHeaderService txnHeaderService;

    @Autowired
    private CustomerTripLedgerRepository customerTripLedgerRepository;

    @Autowired
    private CustomerDetailsRepository customerDetailsRepository;
    @Autowired
    private CustDetailsRechargeService customerDetailsService;

    public WaterPurchaseTransactionDTO getCustomerTransactions(Integer customerId) {
        WaterPurchaseTransactionDTO waterPurchaseTransactionDTO = new WaterPurchaseTransactionDTO();
        Optional<WaterPurchaseParty> waterPurchaseParty = getPartyContract(customerId);
        if (waterPurchaseParty.isPresent()) {
            waterPurchaseTransactionDTO.setWaterPurchaseParty(waterPurchaseParty.get());
        }
        waterPurchaseTransactionDTO.setCustomerName(this.getCustomerName(customerId));
        List<CustomerTripLedger> unpaidCustomerTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);
        waterPurchaseTransactionDTO.setRcCreditReqList(unpaidCustomerTrips);
        waterPurchaseTransactionDTO.setBalanceAmount(
                unpaidCustomerTrips.size() > 0 ? unpaidCustomerTrips.get(0).getBalanceAmount() : BigDecimal.ZERO);

        return waterPurchaseTransactionDTO;
    }

    private String getCustomerName(Integer customerId) {
        CustomerDetails customerProfile = this.customerDetailsRepository.getReferenceById(Long.valueOf(customerId));
        return customerProfile.getCustomerName();
    }

    public Optional<WaterPurchaseParty> getPartyContract(Integer customerId) {
        return Optional.ofNullable(waterPurchasePartyRepo.findPartyDetailsByCustomerId(customerId));
    }

    @Transactional
    public WaterPurchaseTransactionDTO generateAndSaveTrip(Integer customerId, Integer tripAmount, String pumpUsed,
            String username) {

        WaterPurchaseTransactionDTO purchaseTransactionDTO = new WaterPurchaseTransactionDTO();
        List<CustomerTripLedger> unpaidCustomerTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);
        BigDecimal latestBalanceAmount = unpaidCustomerTrips.size() > 0
                ? unpaidCustomerTrips.get(0).getBalanceAmount()
                : BigDecimal.ZERO;

        CustomerTripLedger tripLedgerTxn = generateCreditTransction(customerId, BigDecimal.valueOf(tripAmount),
                latestBalanceAmount, username, pumpUsed);
        purchaseTransactionDTO.setPurchaseId(tripLedgerTxn.getId());

        unpaidCustomerTrips.add(tripLedgerTxn);

        purchaseTransactionDTO.setRcCreditReqList(unpaidCustomerTrips.stream()
                .sorted(Comparator.comparing(CustomerTripLedger::getTripDateTime).reversed())
                .collect(Collectors.toList()));
        purchaseTransactionDTO.setBalanceAmount(tripLedgerTxn.getBalanceAmount());
        return purchaseTransactionDTO;
    }

    private RcCreditReq populateCreditDebitRecord(Integer customerId, CreditRequestType reqType, BigDecimal amount) {
        RcCreditReq creditReq = new RcCreditReq();
        creditReq.setCredDttm(LocalDateTime.now());
        creditReq.setCustId(customerId);
        creditReq.setReqAmt(amount);
        creditReq.setReqType(reqType.toString());

        return creditReqService.saveCreditRequest(creditReq);
    }

    private CustomerTripLedger generateCreditTransction(Integer customerId, BigDecimal tripAmount,
            BigDecimal latestBalanceAmount, String username, String pumpUsed) {

        CustomerTripLedger ledger = new CustomerTripLedger();
        ledger.setCustId(customerId);
        ledger.setTripDateTime(LocalDateTime.now());
        ledger.setCreditAmount(tripAmount);
        ledger.setDepositAmount(BigDecimal.ZERO);
        ledger.setBalanceAmount(tripAmount.add(latestBalanceAmount));
        ledger.setPumpUsed(PumpUsed.valueOf(pumpUsed.toUpperCase()));
        ledger.setStatus(TripStatus.FILLING);
        ledger.setStartTime(LocalDateTime.now());
        ledger.setCredBy(username);
        return customerTripLedgerRepository.save(ledger);
    }

    private CustomerTripLedger generateDepositTransction(Integer customerId, BigDecimal depositAmount,
            BigDecimal latestBalanceAmount, String username) {

        CustomerTripLedger ledger = new CustomerTripLedger();
        ledger.setCustId(customerId);
        ledger.setTripDateTime(LocalDateTime.now());
        ledger.setCreditAmount(BigDecimal.ZERO);
        ledger.setDepositAmount(depositAmount);
        ledger.setBalanceAmount(latestBalanceAmount.subtract(depositAmount));
        ledger.setCredBy(username);

        return customerTripLedgerRepository.save(ledger);
    }

    private BigDecimal getTripAmount(WaterPurchaseParty contract) {
        int tripCount = getTripUnit(contract.getCapacity());
        return new BigDecimal(tripCount * 40); // later keep this amount configurable
    }

    private int getTripUnit(int capacity) {
        if (capacity <= 500) {
            return 1;
        } // Calculate unit by dividing the capacity by 500 and rounding up
        return (capacity + 499) / 500;
    }

    public WaterPurchaseTransactionDTO persistPayment(Integer customerId, CustomerPayment customerPayment,
            String username) {
        // ***** Next two lines performs payment and clears trips */
        List<CustomerTripLedger> unpaidCustomerTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);
        BigDecimal latestBalanceAmount = unpaidCustomerTrips.size() > 0 ? unpaidCustomerTrips.get(0).getBalanceAmount()
                : BigDecimal.ZERO;
        generateDepositTransction(customerId, customerPayment.getPaymentAmount(), latestBalanceAmount, username);
        RcTxnHeader paidTransaction = generateRechargeCashTransaction(customerPayment);

        if (customerPayment.getPaymentMode() == PaymentMethod.UPI) {
            RcCreditReq bankTransferAck = generateRechargeUPIPaidTransaction(customerPayment.getPaymentAmount());
            RcTxnDetail rcBankTransfer = new RcTxnDetail();
            rcBankTransfer.setTxnFlg("CRDT");
            rcBankTransfer.setAmount(customerPayment.getPaymentAmount().negate());
            rcBankTransfer.setRcTxnHeader(paidTransaction);
            rcBankTransfer.setReferenceNo(String.valueOf(bankTransferAck.getId()));
            rcBankTransfer.setRefCompany(" ");
            txnHeaderService.saveTransactionDetails(rcBankTransfer);
        }
        WaterPurchaseTransactionDTO waterPurchaseTransactionDTO = new WaterPurchaseTransactionDTO();
        if (latestBalanceAmount.compareTo(customerPayment.getPaymentAmount()) == 0) {
            waterPurchaseTransactionDTO.setRcCreditReqList(new ArrayList<>());
            waterPurchaseTransactionDTO.setBalanceAmount(BigDecimal.ZERO);
        } else {
            unpaidCustomerTrips = customerTripLedgerRepository.findLatestTransactionsAfterZeroBalance(customerId);
            waterPurchaseTransactionDTO.setBalanceAmount(
                    unpaidCustomerTrips.size() > 0 ? unpaidCustomerTrips.get(0).getBalanceAmount() : BigDecimal.ZERO);
            waterPurchaseTransactionDTO.setRcCreditReqList(unpaidCustomerTrips);
        }
        return waterPurchaseTransactionDTO;
    }

    private RcCreditReq generateRechargeUPIPaidTransaction(BigDecimal paymentAmount) {
        CustomerDetails bankAccount = customerDetailsService.getBankByName("janata sahakari");

        return populateCreditDebitRecord(bankAccount.getCustId(), CreditRequestType.DBT, paymentAmount.negate());

    }

    private RcTxnHeader generateRechargeCashTransaction(CustomerPayment customerPayment) {
        RcTxnHeader header = new RcTxnHeader();
        header.setTxnDttm(LocalDateTime.now());
        header.setTxnTotalAmt(customerPayment.getPaymentAmount());
        if (customerPayment.getPaymentMode() == PaymentMethod.CASH) {
            header.setAmtTndred(customerPayment.getPaymentAmount());
            header.setAmtReturned(BigDecimal.ZERO);
        } else if (customerPayment.getPaymentMode() == PaymentMethod.UPI) {
            header.setAmtTndred(BigDecimal.ZERO);
            header.setAmtReturned(customerPayment.getPaymentAmount().negate());
        }

        header.setTxnDetails(new ArrayList<>());

        RcTxnDetail rcTxnDetail = new RcTxnDetail();
        rcTxnDetail.setTxnFlg("BNW");
        rcTxnDetail.setAmount(customerPayment.getPaymentAmount());
        rcTxnDetail.setRcTxnHeader(header);
        rcTxnDetail.setReferenceNo(" ");
        rcTxnDetail.setRefCompany(" ");

        header.getTxnDetails().add(rcTxnDetail);
        return txnHeaderService.saveTransaction(header);
    }

    public BigDecimal getCalculatedAmount(String custId) {
        Optional<WaterPurchaseParty> contract = getPartyContract(Integer.valueOf(custId));
        if (contract.isPresent()) {
            return this.getTripAmount(contract.get());
        }
        return null;
    }

    public List<CustomerPendingTripsDTO> getRecentCustomersWithPendingTrips() {
        List<Map<String, Object>> results = customerDetailsRepository.findRecentCustomersWithPendingTrips();

        List<CustomerPendingTripsDTO> dtos = results.stream().map(result -> {
            Integer custId = (Integer) result.get("cust_id");
            String customerName = (String) result.get("cust_name");
            String contactNum = (String) result.get("contact_num");
            int tripCount = result.get("TransactionCount") != null
                    ? ((Number) result.get("TransactionCount")).intValue()
                    : 0;
            BigDecimal maxBalanceAmount = result.get("MaxBalanceAmount") != null
                    ? BigDecimal.valueOf(((Number) result.get("MaxBalanceAmount")).doubleValue())
                    : BigDecimal.ZERO;
            return new CustomerPendingTripsDTO(custId, customerName, contactNum, tripCount, maxBalanceAmount);
        }).collect(Collectors.toList());

        return dtos;
    }

    public Page<CustomerTripLedger> getPaginatedTransactions(int custId, Pageable pageable) {
        return customerTripLedgerRepository.findByCustId(custId, pageable);
    }

    public TripStateDto getInProgressTrip(Long customerId) {
        return customerTripLedgerRepository
                .findFirstInProgressTrip(customerId.intValue())
                .map(this::mapToTripDTO)
                .orElse(null);
    }

    public List<CustomerTripLedger> updateTripTime(Integer customerId, Integer tripId) {
        CustomerTripLedger ledger = customerTripLedgerRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found"));

        if (ledger.getCustId() != customerId.intValue()) {
            throw new IllegalArgumentException("Trip does not belong to the specified customer");
        }

        if (ledger.getStatus() != TripStatus.FILLING) {
            throw new IllegalStateException("Trip is not in FILLING status");
        }

        ledger.setEndTime(LocalDateTime.now());
        ledger.setStatus(TripStatus.COMPLETED);
        customerTripLedgerRepository.save(ledger);
        return customerTripLedgerRepository.findLatestTransactionsAfterZeroBalance(customerId);

    }

    private TripStateDto mapToTripDTO(CustomerTripLedger ledger) {
        TripStateDto dto = new TripStateDto();
        dto.setTripId(ledger.getId());
        dto.setCustomerId(ledger.getCustId());
        dto.setTripStatus(ledger.getStatus());
        dto.setTripStartTime(ledger.getStartTime());
        dto.setPumpUsed(ledger.getPumpUsed());
        return dto;
    }
}
