package com.exe.morphia3d.design;

public interface MeshyTransport {
    String submit(MeshyModelProvider.ModelRequest request);
    MeshyModelProvider.TaskStatus status(String providerTaskReference);
    MeshyModelProvider.GeneratedModel result(String providerTaskReference);
}
