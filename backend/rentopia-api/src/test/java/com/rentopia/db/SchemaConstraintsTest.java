package com.rentopia.db;

import com.rentopia.support.Fixtures;
import com.rentopia.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.rentopia.support.SqlStates.CHECK_VIOLATION;
import static com.rentopia.support.SqlStates.UNIQUE_VIOLATION;
import static com.rentopia.support.SqlStates.assertSqlState;
import static org.assertj.core.api.Assertions.assertThat;

/** Money, idempotency and integrity rules enforced by the schema. */
class SchemaConstraintsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    Fixtures fx;
    UUID vendor;
    UUID listing;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(jdbc);
        vendor = fx.vendor();
        listing = fx.listing(vendor, Fixtures.CAR);
    }

    @Test
    void duplicateWebhookDeliveryIsRejected() {
        String eventId = "evt_" + UUID.randomUUID();
        String sql = """
                INSERT INTO payment_events (gateway, gateway_event_id, event_type, signature_valid, payload)
                VALUES ('razorpay', ?, 'payment.captured', true, '{}'::jsonb)""";
        jdbc.update(sql, eventId);
        assertSqlState(UNIQUE_VIOLATION, () -> jdbc.update(sql, eventId));
    }

    @Test
    void bookingCreationIsIdempotentPerCustomer() {
        UUID customer = fx.user();
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        String sql = """
                INSERT INTO bookings (customer_id, listing_id, vendor_id, start_at, end_at, billing_period, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'DAILY', 'checkout-123')""";
        jdbc.update(sql, customer, listing, vendor, start, start.plusDays(1));
        assertSqlState(UNIQUE_VIOLATION, () -> jdbc.update(sql, customer, listing, vendor, start, start.plusDays(1)));
    }

    @Test
    void bookingTotalMustMatchItsComponents() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        assertSqlState(CHECK_VIOLATION, () -> jdbc.update("""
                INSERT INTO bookings (customer_id, listing_id, vendor_id, start_at, end_at, billing_period,
                                      rent_paise, deposit_paise, platform_fee_paise, tax_paise, discount_paise, total_paise)
                VALUES (?, ?, ?, ?, ?, 'DAILY', 1000000, 500000, 20000, 3600, 0, 999)""",
                fx.user(), listing, vendor, start, start.plusDays(1)));
    }

    @Test
    void bookingEndMustBeAfterStart() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        assertSqlState(CHECK_VIOLATION, () -> fx.booking(listing, vendor, start, start));
    }

    @Test
    void pendingPaymentBookingRequiresHoldExpiry() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        UUID booking = fx.booking(listing, vendor, start, start.plusDays(1));
        assertSqlState(CHECK_VIOLATION,
                () -> jdbc.update("UPDATE bookings SET status = 'PENDING_PAYMENT' WHERE id = ?", booking));
    }

    @Test
    void bookingNumbersAreGenerated() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        UUID booking = fx.booking(listing, vendor, start, start.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT booking_number FROM bookings WHERE id = ?", String.class, booking))
                .matches("RTP\\d+");
    }

    @Test
    void emailUniquenessIsCaseInsensitive() {
        String email = "Case." + UUID.randomUUID() + "@Test.Rentopia";
        jdbc.update("INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', 'A')", email);
        assertSqlState(UNIQUE_VIOLATION, () -> jdbc.update(
                "INSERT INTO users (email, password_hash, full_name) VALUES (?, 'x', 'B')", email.toLowerCase()));
    }

    @Test
    void reviewRatingMustBeOneToFive() {
        OffsetDateTime start = OffsetDateTime.now().plusDays(5);
        UUID booking = fx.booking(listing, vendor, start, start.plusDays(1));
        UUID customer = jdbc.queryForObject("SELECT customer_id FROM bookings WHERE id = ?", UUID.class, booking);
        assertSqlState(CHECK_VIOLATION, () -> jdbc.update("""
                INSERT INTO reviews (booking_id, listing_id, customer_id, vendor_id, rating)
                VALUES (?, ?, ?, ?, 6)""", booking, listing, customer, vendor));
    }

    @Test
    void updatedAtIsMaintainedByTrigger() throws InterruptedException {
        UUID user = fx.user();
        var before = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", OffsetDateTime.class, user);
        Thread.sleep(20);
        jdbc.update("UPDATE users SET full_name = 'Renamed' WHERE id = ?", user);
        var after = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", OffsetDateTime.class, user);
        assertThat(after).isAfter(before);
    }
}
