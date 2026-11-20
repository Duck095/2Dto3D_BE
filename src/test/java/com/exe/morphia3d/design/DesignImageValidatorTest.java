package com.exe.morphia3d.design;

import com.exe.morphia3d.service.MediaUploadProperties;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesignImageValidatorTest {
    private final DesignImageValidator validator = new DesignImageValidator(new MediaUploadProperties());

    @Test
    void acceptsDecodedPngWithMatchingSignature() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        assertThatCode(() -> validator.validate(bytes.toByteArray(), "image/png")).doesNotThrowAnyException();
    }

    @Test
    void rejectsMismatchedMimeAndCorruptImages() {
        assertThatThrownBy(() -> validator.validate(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(DesignProjectException.class).hasMessage("DESIGN_PROVIDER_OUTPUT_INVALID");
    }
}
