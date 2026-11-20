package com.exe.morphia3d.design;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignProjectStateMachineTest {
    private final DesignProjectStateMachine machine = new DesignProjectStateMachine();

    @Test
    void explicitApprovalAndCommandsDriveReferenceToModelLifecycle() {
        assertThat(machine.enqueueReferences(DesignProjectStatus.DRAFT)).isEqualTo(DesignProjectStatus.REFERENCES_QUEUED);
        assertThat(machine.startReferences(DesignProjectStatus.REFERENCES_QUEUED)).isEqualTo(DesignProjectStatus.REFERENCES_GENERATING);
        assertThat(machine.finishReferences(DesignProjectStatus.REFERENCES_GENERATING)).isEqualTo(DesignProjectStatus.REFERENCES_REVIEW);
        assertThat(machine.approveReferences(DesignProjectStatus.REFERENCES_REVIEW)).isEqualTo(DesignProjectStatus.REFERENCES_APPROVED);
        assertThat(machine.enqueueModel(DesignProjectStatus.REFERENCES_APPROVED)).isEqualTo(DesignProjectStatus.MODEL_QUEUED);
        assertThat(machine.startModel(DesignProjectStatus.MODEL_QUEUED)).isEqualTo(DesignProjectStatus.MODEL_GENERATING);
        assertThat(machine.finishModel(DesignProjectStatus.MODEL_GENERATING)).isEqualTo(DesignProjectStatus.MODEL_REVIEW);
        assertThat(machine.approveModel(DesignProjectStatus.MODEL_REVIEW)).isEqualTo(DesignProjectStatus.COMPLETED);
    }

    @Test
    void rejectsSkippingReferenceApprovalAndAllowsFailedRetryAsNewQueue() {
        assertThatThrownBy(() -> machine.enqueueModel(DesignProjectStatus.REFERENCES_REVIEW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(machine.fail(DesignProjectStatus.MODEL_GENERATING)).isEqualTo(DesignProjectStatus.FAILED);
        assertThat(machine.enqueueReferences(DesignProjectStatus.FAILED)).isEqualTo(DesignProjectStatus.REFERENCES_QUEUED);
    }
}
