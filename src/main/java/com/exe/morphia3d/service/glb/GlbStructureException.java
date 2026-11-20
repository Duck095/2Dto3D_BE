package com.exe.morphia3d.service.glb;

/** Raised when a GLB container or its glTF structure is malformed or exceeds parser limits. */
public final class GlbStructureException extends IllegalArgumentException {
    public GlbStructureException(String message) {
        super(message);
    }

    public GlbStructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
