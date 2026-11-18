package com.rentopia.db;

import com.rentopia.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationTest extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void allMigrationsApplySuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(7);
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState))
                .containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void referenceDataIsSeeded() {
        assertThat(jdbc.queryForList("SELECT code FROM roles ORDER BY id", String.class))
                .containsExactly("CUSTOMER", "VENDOR", "ADMIN", "SUPER_ADMIN", "SUPPORT");
        assertThat(jdbc.queryForList("SELECT code FROM categories ORDER BY id", String.class))
                .containsExactly("PROPERTY", "ROOM", "HOSTEL", "CAR", "BIKE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amenities", Integer.class)).isPositive();
    }

    @Test
    void requiredExtensionsAreInstalled() {
        assertThat(jdbc.queryForList("SELECT extname FROM pg_extension", String.class))
                .contains("pgcrypto", "citext", "btree_gist");
    }

    @Test
    void everyPlannedTableExists() {
        List<String> expected = List.of(
                // identity
                "users", "roles", "user_roles", "refresh_tokens", "otp_codes", "customer_profiles", "addresses",
                // vendor
                "vendor_profiles", "vendor_documents", "vendor_bank_accounts",
                // catalog
                "cities", "categories", "listings", "listing_images", "amenities", "listing_amenities",
                "listing_prices", "listing_status_history",
                // category details
                "properties", "rooms", "hostels", "hostel_rooms", "beds", "vehicles", "vehicle_documents",
                // availability & booking
                "inventory_units", "blocked_dates", "bookings", "booking_items", "booking_pricing_lines",
                "booking_status_history",
                // payments & commercials
                "payments", "payment_events", "refunds", "commission_rules", "coupons", "coupon_redemptions",
                "vendor_settlements", "settlement_items",
                // engagement & ops
                "reviews", "wishlists", "notifications", "support_tickets", "ticket_messages", "audit_logs");
        List<String> actual = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(actual).containsAll(expected);
    }
}
