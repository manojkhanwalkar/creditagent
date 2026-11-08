package com.creditagent.kyc;

import com.creditagent.kyc.Verification.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * An uploaded image, decoded defensively: the format comes from the file's magic bytes (not its name
 * or declared type), only PNG and JPEG are accepted, and the pixel count is checked from the header
 * before any pixels are decoded. Large images are decoded subsampled to at most {@link
 * #MAX_DECODED_PIXELS}, so a small, highly compressed file cannot make the service allocate hundreds of
 * megabytes (a 40-megapixel, 16-bit RGBA PNG is about 300 KB on disk and 320 MB decoded).
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

    /**
     * Most pixels ever decoded. The blur check works at {@link BlurDetector#WORK_WIDTH} pixels wide,
     * so decoding more than about twice that in each direction only costs memory and time.
     */
    static final long MAX_DECODED_PIXELS = 4L * BlurDetector.WORK_WIDTH * BlurDetector.WORK_WIDTH;

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
            ImageReadParam param = reader.getDefaultReadParam();
            int step = subsampling(width, height);
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            BufferedImage pixels = reader.read(0, param);
            return new DocumentImage(pixels, text(reader.getImageMetadata(0)));
        } catch (IOException | RuntimeException e) {
            // Truncated or corrupt data, or a decoder limit: either way, not a usable photo.
            throw new Unreadable(Reason.UNSUPPORTED_IMAGE);
        } finally {
            reader.dispose();
        }
    }

    /** The smallest step that keeps a {@code width} × {@code height} image within {@link #MAX_DECODED_PIXELS}. */
    static int subsampling(long width, long height) {
        int step = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / MAX_DECODED_PIXELS)));
        while (Math.ceilDiv(width, step) * Math.ceilDiv(height, step) > MAX_DECODED_PIXELS) {
            step++;
        }
        return step;
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
