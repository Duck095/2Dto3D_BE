package com.exe.morphia3d.design;

import com.exe.morphia3d.entity.DesignUsageBudgetWindow;
import com.exe.morphia3d.entity.DesignUsageReservation;
import com.exe.morphia3d.repository.DesignUsageBudgetWindowRepository;
import com.exe.morphia3d.repository.DesignUsageReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

@Service
public class DesignUsageReservationService {
    private final DesignUsageLimits limits;
    private final DesignUsageBudgetWindowRepository windows;
    private final DesignUsageReservationRepository reservations;
    private final Clock clock;

    @Autowired
    public DesignUsageReservationService(DesignUsageLimits limits, DesignUsageBudgetWindowRepository windows,
                                         DesignUsageReservationRepository reservations) {
        this(limits, windows, reservations, Clock.systemUTC());
    }

    DesignUsageReservationService(DesignUsageLimits limits, DesignUsageBudgetWindowRepository windows,
                                  DesignUsageReservationRepository reservations, Clock clock) {
        this.limits = limits; this.windows = windows; this.reservations = reservations; this.clock = clock;
    }

    @Transactional
    public DesignUsageReservation reserve(UUID jobId, Integer ownerId, long requestedUnits) {
        if (jobId == null || ownerId == null || ownerId <= 0 || requestedUnits <= 0
                || limits.getPerJobUnits() <= 0 || limits.getPerOwnerDailyUnits() <= 0 || limits.getGlobalDailyUnits() <= 0) {
            throw new DesignQuotaException("DESIGN_USAGE_DISABLED");
        }
        if (requestedUnits > limits.getPerJobUnits()) throw new DesignQuotaException("DESIGN_JOB_LIMIT_EXCEEDED");
        var prior = reservations.findByJobId(jobId);
        if (prior.isPresent()) {
            if (prior.get().getReservedUnits() == requestedUnits) return prior.get();
            throw new DesignQuotaException("DESIGN_RESERVATION_CONFLICT");
        }

        LocalDate day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        DesignUsageBudgetWindow global = lockWindow("GLOBAL", day, limits.getGlobalDailyUnits());
        DesignUsageBudgetWindow owner = lockWindow("USER:" + ownerId, day, limits.getPerOwnerDailyUnits());
        ensureCapacity(global, requestedUnits);
        ensureCapacity(owner, requestedUnits);
        global.setReservedUnits(Math.addExact(global.getReservedUnits(), requestedUnits));
        owner.setReservedUnits(Math.addExact(owner.getReservedUnits(), requestedUnits));
        windows.save(global); windows.save(owner);

        DesignUsageReservation reservation = new DesignUsageReservation();
        reservation.setId(UUID.randomUUID()); reservation.setJobId(jobId); reservation.setOwnerUserId(ownerId);
        reservation.setGlobalBudgetWindowId(global.getId()); reservation.setOwnerBudgetWindowId(owner.getId());
        reservation.setReservedUnits(requestedUnits); reservation.setStatus(DesignUsageReservationStatus.RESERVED);
        return reservations.save(reservation);
    }

    @Transactional
    public void settle(UUID jobId, long actualUnits, String providerUsageReference) {
        DesignUsageReservation reservation = reservations.findLockedByJobId(jobId)
                .orElseThrow(() -> new DesignQuotaException("DESIGN_RESERVATION_NOT_FOUND"));
        if (reservation.getStatus() == DesignUsageReservationStatus.SETTLED) {
            if (Objects.equals(reservation.getSettledUnits(), actualUnits)) return;
            throw new DesignQuotaException("DESIGN_SETTLEMENT_CONFLICT");
        }
        if (reservation.getStatus() == DesignUsageReservationStatus.RELEASED) throw new DesignQuotaException("DESIGN_RESERVATION_RELEASED");
        if (actualUnits < 0 || actualUnits > reservation.getReservedUnits()) {
            reservation.setStatus(DesignUsageReservationStatus.UNKNOWN);
            reservation.setProviderUsageReference(providerUsageReference);
            reservations.save(reservation);
            return;
        }
        DesignUsageBudgetWindow global = lockWindowById(reservation.getGlobalBudgetWindowId());
        DesignUsageBudgetWindow owner = lockWindowById(reservation.getOwnerBudgetWindowId());
        for (DesignUsageBudgetWindow window : new DesignUsageBudgetWindow[]{global, owner}) {
            window.setReservedUnits(window.getReservedUnits() - reservation.getReservedUnits());
            window.setSettledUnits(Math.addExact(window.getSettledUnits(), actualUnits));
            windows.save(window);
        }
        reservation.setSettledUnits(actualUnits); reservation.setProviderUsageReference(providerUsageReference);
        reservation.setStatus(DesignUsageReservationStatus.SETTLED); reservation.setSettledAt(clock.instant());
        reservations.save(reservation);
    }

