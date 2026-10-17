package com.mangle.retailshopapp.recharge.repo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The credit book's queries, against real MySQL (#14, #15).
 *
 * <p>Written for review finding 8, which is a query bug and so cannot be caught
 * anywhere but here. The running balance orders by
 * {@code (CRED_DTTM, CRE_REQ_ID)} while the oldest-open subquery compared the
 * timestamp alone — and V28 dropped {@code CRED_DTTM_UNIQUE}, so two rows can
 * now share a second. Every fixture below writes its timestamps explicitly for
 * that reason.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("The credit book")
class CreditBookRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("recharge")
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("db/legacy-baseline.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "8");
        registry.add("spring.flyway.target", () -> "29");
        registry.add("spring.flyway.out-of-order", () -> "false");
    }

    /** The three bank pseudo-customers §2.6 excludes. */
    private static final List<Integer> LEDGER_ACCOUNTS = List.of(269, 323, 409);

    @Autowired private CreditBookRepository creditBook;
    @Autowired private EntityManager em;

    private LocalDateTime base;

    @BeforeEach
    void anchorTheClock() {
        // Relative, never a literal date: implementation-plan.md §0.6's fourth
        // defect was a test pinned to a date that broke the next day.
        base = LocalDateTime.now().minusDays(40).withNano(0);
    }

    // ------------------------------------------------------------------
    // Review finding 8
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a receipt and a charge in the SAME SECOND still date the open debt")
    void sameSecondReceiptAndCharge() {
        // Finding 8, exactly. The tab is cleared and reopened within one second:
        //
        //   t+0s  DBT  -500   owes 500
        //   t+5d  DPAM +500   clear          <- zero point
        //   t+5d  DBT  -200   owes 200 again <- SAME SECOND as the receipt
        //
        // Comparing timestamps alone, the third row is not > the second, so it
        // is excluded, oldest_open comes back NULL and the 30-day badge goes
        // dark on a customer who is 35 days into a new debt. Comparing the pair
        // (CRED_DTTM, CRE_REQ_ID) — the same key the running balance orders by —
        // includes it.
        int customerId = insertCustomer("Same", "Second", "9000000101");
        LocalDateTime cleared = base.plusDays(5);
        insertRow(customerId, base, "DBT", "-500.00");
        insertRow(customerId, cleared, "DPAM", "500.00");
        insertRow(customerId, cleared, "DBT", "-200.00");

        Object[] position = creditBook.positionOf(customerId).get(0);
        assertEquals(0, new BigDecimal("-200.00").compareTo((BigDecimal) position[0]),
                "the tally is right either way — that is what makes the bug quiet");
        assertNotNull(position[1],
                "and the open debt is dated, rather than NULL because of a tied second");
        assertEquals(cleared, ((java.sql.Timestamp) position[1]).toLocalDateTime(),
                "it began when the new charge landed");

        Object[] row = onlyRow(customerId);
        assertNotNull(row[6], "the credit book must date it too, or the badge goes dark");
        assertEquals(cleared, ((java.sql.Timestamp) row[6]).toLocalDateTime());
    }

    @Test
    @DisplayName("a tab that was never clear dates from its very first charge")
    void neverCleared() {
        // The other end of the same subquery: there is no zero point at all, so
        // the NULL branch has to let every row through. A LIMIT 1 that returns
        // no rows must not silently drop the customer's whole history.
        int customerId = insertCustomer("Never", "Clear", "9000000102");
        insertRow(customerId, base, "DBT", "-300.00");
        insertRow(customerId, base.plusDays(10), "DBT", "-150.00");

        Object[] position = creditBook.positionOf(customerId).get(0);
        assertEquals(0, new BigDecimal("-450.00").compareTo((BigDecimal) position[0]));
        assertEquals(base, ((java.sql.Timestamp) position[1]).toLocalDateTime(),
                "the debt is as old as the first charge");
    }

    @Test
    @DisplayName("a part payment does not reset the ageing clock")
    void partPaymentKeepsTheAge() {
        // FIFO is what "oldest debt" means with a single-sided tally: the
        // balance never reached zero, so the clock still runs from the first
        // charge. If this reset, a customer could be kept perpetually un-warned
        // by paying a rupee a month.
        int customerId = insertCustomer("Part", "Payer", "9000000103");
        insertRow(customerId, base, "DBT", "-500.00");
        insertRow(customerId, base.plusDays(20), "DPAM", "100.00");

        Object[] position = creditBook.positionOf(customerId).get(0);
        assertEquals(base, ((java.sql.Timestamp) position[1]).toLocalDateTime());
    }

    @Test
    @DisplayName("a tab cleared and left clear has no open debt to date")
    void clearedTabHasNoAge() {
        int customerId = insertCustomer("All", "Square", "9000000104");
        insertRow(customerId, base, "DBT", "-500.00");
        insertRow(customerId, base.plusDays(1), "DPAM", "500.00");

        Object[] position = creditBook.positionOf(customerId).get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) position[0]));
        assertNull(position[1], "nothing is owed, so no debt has an age");
        assertTrue(creditBook.creditBook(LEDGER_ACCOUNTS, null, PageRequest.of(0, 50)).stream()
                        .noneMatch(r -> ((Number) r[0]).intValue() == customerId),
                "and a settled customer is off the credit book entirely");
    }

    // ------------------------------------------------------------------
    // What the book shows, and to whom
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a customer in credit stays on the book, with the balance the other way")
    void inCreditIsNotLost() {
        // §7.1(b): a "who owes us" filter of balance < 0 "silently loses them",
        // and §14.2 records the Rs 52 that cost inside a sealed opening journal.
        // balance <> 0 is the rule, and the two live customers carrying +Rs 37
        // and +Rs 15 are why.
        int customerId = insertCustomer("In", "Credit", "9000000105");
        insertRow(customerId, base, "DPAM", "37.00");

        Object[] row = onlyRow(customerId);
        assertEquals(0, new BigDecimal("37.00").compareTo((BigDecimal) row[4]),
                "a positive tally means the shop holds their money");
    }

    @Test
    @DisplayName("the tab sums every business, because the tally has only one side")
    void crossBusiness() {
        // log §14.8's worked example. There is no business column on
        // rc_credit_req at all, so this is less a feature than the absence of a
        // filter — and that absence is the decision: scoped to recharge the warn
        // flag would understate the exposure while looking authoritative.
        int customerId = insertCustomer("Cross", "Business", "9000000106");
        insertRow(customerId, base, "DBT", "-2000.00");            // water
        insertRow(customerId, base.plusDays(30), "DBT", "-49.00"); // recharge

        Object[] row = onlyRow(customerId);
        assertEquals(0, new BigDecimal("-2049.00").compareTo((BigDecimal) row[4]));
        assertEquals(base, ((java.sql.Timestamp) row[6]).toLocalDateTime(),
                "and the age comes from the oldest of them, whichever business it was");
    }

    @Test
    @DisplayName("the bank pseudo-customers are not debtors")
    void ledgerAccountsExcluded() {
        // §2.6: customer 269 alone carries -Rs 46,766, which is a UPI clearing
        // balance. Listed, it would be the shop's largest debtor and read as one.
        insertCustomerWithId(269, "Janata", "Sahakari Bank", "0000000269");
        insertRow(269, base, "DBT", "-46766.00");

        assertTrue(creditBook.creditBook(LEDGER_ACCOUNTS, null, PageRequest.of(0, 50)).stream()
                .noneMatch(r -> ((Number) r[0]).intValue() == 269));
        assertEquals(0, creditBook.countCreditBook(LEDGER_ACCOUNTS, null));
    }

    @Test
    @DisplayName("minOutstanding filters, and leaving it out is not the same as zero")
    void minOutstandingIsNotZero() {
        // THE CASE BETWEEN THE TWO OBVIOUS ONES. An absent filter keeps everyone
        // with a tab, including the customers in credit. Reading an absent
        // filter as zero would mean balance <= 0, which drops them — §7.1(b)'s
        // mistake reached from the other direction.
        int owesALot = insertCustomer("Owes", "Plenty", "9000000107");
        int owesALittle = insertCustomer("Owes", "Little", "9000000108");
        int inCredit = insertCustomer("Holds", "Credit", "9000000109");
        insertRow(owesALot, base, "DBT", "-2000.00");
        insertRow(owesALittle, base.plusDays(1), "DBT", "-49.00");
        insertRow(inCredit, base.plusDays(2), "DPAM", "15.00");

        assertEquals(3, creditBook.countCreditBook(LEDGER_ACCOUNTS, null),
                "absent: everyone with a tab, in credit included");
        assertEquals(1, creditBook.countCreditBook(LEDGER_ACCOUNTS, new BigDecimal("100.00")),
                "a positive floor: only the one owing more than it");
        assertEquals(2, creditBook.countCreditBook(LEDGER_ACCOUNTS, new BigDecimal("0.01")),
                "and the smallest positive floor is what excludes the one in credit");

        var page = creditBook.creditBook(LEDGER_ACCOUNTS, new BigDecimal("100.00"),
                PageRequest.of(0, 50));
        assertEquals(1, page.size(), "the count and the list must apply the same predicate");
        assertEquals(owesALot, ((Number) page.get(0)[0]).intValue());
    }

    @Test
    @DisplayName("the book is ordered oldest debt first")
    void oldestFirst() {
        int older = insertCustomer("Older", "Debt", "9000000110");
        int newer = insertCustomer("Newer", "Debt", "9000000111");
        insertRow(newer, base.plusDays(20), "DBT", "-100.00");
        insertRow(older, base, "DBT", "-100.00");

        var page = creditBook.creditBook(LEDGER_ACCOUNTS, null, PageRequest.of(0, 50));
        assertEquals(older, ((Number) page.get(0)[0]).intValue(),
                "whoever has waited longest is chased first");
        assertEquals(newer, ((Number) page.get(1)[0]).intValue());
    }

    @Test
    @DisplayName("a customer in credit sorts BELOW every debtor, not above them")
    void inCreditSortsLast() {
        // Found by running it. oldest_open is NULL for someone who owes
        // nothing, MySQL sorts NULL first, so "has Rs 37 with us" appeared at
        // the top of a screen whose whole job is "who owes us, oldest first" —
        // above a debtor 40 days overdue. The two in-credit customers are real,
        // so this would have been the first rows on day one.
        int inCredit = insertCustomer("Holds", "Money", "9000000114");
        int debtor = insertCustomer("Owes", "Money", "9000000115");
        insertRow(inCredit, base.plusDays(35), "DPAM", "37.00");
        insertRow(debtor, base, "DBT", "-100.00");

        var page = creditBook.creditBook(LEDGER_ACCOUNTS, null, PageRequest.of(0, 50));

        assertEquals(debtor, ((Number) page.get(0)[0]).intValue(),
                "the debtor is the point of the screen");
        assertEquals(inCredit, ((Number) page.get(1)[0]).intValue(),
                "and the customer who owes nothing comes after everyone who does");
    }

    // ------------------------------------------------------------------
    // #15 — the statement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the statement runs newest first with an oldest-first running balance")
    void statementOrdering() {
        // The ordering appears twice in the query on purpose: reversing a
        // running total is not the same as computing it backwards.
        int customerId = insertCustomer("State", "Ment", "9000000112");
        insertRow(customerId, base, "DBT", "-500.00");
        insertRow(customerId, base.plusDays(10), "DPAM", "200.00");
        insertRow(customerId, base.plusDays(20), "DBT", "-100.00");

        var lines = creditBook.statementOf(customerId, PageRequest.of(0, 50));

        assertEquals(3, lines.size());
        assertEquals(3, creditBook.countStatement(customerId));
        assertEquals(0, new BigDecimal("-400.00").compareTo((BigDecimal) lines.get(0)[4]),
                "newest line first, and its running balance is the CURRENT one");
        assertEquals(0, new BigDecimal("-300.00").compareTo((BigDecimal) lines.get(1)[4]));
        assertEquals(0, new BigDecimal("-500.00").compareTo((BigDecimal) lines.get(2)[4]),
                "and the oldest line carries the balance as it stood then");
    }

    @Test
    @DisplayName("two statement lines in one second still order by id, both ways")
    void statementTiesBreakOnId() {
        // The same V28 consequence as finding 8, in the statement's window and
        // in its ORDER BY. Without the id tiebreak the running balance could be
        // computed in one order and displayed in another, so a line would show
        // a balance that never existed.
        int customerId = insertCustomer("Tied", "Seconds", "9000000113");
        LocalDateTime t = base.plusDays(3);
        insertRow(customerId, t, "DBT", "-100.00");
        insertRow(customerId, t, "DBT", "-50.00");

        var lines = creditBook.statementOf(customerId, PageRequest.of(0, 50));

        assertEquals(0, new BigDecimal("-150.00").compareTo((BigDecimal) lines.get(0)[4]),
                "the later-inserted row is shown first and carries the total");
        assertEquals(0, new BigDecimal("-100.00").compareTo((BigDecimal) lines.get(1)[4]));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Object[] onlyRow(int customerId) {
        return creditBook.creditBook(LEDGER_ACCOUNTS, null, PageRequest.of(0, 50)).stream()
                .filter(r -> ((Number) r[0]).intValue() == customerId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("customer " + customerId
                        + " is not on the credit book"));
    }

    private void insertRow(int customerId, LocalDateTime at, String type, String amount) {
        em.createNativeQuery("INSERT INTO rc_credit_req (CUST_ID, CRED_DTTM, REQ_TYPE, REQ_AMT) "
                        + "VALUES (:c, :d, :t, :a)")
                .setParameter("c", customerId).setParameter("d", at)
                .setParameter("t", type).setParameter("a", new BigDecimal(amount))
                .executeUpdate();
        em.flush();
    }

    private int insertCustomer(String first, String last, String contact) {
        em.createNativeQuery("INSERT INTO rs_cust_dtls (FIRST_NAME, LAST_NAME, CONTACT_NUM) "
                        + "VALUES (:f, :l, :c)")
                .setParameter("f", first).setParameter("l", last).setParameter("c", contact)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult())
                .intValue();
    }

    private void insertCustomerWithId(int id, String first, String last, String contact) {
        em.createNativeQuery("INSERT INTO rs_cust_dtls (CUST_ID, FIRST_NAME, LAST_NAME, "
                        + "CONTACT_NUM) VALUES (:i, :f, :l, :c)")
                .setParameter("i", id).setParameter("f", first).setParameter("l", last)
                .setParameter("c", contact)
                .executeUpdate();
        em.flush();
    }
}
