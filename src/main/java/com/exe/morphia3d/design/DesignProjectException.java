package com.exe.morphia3d.design;

public class DesignProjectException extends RuntimeException {
    private final String code;
    public DesignProjectException(String code) { super(code); this.code = code; }
    public String getCode() { return code; }
}