    /** Settles the complete internal reservation after a confirmed provider completion/failure. */
    @Transactional
    public void settleReserved(UUID jobId, String providerUsageReference) {
        DesignUsageReservation reservation = reservations.findLockedByJobId(jobId)
                .orElseThrow(() -> new DesignQuotaException("DESIGN_RESERVATION_NOT_FOUND"));
        if (reservation.getStatus() == DesignUsageReservationStatus.SETTLED) {
            if (Objects.equals(reservation.getSettledUnits(), reservation.getReservedUnits())) return;
            throw new DesignQuotaException("DESIGN_SETTLEMENT_CONFLICT");
        }
        settle(jobId, reservation.getReservedUnits(), providerUsageReference);
    }

    @Transactional
    public void releaseZeroCost(UUID jobId) {
        DesignUsageReservation reservation = reservations.findLockedByJobId(jobId)
                .orElseThrow(() -> new DesignQuotaException("DESIGN_RESERVATION_NOT_FOUND"));
        if (reservation.getStatus() == DesignUsageReservationStatus.RELEASED
                || reservation.getStatus() == DesignUsageReservationStatus.SETTLED) return;
        if (reservation.getStatus() == DesignUsageReservationStatus.UNKNOWN) throw new DesignQuotaException("DESIGN_UNKNOWN_COST_CANNOT_RELEASE");
        DesignUsageBudgetWindow global = lockWindowById(reservation.getGlobalBudgetWindowId());
        DesignUsageBudgetWindow owner = lockWindowById(reservation.getOwnerBudgetWindowId());
        global.setReservedUnits(global.getReservedUnits() - reservation.getReservedUnits());
        owner.setReservedUnits(owner.getReservedUnits() - reservation.getReservedUnits());
        windows.save(global); windows.save(owner);
        reservation.setStatus(DesignUsageReservationStatus.RELEASED);
        reservations.save(reservation);
    }

    private DesignUsageBudgetWindow lockWindow(String scope, LocalDate day, long limit) {
        windows.insertWindowIfAbsent(UUID.randomUUID(), scope, day, limit);
        DesignUsageBudgetWindow window = windows.findByScopeKeyAndWindowDate(scope, day)
                .orElseThrow(() -> new DesignQuotaException("DESIGN_BUDGET_WINDOW_UNAVAILABLE"));
        if (window.getLimitUnits() != limit) throw new DesignQuotaException("DESIGN_BUDGET_CONFIGURATION_CHANGED");
        return window;
    }

    private DesignUsageBudgetWindow lockWindowById(UUID id) {
        DesignUsageBudgetWindow current = windows.findById(id)
                .orElseThrow(() -> new DesignQuotaException("DESIGN_BUDGET_WINDOW_UNAVAILABLE"));
        return windows.findByScopeKeyAndWindowDate(current.getScopeKey(), current.getWindowDate())
                .orElseThrow(() -> new DesignQuotaException("DESIGN_BUDGET_WINDOW_UNAVAILABLE"));
    }

    private void ensureCapacity(DesignUsageBudgetWindow window, long requested) {
        long total;
        try { total = Math.addExact(Math.addExact(window.getReservedUnits(), window.getSettledUnits()), requested); }
        catch (ArithmeticException overflow) { throw new DesignQuotaException("DESIGN_DAILY_LIMIT_EXCEEDED"); }
        if (total > window.getLimitUnits()) throw new DesignQuotaException("DESIGN_DAILY_LIMIT_EXCEEDED");
    }
}
