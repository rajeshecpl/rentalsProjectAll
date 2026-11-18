package com.rentopia.db;

import com.rentopia.support.Fixtures;
import com.rentopia.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.rentopia.support.SqlStates.EXCLUSION_VIOLATION;
import static com.rentopia.support.SqlStates.assertSqlState;
import static com.rentopia.support.SqlStates.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Proves PostgreSQL itself prevents double bookings, independent of
 * application code (the critical scenarios from the project plan).
 */
class BookingOverlapTest extends IntegrationTest {

    private static final OffsetDateTime DAY1 = OffsetDateTime.of(2027, 1, 10, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));
    private static final OffsetDateTime DAY2 = DAY1.plusDays(1);
    private static final OffsetDateTime DAY3 = DAY1.plusDays(2);
    private static final OffsetDateTime DAY4 = DAY1.plusDays(3);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    Fixtures fx;
    UUID vendor;

    @BeforeEach
    void setUp() {
        fx = new Fixtures(jdbc);
        vendor = fx.vendor();
    }

    @Test
    void sameCarCannotBeBookedForOverlappingPeriods() {
        UUID car = fx.listing(vendor, Fixtures.CAR);
        UUID unit = fx.unit(car, "VEHICLE");

        fx.bookingItem(fx.booking(car, vendor, DAY1, DAY3), unit, DAY1, DAY3);

        UUID second = fx.booking(car, vendor, DAY2, DAY4);
        assertSqlState(EXCLUSION_VIOLATION, () -> fx.bookingItem(second, unit, DAY2, DAY4));
    }

    @Test
    void backToBackRentalsAreAllowed() {
        UUID bike = fx.listing(vendor, Fixtures.BIKE);
        UUID unit = fx.unit(bike, "VEHICLE");

        fx.bookingItem(fx.booking(bike, vendor, DAY1, DAY2), unit, DAY1, DAY2);
        // [DAY1, DAY2) and [DAY2, DAY3) touch but do not overlap
        assertThatCode(() -> fx.bookingItem(fx.booking(bike, vendor, DAY2, DAY3), unit, DAY2, DAY3))
                .doesNotThrowAnyException();
    }

    @Test
    void differentUnitsDoNotConflict() {
        UUID carA = fx.listing(vendor, Fixtures.CAR);
        UUID carB = fx.listing(vendor, Fixtures.CAR);
        UUID unitA = fx.unit(carA, "VEHICLE");
        UUID unitB = fx.unit(carB, "VEHICLE");

        fx.bookingItem(fx.booking(carA, vendor, DAY1, DAY3), unitA, DAY1, DAY3);
        assertThatCode(() -> fx.bookingItem(fx.booking(carB, vendor, DAY1, DAY3), unitB, DAY1, DAY3))
                .doesNotThrowAnyException();
    }

    @Test
    void cancelledBookingReleasesInventoryAndCannotBeSilentlyRevived() {
        UUID room = fx.listing(vendor, Fixtures.ROOM);
        UUID unit = fx.unit(room, "ROOM");

        UUID cancelledItem = fx.bookingItem(fx.booking(room, vendor, DAY1, DAY3), unit, DAY1, DAY3);
        jdbc.update("UPDATE booking_items SET is_blocking = false WHERE id = ?", cancelledItem);

        assertThatCode(() -> fx.bookingItem(fx.booking(room, vendor, DAY1, DAY3), unit, DAY1, DAY3))
                .doesNotThrowAnyException();

        // Re-activating the cancelled item would now double-book, so the database refuses.
        assertSqlState(EXCLUSION_VIOLATION,
                () -> jdbc.update("UPDATE booking_items SET is_blocking = true WHERE id = ?", cancelledItem));
    }

    @Test
    void hostelWithTwentyBedsRejectsTheTwentyFirstOverlappingBooking() {
        UUID hostel = fx.listing(vendor, Fixtures.HOSTEL);
        List<UUID> beds = fx.hostelBedUnits(hostel, 20);

        for (UUID bed : beds) {
            fx.bookingItem(fx.booking(hostel, vendor, DAY1, DAY4), bed, DAY1, DAY4);
        }

        // The 21st customer cannot get any bed for an overlapping period.
        UUID booking21 = fx.booking(hostel, vendor, DAY2, DAY3);
        for (UUID bed : beds) {
            assertSqlState(EXCLUSION_VIOLATION, () -> fx.bookingItem(booking21, bed, DAY2, DAY3));
        }
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM booking_items i JOIN inventory_units u ON u.id = i.inventory_unit_id
                WHERE u.listing_id = ? AND i.is_blocking""", Integer.class, hostel);
        assertThat(active).isEqualTo(20);
    }

    @Test
    void vendorBlockAndBookingCannotOverlapInEitherOrder() {
        UUID property = fx.listing(vendor, Fixtures.PROPERTY);
        UUID unit = fx.unit(property, "WHOLE");

        fx.block(unit, DAY1, DAY2);
        assertSqlState(EXCLUSION_VIOLATION,
                () -> fx.bookingItem(fx.booking(property, vendor, DAY1, DAY3), unit, DAY1, DAY3));

        fx.bookingItem(fx.booking(property, vendor, DAY3, DAY4), unit, DAY3, DAY4);
        assertSqlState(EXCLUSION_VIOLATION, () -> fx.block(unit, DAY3, DAY4));
    }

    /**
     * Two customers book the same car at the same moment in separate transactions.
     * The second insert waits on the first transaction, then fails once it commits.
     */
    @Test
    void concurrentBookingsForSameCarExactlyOneWins() throws Exception {
        UUID car = fx.listing(vendor, Fixtures.CAR);
        UUID unit = fx.unit(car, "VEHICLE");
        UUID bookingA = fx.booking(car, vendor, DAY1, DAY3);
        UUID bookingB = fx.booking(car, vendor, DAY2, DAY4);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch aInserted = new CountDownLatch(1);
        CountDownLatch bStarted = new CountDownLatch(1);

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            Fixtures.bookingItem(jdbc, bookingA, unit, DAY1, DAY3);
            aInserted.countDown();
            await(bStarted);
            sleep(300); // give B time to block on A's uncommitted row
        }));

        CompletableFuture<String> b = CompletableFuture.supplyAsync(() -> {
            await(aInserted);
            bStarted.countDown();
            try {
                tx.executeWithoutResult(s -> Fixtures.bookingItem(jdbc, bookingB, unit, DAY2, DAY4));
                return "COMMITTED";
            } catch (RuntimeException e) {
                return sqlState(e);
            }
        });

        a.get(30, TimeUnit.SECONDS);
        assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(EXCLUSION_VIOLATION);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM booking_items WHERE inventory_unit_id = ? AND is_blocking",
                Integer.class, unit)).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
