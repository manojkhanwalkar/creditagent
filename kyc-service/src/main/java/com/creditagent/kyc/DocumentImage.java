package com.creditagent.kyc;

import com.creditagent.kyc.Verification.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * An uploaded image, decoded defensively: the format comes from the file's magic bytes (not its name
 * or declared type), only PNG and JPEG are accepted, and the pixel count is checked from the header
 * before any pixels are decoded.
 *
 * @param text the image's text metadata (PNG {@code tEXt}/{@code iTXt}, JPEG comments)
 */
record DocumentImage(BufferedImage pixels, List<String> text) {

    /** Thrown when the upload cannot become a {@link DocumentImage}; carries the reason to report. */
    static final class Unreadable extends Exception {
        private static final long serialVersionUID = 1L;

        final transient Reason reason;

        Unreadable(Reason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    static DocumentImage decode(byte[] bytes, KycProperties limits) throws Unreadable {
        String format = format(bytes);
        if (format == null) {
            throw new Unreadable(Reason.UNSUPPORTED_IMAGE);
        }
        if (bytes.length > limits.maxImageBytes()) {
            throw new Unreadable(Reason.IMAGE_SIZE);
        }
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new Unreadable(Reason.UNSUPPORTED_IMAGE);
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(in, true, false);
            long width = reader.getWidth(0);
            long height = reader.getHeight(0);
            if (width * height > limits.maxPixels()
                    || Math.max(width, height) < limits.minWidth()
                    || Math.min(width, height) < limits.minHeight()) {
                throw new Unreadable(Reason.IMAGE_SIZE);
            }
            BufferedImage pixels = reader.read(0);
            return new DocumentImage(pixels, text(reader.getImageMetadata(0)));
        } catch (IOException | RuntimeException e) {
            // Truncated or corrupt data, or a decoder limit: either way, not a usable photo.
            throw new Unreadable(Reason.UNSUPPORTED_IMAGE);
        } finally {
            reader.dispose();
        }
    }

    /** "png", "jpeg", or null. */
    static String format(byte[] bytes) {
        if (startsWith(bytes, PNG)) {
            return "png";
        }
        if (startsWith(bytes, JPEG)) {
            return "jpeg";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Every {@code Text/TextEntry} value in the plug-in neutral metadata tree. */
    private static List<String> text(IIOMetadata metadata) {
        List<String> values = new ArrayList<>();
        if (metadata == null || !metadata.isStandardMetadataFormatSupported()) {
            return values;
        }
        Node root = metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName);
        for (Node section = root.getFirstChild(); section != null; section = section.getNextSibling()) {
            if (!"Text".equals(section.getNodeName())) {
                continue;
            }
            for (Node entry = section.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                NamedNodeMap attributes = entry.getAttributes();
                Node value = attributes == null ? null : attributes.getNamedItem("value");
                if (value != null && value.getNodeValue() != null) {
                    values.add(value.getNodeValue());
                }
            }
        }
        return values;
    }
}
