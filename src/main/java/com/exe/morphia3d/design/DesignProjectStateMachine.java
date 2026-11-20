package com.exe.morphia3d.design;

public class DesignProjectStateMachine {
    public DesignProjectStatus enqueueReferences(DesignProjectStatus current) {
        require(current, DesignProjectStatus.DRAFT, DesignProjectStatus.FAILED, DesignProjectStatus.REFERENCES_REVIEW,
                DesignProjectStatus.COMPLETED);
        return DesignProjectStatus.REFERENCES_QUEUED;
    }

    public DesignProjectStatus startReferences(DesignProjectStatus current) {
        require(current, DesignProjectStatus.REFERENCES_QUEUED);
        return DesignProjectStatus.REFERENCES_GENERATING;
    }

    public DesignProjectStatus finishReferences(DesignProjectStatus current) {
        require(current, DesignProjectStatus.REFERENCES_GENERATING);
        return DesignProjectStatus.REFERENCES_REVIEW;
    }

    public DesignProjectStatus approveReferences(DesignProjectStatus current) {
        require(current, DesignProjectStatus.REFERENCES_REVIEW);
        return DesignProjectStatus.REFERENCES_APPROVED;
    }

    public DesignProjectStatus enqueueModel(DesignProjectStatus current) {
        require(current, DesignProjectStatus.REFERENCES_APPROVED, DesignProjectStatus.MODEL_REVIEW, DesignProjectStatus.FAILED);
        return DesignProjectStatus.MODEL_QUEUED;
    }

    public DesignProjectStatus startModel(DesignProjectStatus current) {
        require(current, DesignProjectStatus.MODEL_QUEUED);
        return DesignProjectStatus.MODEL_GENERATING;
    }

    public DesignProjectStatus finishModel(DesignProjectStatus current) {
        require(current, DesignProjectStatus.MODEL_GENERATING);
        return DesignProjectStatus.MODEL_REVIEW;
    }

    public DesignProjectStatus approveModel(DesignProjectStatus current) {
        require(current, DesignProjectStatus.MODEL_REVIEW);
        return DesignProjectStatus.COMPLETED;
    }

    public DesignProjectStatus fail(DesignProjectStatus current) {
        if (current == DesignProjectStatus.COMPLETED) throw new IllegalStateException("Completed project cannot fail");
        return DesignProjectStatus.FAILED;
    }

    private void require(DesignProjectStatus current, DesignProjectStatus... allowed) {
        for (DesignProjectStatus state : allowed) if (state == current) return;
        throw new IllegalStateException("Transition is not allowed from " + current);
    }
}
