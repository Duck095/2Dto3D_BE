package com.exe.morphia3d.design;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** All limits are integer abstract usage units; zero means disabled, never unlimited. */
@Component
@ConfigurationProperties(prefix = "app.design.usage")
public class DesignUsageLimits {
    private long perJobUnits;
    private long perOwnerDailyUnits;
    private long globalDailyUnits;
    private long referenceJobUnits;
    private long modelJobUnits;
    public long getPerJobUnits() { return perJobUnits; }
    public void setPerJobUnits(long value) { perJobUnits = value; }
    public long getPerOwnerDailyUnits() { return perOwnerDailyUnits; }
    public void setPerOwnerDailyUnits(long value) { perOwnerDailyUnits = value; }
    public long getGlobalDailyUnits() { return globalDailyUnits; }
    public void setGlobalDailyUnits(long value) { globalDailyUnits = value; }
    public long getReferenceJobUnits() { return referenceJobUnits; }
    public void setReferenceJobUnits(long value) { referenceJobUnits = value; }
    public long getModelJobUnits() { return modelJobUnits; }
    public void setModelJobUnits(long value) { modelJobUnits = value; }
}
