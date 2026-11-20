package com.exe.morphia3d.design;

public class UnavailableMeshyTransport implements MeshyTransport {
    @Override public String submit(MeshyModelProvider.ModelRequest request) { throw new ProviderUnavailableException("MESHY_TRANSPORT_UNAVAILABLE"); }
    @Override public MeshyModelProvider.TaskStatus status(String providerTaskReference) { throw new ProviderUnavailableException("MESHY_TRANSPORT_UNAVAILABLE"); }
    @Override public MeshyModelProvider.GeneratedModel result(String providerTaskReference) { throw new ProviderUnavailableException("MESHY_TRANSPORT_UNAVAILABLE"); }
}
