package com.creditagent.kyc;

import com.creditagent.common.Ssn;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Draws a sample Social Security card for demos and tests, optionally blurred, as a PNG. The card is
 * marked SAMPLE and carries the machine-readable label {@link SsnDocumentVerifier} reads in place of
 * OCR.
 */
public final class SsnCardRenderer {

    public static final int WIDTH = 856;
    public static final int HEIGHT = 540;
    /** Largest blur radius the sample endpoint draws. */
    public static final int MAX_BLUR = 12;

    private static final Color PAPER = new Color(0xF4, 0xF1, 0xEA);
    private static final Color INK = new Color(0x1B, 0x2A, 0x4A);
    private static final Color ACCENT = new Color(0x2F, 0x5D, 0x9E);

    private SsnCardRenderer() {}

    /** A sample card for {@code ssn}; {@code blurRadius} 0 is sharp. */
    public static byte[] render(Ssn ssn, int blurRadius) {
        String digits = ssn.digits();
        String formatted = digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
        return render(formatted, label(SsnDocumentVerifier.SSN_CARD, formatted), blurRadius);
    }

    static String label(String document, String ssn) {
        return SsnDocumentVerifier.LABEL_PREFIX + " document=" + document + (ssn == null ? "" : " ssn=" + ssn);
    }

    /** A card showing {@code printed}, with {@code label} as its metadata (null for none). */
    static byte[] render(String printed, String label, int blurRadius) {
        if (blurRadius < 0 || blurRadius > MAX_BLUR) {
            throw new IllegalArgumentException("blur radius must be 0 to " + MAX_BLUR);
        }
        BufferedImage card = draw(printed);
        if (blurRadius > 0) {
            card = blur(card, blurRadius);
        }
        return png(card, label);
    }

    private static BufferedImage draw(String printed) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(PAPER);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(6));
            g.drawRoundRect(14, 14, WIDTH - 28, HEIGHT - 28, 28, 28);
            g.fillRect(14, 60, WIDTH - 28, 70);

            g.setColor(PAPER);
            centred(g, "SOCIAL SECURITY", new Font(Font.SERIF, Font.BOLD, 44), 112);
            g.setColor(INK);
            centred(g, "THIS NUMBER HAS BEEN ESTABLISHED FOR", new Font(Font.SANS_SERIF, Font.PLAIN, 22), 200);
            centred(g, printed, new Font(Font.MONOSPACED, Font.BOLD, 64), 290);
            centred(g, "SAMPLE CARDHOLDER", new Font(Font.SANS_SERIF, Font.BOLD, 30), 360);
            g.setStroke(new BasicStroke(2));
            g.drawLine(180, 440, WIDTH - 180, 440);
            centred(g, "SIGNATURE", new Font(Font.SANS_SERIF, Font.PLAIN, 16), 465);
            g.setColor(new Color(0xB0, 0x30, 0x30));
            centred(g, "SAMPLE - NOT A GOVERNMENT DOCUMENT", new Font(Font.SANS_SERIF, Font.BOLD, 18), 505);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void centred(Graphics2D g, String text, Font font, int baseline) {
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, (WIDTH - metrics.stringWidth(text)) / 2, baseline);
    }

    /** Gaussian blur with sigma = radius / 2, edges extended so the border does not go dark. */
    static BufferedImage blur(BufferedImage source, int radius) {
        int size = 2 * radius + 1;
        float sigma = Math.max(0.5f, radius / 2f);
        float[] weights = new float[size];
        float total = 0;
        for (int i = 0; i < size; i++) {
            int d = i - radius;
            weights[i] = (float) Math.exp(-(d * d) / (2 * sigma * sigma));
            total += weights[i];
        }
        for (int i = 0; i < size; i++) {
            weights[i] /= total;
        }
        BufferedImage padded = pad(source, radius);
        BufferedImage horizontal = new ConvolveOp(new Kernel(size, 1, weights), ConvolveOp.EDGE_NO_OP, null)
                .filter(padded, null);
        BufferedImage both = new ConvolveOp(new Kernel(1, size, weights), ConvolveOp.EDGE_NO_OP, null)
                .filter(horizontal, null);
        return both.getSubimage(radius, radius, source.getWidth(), source.getHeight());
    }

    private static BufferedImage pad(BufferedImage source, int by) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage out = new BufferedImage(w + 2 * by, h + 2 * by, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < out.getHeight(); y++) {
            int sy = Math.clamp(y - by, 0, h - 1);
            for (int x = 0; x < out.getWidth(); x++) {
                out.setRGB(x, y, source.getRGB(Math.clamp(x - by, 0, w - 1), sy));
            }
        }
        return out;
    }

    private static byte[] png(BufferedImage image, String label) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null);
            if (label != null) {
                IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
                entry.setAttribute("keyword", "Description");
                entry.setAttribute("value", label);
                IIOMetadataNode text = new IIOMetadataNode("tEXt");
                text.appendChild(entry);
                IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
                root.appendChild(text);
                metadata.mergeTree("javax_imageio_png_1.0", root);
            }
            writer.write(new IIOImage(image, null, metadata));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
