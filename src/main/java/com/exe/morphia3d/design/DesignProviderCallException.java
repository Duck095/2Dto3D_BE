package com.exe.morphia3d.design;

public final class DesignProviderCallException extends RuntimeException {
    public enum DispatchState { NOT_DISPATCHED, OUTCOME_UNKNOWN }

    private final String safeCode;
    private final DispatchState dispatchState;

    public DesignProviderCallException(String safeCode, DispatchState dispatchState) {
        super(safeCode);
        this.safeCode = safeCode;
        this.dispatchState = dispatchState;
    }

    public String getSafeCode() { return safeCode; }
    public DispatchState getDispatchState() { return dispatchState; }
}
