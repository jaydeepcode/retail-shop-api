package com.mangle.retailshopapp.water.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import com.mangle.retailshopapp.water.model.CustomerWithCreditPointsDTO;
import com.mangle.retailshopapp.water.model.PaymentMethod;
import com.mangle.retailshopapp.water.model.WaterPurchaseParty;
import com.mangle.retailshopapp.water.model.WaterPurchaseTransactionDTO;
import com.mangle.retailshopapp.water.repo.WaterPurchasePartyRepo;
import com.mangle.retailshopapp.water.model.TripStatus;
import com.mangle.retailshopapp.water.model.PumpUsed;
import com.mangle.retailshopapp.water.model.TripStateDto;
import com.mangle.retailshopapp.water.model.CreditBalanceDTO;
import com.mangle.retailshopapp.water.model.FlowRateDTO;
import com.mangle.retailshopapp.water.model.EstimatedTimeResponse;
import com.mangle.retailshopapp.water.event.TripAutoStopEvent;

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

    @Autowired
    private FlowRateService flowRateService;

    @Autowired
    private PumpAutoStopService pumpAutoStopService;

    private static final Logger logger = LoggerFactory.getLogger(WaterTransactionService.class);

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
        return customerProfile.getFirstName() + " " + customerProfile.getLastName(); // Or use getCustomerName() helper
    }

    public Optional<WaterPurchaseParty> getPartyContract(Integer customerId) {
        return waterPurchasePartyRepo.findPartyDetailsByCustomerId(customerId);
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
        
        // Calculate expected duration and schedule auto-stop
        Optional<WaterPurchaseParty> partyOpt = getPartyContract(customerId);
        if (partyOpt.isPresent()) {
            WaterPurchaseParty party = partyOpt.get();
            PumpUsed pumpUsedEnum = PumpUsed.valueOf(pumpUsed.toUpperCase());
            
            // Calculate expected duration using flow rate service
            FlowRateDTO flowRate = flowRateService.getFlowRateForEstimation(customerId, pumpUsedEnum);
            
            int expectedDuration = BigDecimal.valueOf(party.getCapacity())
                .multiply(flowRate.getSecPerLiter())
                .intValue();
            tripLedgerTxn.setExpectedDurationSec(expectedDuration);
            tripLedgerTxn.setAutoStopScheduled(true);
            customerTripLedgerRepository.save(tripLedgerTxn);
            
            // Schedule auto-stop check 5 seconds before expected completion
            LocalDateTime checkTime = tripLedgerTxn.getStartTime()
                    .plusSeconds(expectedDuration - 5);
            pumpAutoStopService.scheduleAutoStopCheck(tripLedgerTxn.getId(), checkTime);
            
            logger.info("Trip {} scheduled for auto-stop in {} seconds ({})", 
                tripLedgerTxn.getId(), expectedDuration, flowRate.getCalculationSource());
        }
        
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

    /**
     * Single source of truth for trip completion.
     * Used by: manual stop, auto-stop, admin actions
     */
    @Transactional
    public CustomerTripLedger completeTrip(
        CustomerTripLedger trip, 
        boolean isAutoStopped, 
        String stoppedBy
    ) {
        // Validation
        if (trip.getStatus() != TripStatus.FILLING) {
            throw new IllegalStateException(
                "Cannot complete trip in status: " + trip.getStatus()
            );
        }
        
        // Common completion logic
        trip.setEndTime(LocalDateTime.now());
        trip.setStatus(TripStatus.COMPLETED);
        trip.setAutoStopped(isAutoStopped);
        trip.setAutoStopScheduled(false);
        
        if (isAutoStopped) {
            trip.setAutoStopAttemptedAt(LocalDateTime.now());
        }
        
        CustomerTripLedger completed = customerTripLedgerRepository.save(trip);
        
        // Future hook for notifications, analytics, etc
        // onTripCompleted(completed, isAutoStopped, stoppedBy);
        // Cancel scheduled auto-stop if manually stopped
        if(!isAutoStopped) {
            pumpAutoStopService.cancelScheduledCheck(trip.getId());
        }
        return completed;
    }

    /**
     * Refactored updateTripTime - reuses common logic
     * Now idempotent - safe for concurrent calls
     */
    public List<CustomerTripLedger> updateTripTime(Integer customerId, Integer tripId) {
        CustomerTripLedger ledger = customerTripLedgerRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Trip not found"));

        if (ledger.getCustId() != customerId.intValue()) {
            throw new IllegalArgumentException("Trip does not belong to the specified customer");
        }

        // Idempotent: if already completed, just return
        if (ledger.getStatus() == TripStatus.COMPLETED) {
            logger.info("Trip {} already completed", tripId);
            return customerTripLedgerRepository.findLatestTransactionsAfterZeroBalance(customerId);
        }

        if (ledger.getStatus() != TripStatus.FILLING) {
            throw new IllegalStateException("Trip is not in FILLING status");
        }

        // Reuse common completion logic
        completeTrip(ledger, false, getCurrentUsername());
        
        logger.info("Manually stopped trip {} for customer {}", tripId, customerId);
        
        return customerTripLedgerRepository.findLatestTransactionsAfterZeroBalance(customerId);
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private TripStateDto mapToTripDTO(CustomerTripLedger ledger) {
        TripStateDto dto = new TripStateDto();
        dto.setTripId(ledger.getId());
        dto.setCustomerId(ledger.getCustId());
        dto.setTripStatus(ledger.getStatus());
        dto.setTripStartTime(ledger.getStartTime());
        dto.setPumpUsed(ledger.getPumpUsed());
        dto.setExpectedDurationSeconds(ledger.getExpectedDurationSec());
        return dto;
    }

    public CreditBalanceDTO getCreditBalance(Integer customerId) {
        // Get balance amount from latest transactions after zero balance
        List<CustomerTripLedger> unpaidCustomerTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);
        BigDecimal balanceAmount = unpaidCustomerTrips.size() > 0
                ? unpaidCustomerTrips.get(0).getBalanceAmount()
                : BigDecimal.ZERO;

        // Check if customer has an active trip (FILLING status)
        Optional<CustomerTripLedger> activeTrip = customerTripLedgerRepository
                .findFirstInProgressTrip(customerId);
        boolean hasActiveTrip = activeTrip.isPresent();

        // Get customer capacity from WaterPurchaseParty
        Optional<WaterPurchaseParty> partyContract = getPartyContract(customerId);
        if (!partyContract.isPresent()) {
            return new CreditBalanceDTO(balanceAmount, 0, hasActiveTrip);
        }

        // Calculate trip cost using existing getTripAmount() method
        BigDecimal tripCost = getTripAmount(partyContract.get());

        // Calculate credit points (pending trips count) as balanceAmount / tripCost
        int creditPoints = 0;
        if (tripCost.compareTo(BigDecimal.ZERO) > 0) {
            creditPoints = balanceAmount.divide(tripCost, 0, java.math.RoundingMode.DOWN).intValue();
        }

        return new CreditBalanceDTO(balanceAmount, creditPoints, hasActiveTrip);
    }

    public List<TripStateDto> getPendingTrips(Integer customerId) {
        // Get all unpaid trips after last zero balance
        List<CustomerTripLedger> unpaidTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);
        
        // Map to TripStateDto list using existing mapToTripDTO method
        return unpaidTrips.stream()
                .map(this::mapToTripDTO)
                .collect(Collectors.toList());
    }

    public List<CustomerWithCreditPointsDTO> getTopCustomersWithCreditPoints() {
        // Get top 6 recent customers (similar to getRecentCustomersWithPendingTrips)
        List<Map<String, Object>> results = customerDetailsRepository.findRecentCustomersWithPendingTrips();

        // For each customer, calculate credit points using getCreditBalance()
        List<CustomerWithCreditPointsDTO> dtos = results.stream().map(result -> {
            Integer custId = (Integer) result.get("cust_id");
            String customerName = (String) result.get("cust_name");
            String contactNum = (String) result.get("contact_num");

            // Get credit balance which includes credit points and hasActiveTrip
            CreditBalanceDTO creditBalance = getCreditBalance(custId);
            
            return new CustomerWithCreditPointsDTO(
                custId,
                customerName,
                contactNum,
                creditBalance.getCreditPoints(),
                creditBalance.getBalanceAmount(),
                creditBalance.getHasActiveTrip()
            );
        }).collect(Collectors.toList());

        return dtos;
    }

    /**
     * Get estimated time for a customer and pump type
     * Calculates: capacity * flowRate.secPerLiter
     * Logs calculationSource and sampleSize on server (not returned to client)
     */
    public EstimatedTimeResponse getEstimatedTime(Integer customerId, String pumpUsed) {
        Optional<WaterPurchaseParty> partyOpt = getPartyContract(customerId);
        
        if (partyOpt.isEmpty()) {
            logger.warn("No party contract found for customer {}", customerId);
            return new EstimatedTimeResponse(0);
        }
        
        WaterPurchaseParty party = partyOpt.get();
        PumpUsed pumpUsedEnum = PumpUsed.valueOf(pumpUsed.toUpperCase());
        
        // Get flow rate from FlowRateService (fast retrieval - never calculates)
        FlowRateDTO flowRate = flowRateService.getFlowRateForEstimation(customerId, pumpUsedEnum);
        
        // Calculate estimated time: capacity * flowRate.secPerLiter
        int estimatedTimeSeconds = BigDecimal.valueOf(party.getCapacity())
                .multiply(flowRate.getSecPerLiter())
                .intValue();
        
        // Log calculationSource and sampleSize on server (not returned to client)
        logger.info("Estimated time for customer {}: {} seconds (capacity: {}L, rate: {} sec/L, source: {}, sampleSize: {})",
                customerId, estimatedTimeSeconds, party.getCapacity(), 
                flowRate.getSecPerLiter(), flowRate.getCalculationSource(), flowRate.getSampleSize());
        
        return new EstimatedTimeResponse(estimatedTimeSeconds);
    }

    /**
     * Event listener for auto-stop events.
     * Handles trip completion when auto-stop is triggered.
     * This breaks the circular dependency with PumpAutoStopService.
     */
    @EventListener
    @Transactional
    public void handleTripAutoStopEvent(TripAutoStopEvent event) {
        CustomerTripLedger tripFromEvent = event.getTrip();
        try {
            // Refresh trip from database to ensure we have latest state
            CustomerTripLedger trip = customerTripLedgerRepository.findById(tripFromEvent.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Trip not found: " + tripFromEvent.getId()));
            
            // Complete the trip using common completion logic
            completeTrip(trip, true, "AUTO_STOP");
            
            logger.info("Successfully completed trip {} via auto-stop event", trip.getId());
        } catch (Exception e) {
            logger.error("Failed to complete trip {} from auto-stop event: {}", 
                    tripFromEvent.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Update trip amount for an active trip.
     * Only trips in FILLING status can be updated.
     */
    @Transactional
    public WaterPurchaseTransactionDTO updateTripAmount(Integer customerId, Integer tripId, Integer newAmount, String username) {
      
        // Get all unpaid trips after zero balance (already sorted DESC by tripDateTime)
        List<CustomerTripLedger> unpaidTrips = customerTripLedgerRepository
                .findLatestTransactionsAfterZeroBalance(customerId);

        // Find the trip to update in the list
        Optional<CustomerTripLedger> tripToUpdateOpt = unpaidTrips.stream()
                .filter(t -> t.getId() == tripId)
                .findFirst();

        if (tripToUpdateOpt.isEmpty()) {
            throw new IllegalArgumentException("Trip not found in unpaid trips list");
        }
        
        CustomerTripLedger tripToUpdate = tripToUpdateOpt.get();
        if (tripToUpdate.getCustId() != customerId.intValue()) {
            throw new IllegalArgumentException("Trip does not belong to the specified customer");
        }

        // Validate trip is in FILLING status
        if (tripToUpdate.getStatus() != TripStatus.FILLING) {
            throw new IllegalStateException("Cannot update trip amount. Trip is not in FILLING status. Current status: " + tripToUpdate.getStatus());
        }
        
        // Find index of this trip in the DESC sorted list
        int tripIndex = -1;
        for (int i = 0; i < unpaidTrips.size(); i++) {
            if (unpaidTrips.get(i).getId() == tripId) {
                tripIndex = i;
                break;
            }
        }

        // Get balance from the trip before this one (at index + 1 in DESC order)
        BigDecimal previousBalance = BigDecimal.ZERO;
        if (tripIndex + 1 < unpaidTrips.size()) {
            previousBalance = unpaidTrips.get(tripIndex + 1).getBalanceAmount();
        }

        // Update trip's creditAmount
        BigDecimal oldAmount = tripToUpdate.getCreditAmount();
        BigDecimal newAmountBigDecimal = BigDecimal.valueOf(newAmount);
        tripToUpdate.setCreditAmount(newAmountBigDecimal);
        
        // Set balanceAmount = previous balance + new amount
        tripToUpdate.setBalanceAmount(previousBalance.add(newAmountBigDecimal));
        
        // Save the trip
        customerTripLedgerRepository.save(tripToUpdate);

        // Refresh the list to get updated values
        unpaidTrips = customerTripLedgerRepository.findLatestTransactionsAfterZeroBalance(customerId);

        // Build and return response
        WaterPurchaseTransactionDTO response = new WaterPurchaseTransactionDTO();
        response.setPurchaseId(tripId);
        response.setRcCreditReqList(unpaidTrips);
        response.setBalanceAmount(unpaidTrips.size() > 0 
                ? unpaidTrips.get(0).getBalanceAmount() 
                : BigDecimal.ZERO);

        logger.info("Updated trip {} amount from {} to {} for customer {}", 
                tripId, oldAmount, newAmount, customerId);

        return response;
    }
}
