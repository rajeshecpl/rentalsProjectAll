package com.rentopia.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plain-SQL builders for schema-level tests (no JPA entities exist yet for these tables). */
public class Fixtures {

    public static final short PROPERTY = 1, ROOM = 2, HOSTEL = 3, CAR = 4, BIKE = 5;

    private final JdbcTemplate jdbc;

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user() {
        String tag = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, full_name)
                VALUES (?, 'x', 'Test User') RETURNING id""", UUID.class, tag + "@test.rentopia");
    }

    public UUID vendor() {
        return jdbc.queryForObject("""
                INSERT INTO vendor_profiles (user_id, vendor_type, display_name, verification_status)
                VALUES (?, 'INDIVIDUAL', 'Test Vendor', 'APPROVED') RETURNING id""", UUID.class, user());
    }

    public UUID city() {
        String tag = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO cities (name, state, slug) VALUES (?, 'Karnataka', ?) RETURNING id""",
                UUID.class, "City " + tag, "city-" + tag);
    }

    public UUID listing(UUID vendorId, short categoryId) {
        String tag = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO listings (vendor_id, category_id, city_id, title, slug, status, published_at)
                VALUES (?, ?, ?, 'Test listing', ?, 'PUBLISHED', now()) RETURNING id""",
                UUID.class, vendorId, categoryId, city(), "listing-" + tag);
    }

    public UUID unit(UUID listingId, String kind) {
        return jdbc.queryForObject("""
                INSERT INTO inventory_units (listing_id, unit_kind) VALUES (?, ?) RETURNING id""",
                UUID.class, listingId, kind);
    }

    /** Creates a hostel with one room holding {@code beds} beds, each with its own BED inventory unit. */
    public List<UUID> hostelBedUnits(UUID listingId, int beds) {
        jdbc.update("INSERT INTO hostels (listing_id, gender_type) VALUES (?, 'COLIVING')", listingId);
        UUID roomId = jdbc.queryForObject("""
                INSERT INTO hostel_rooms (hostel_listing_id, room_label, sharing_capacity)
                VALUES (?, 'R1', ?) RETURNING id""", UUID.class, listingId, (short) Math.min(beds, 20));
        List<UUID> units = new ArrayList<>();
        for (int i = 1; i <= beds; i++) {
            UUID bedId = jdbc.queryForObject("""
                    INSERT INTO beds (hostel_room_id, bed_label) VALUES (?, ?) RETURNING id""",
                    UUID.class, roomId, "B" + i);
            units.add(jdbc.queryForObject("""
                    INSERT INTO inventory_units (listing_id, unit_kind, bed_id) VALUES (?, 'BED', ?) RETURNING id""",
                    UUID.class, listingId, bedId));
        }
        return units;
    }

    public UUID booking(UUID listingId, UUID vendorId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.queryForObject("""
                INSERT INTO bookings (customer_id, listing_id, vendor_id, start_at, end_at, billing_period)
                VALUES (?, ?, ?, ?, ?, 'DAILY') RETURNING id""",
                UUID.class, user(), listingId, vendorId, start, end);
    }

    public UUID bookingItem(UUID bookingId, UUID unitId, OffsetDateTime start, OffsetDateTime end) {
        return bookingItem(jdbc, bookingId, unitId, start, end);
    }

    /** Overload taking a JdbcTemplate so concurrency tests can use their own transactions. */
    public static UUID bookingItem(JdbcTemplate jdbc, UUID bookingId, UUID unitId,
                                   OffsetDateTime start, OffsetDateTime end) {
        return jdbc.queryForObject("""
                INSERT INTO booking_items (booking_id, inventory_unit_id, period)
                VALUES (?, ?, tstzrange(?, ?, '[)')) RETURNING id""",
                UUID.class, bookingId, unitId, start, end);
    }

    public void block(UUID unitId, OffsetDateTime start, OffsetDateTime end) {
        jdbc.update("""
                INSERT INTO blocked_dates (inventory_unit_id, period) VALUES (?, tstzrange(?, ?, '[)'))""",
                unitId, start, end);
    }
}
