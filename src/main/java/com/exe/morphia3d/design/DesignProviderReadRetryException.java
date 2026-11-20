package com.exe.morphia3d.design;

public final class DesignProviderReadRetryException extends RuntimeException {
    private final String safeCode;

    public DesignProviderReadRetryException(String safeCode) {
        super(safeCode);
        this.safeCode = safeCode;
    }

    public String getSafeCode() { return safeCode; }
}
