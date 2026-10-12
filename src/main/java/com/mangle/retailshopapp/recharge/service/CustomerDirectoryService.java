package com.mangle.retailshopapp.recharge.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangle.retailshopapp.customer.model.CustomerDetails;
import com.mangle.retailshopapp.customer.repo.CustomerDetailsRepository;
import com.mangle.retailshopapp.recharge.exception.CustomerNotFound;

/**
 * The customer identity boundary for the counter (design-api.md §5.1).
 *
 * <p>⚠ SEARCHES ALL OF {@code rs_cust_dtls}, NOT WATER CUSTOMERS ONLY. The
 * existing {@code CustomerDetailsRepository.findCustomersByName} filters on
 * {@code custId in (select w.customerId from WaterPurchaseParty w)}, which is
 * correct for the water module and wrong here: a recharge credit customer need
 * never have bought water. §5.1 states the requirement as "search over all of
 * {@code rs_cust_dtls} (not water-filtered), exclude ledger accounts".
 *
 * <p>⚠ AND IT EXCLUDES THE THREE LEDGER ACCOUNTS (log §2.6). Customers 269
 * (Janata Sahakari Bank), 409 (Hdfc Bank) and 323 (Bankof India4425) are
 * {@code rs_cust_dtls} rows because the legacy app had no concept of a UPI
 * receipt, so money that had gone to the bank was recorded against a "customer"
 * named after it. They hold ₹49,211 between them and must never carry a new
 * credit; the list is read from {@code rc_setting['credit.ledger.customers']}.
 *
 * <p>No search index, and §12's over-engineering register says so for exactly
 * this case: "no search engine or index for a 458-row TV table and a 506-row
 * customer table".
 */
@Service
public class CustomerDirectoryService {

    private final CustomerDetailsRepository customers;
    private final SettingService settings;

    public CustomerDirectoryService(CustomerDetailsRepository customers, SettingService settings) {
        this.customers = customers;
        this.settings = settings;
    }

    /**
     * Resolves an id to a customer who may carry credit.
     *
     * @throws CustomerNotFound 404 for a missing row AND for a ledger account.
     *         From the counter's point of view "that is not a customer" is the
     *         same answer either way, but the MESSAGE differs so the staff member
     *         is not left hunting for a row that is right there
     */
    @Transactional(readOnly = true)
    public CustomerDetails requireCreditable(int customerId) {
        if (settings.ledgerCustomerIds().contains(customerId)) {
            throw CustomerNotFound.ledgerAccount(customerId);
        }
        return customers.findByCustId(customerId)
                .orElseThrow(() -> new CustomerNotFound(customerId));
    }

    /**
     * Name-or-mobile search, ledger accounts removed.
     *
     * <p>Filtered in Java rather than in the query because the exclusion list is
     * three ids read from configuration, and pushing it into SQL would mean
     * either a bind list on every search or the list in the query text. Over a
     * 506-row table the difference is unmeasurable.
     */
    @Transactional(readOnly = true)
    public List<CustomerDetails> search(String query, int limit) {
        List<Integer> excluded = settings.ledgerCustomerIds();
        String needle = query == null ? "" : query.trim().toLowerCase();

        return customers.findAll().stream()
                .filter(c -> !excluded.contains(c.getCustId()))
                .filter(c -> matches(c, needle))
                .limit(limit)
                .toList();
    }

    /**
     * Creates a walk-in with name and mobile only (§3.2 endpoint #5).
     *
     * <p>{@code lastName} is NOT required, and that is measured rather than lax:
     * §6.2 records that 147 of 506 existing rows have a null, blank or "."
     * last name, and a walk-in may give one name. Stored as an empty string
     * because the column is {@code NOT NULL}.
     *
     * <p>Returns the EXISTING customer when the mobile is already on file, which
     * the controller turns into a 409 carrying that id (§3.2) — so the staff
     * member can use the person who is already there instead of creating a
     * duplicate they will later have to merge.
     */
    @Transactional
    public CustomerDetails createWalkIn(String firstName, String lastName, String mobile,
                                        LocalDateTime now) {
        CustomerDetails customer = new CustomerDetails();
        customer.setFirstName(firstName.trim());
        customer.setLastName(lastName == null ? "" : lastName.trim());
        customer.setContactNum(mobile.trim());
        customer.setStatusCode("ACTIVE");
        customer.setCreDttm(now);
        return customers.save(customer);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<CustomerDetails> findByMobile(String mobile) {
        return customers.findByContactNum(mobile == null ? null : mobile.trim());
    }

    private static boolean matches(CustomerDetails c, String needle) {
        if (needle.isEmpty()) {
            return false;
        }
        String name = ((c.getFirstName() == null ? "" : c.getFirstName()) + " "
                + (c.getLastName() == null ? "" : c.getLastName())).toLowerCase();
        String contact = c.getContactNum() == null ? "" : c.getContactNum();
        return name.contains(needle) || contact.contains(needle);
    }
}
