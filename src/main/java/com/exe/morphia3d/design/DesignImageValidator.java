package com.exe.morphia3d.design;

import com.exe.morphia3d.service.MediaUploadProperties;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

@Component
public class DesignImageValidator {
    private final MediaUploadProperties limits;
    public DesignImageValidator(MediaUploadProperties limits) { this.limits = limits; }

    public void validate(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0 || bytes.length > limits.getImageMaxBytes()) invalid();
        String actual = signatureMime(bytes);
        if (!actual.equals(mimeType)) invalid();
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) invalid();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > limits.getDecodedPixelMax()) invalid();
                java.awt.image.BufferedImage decoded = reader.read(0);
                if (decoded == null) invalid();
                decoded.flush();
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof DesignProjectException projectException) throw projectException;
            invalid();
        }
    }

    private String signatureMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return "application/octet-stream";
    }

    private void invalid() { throw new DesignProjectException("DESIGN_PROVIDER_OUTPUT_INVALID"); }
}
