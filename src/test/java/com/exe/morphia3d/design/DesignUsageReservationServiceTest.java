package com.exe.morphia3d.design;

import com.exe.morphia3d.repository.DesignUsageBudgetWindowRepository;
import com.exe.morphia3d.repository.DesignUsageReservationRepository;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DesignUsageReservationServiceTest {
    @Test
    void zeroDefaultLimitsRejectJobsInsteadOfTreatingThemAsUnlimited() {
        var windows = mock(DesignUsageBudgetWindowRepository.class);
        var reservations = mock(DesignUsageReservationRepository.class);
        var service = new DesignUsageReservationService(new DesignUsageLimits(), windows, reservations);

        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), 23, 1))
                .isInstanceOf(DesignQuotaException.class).hasMessage("DESIGN_USAGE_DISABLED");
        verifyNoInteractions(windows, reservations);
    }
}
