package com.creditagent.kyc;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Sharpness as the variance of the Laplacian of the grey-scale image (Pech-Pacheco et al., 2000), the
 * usual quick blur check in document capture. Sharp edges, such as printed text, give large second
 * derivatives and a high variance; a blurred image is smooth and scores low.
 *
 * <p>The score depends on resolution, so large images are first scaled to {@link #WORK_WIDTH} pixels
 * wide. That also bounds the cost of the check.
 */
public final class BlurDetector {

    static final int WORK_WIDTH = 1000;

    private BlurDetector() {}

    public static double sharpness(BufferedImage image) {
        int[][] grey = grey(scaled(image));
        int height = grey.length;
        int width = grey[0].length;
        if (width < 3 || height < 3) {
            return 0;
        }
        double sum = 0;
        double sumSquares = 0;
        long n = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int laplacian = grey[y - 1][x] + grey[y + 1][x] + grey[y][x - 1] + grey[y][x + 1] - 4 * grey[y][x];
                sum += laplacian;
                sumSquares += (double) laplacian * laplacian;
                n++;
            }
        }
        double mean = sum / n;
        return sumSquares / n - mean * mean;
    }

    private static BufferedImage scaled(BufferedImage image) {
        if (image.getWidth() <= WORK_WIDTH) {
            return image;
        }
        int height = Math.max(1, (int) Math.round((double) image.getHeight() * WORK_WIDTH / image.getWidth()));
        BufferedImage out = new BufferedImage(WORK_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, WORK_WIDTH, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** ITU-R BT.601 luma, 0–255. */
    private static int[][] grey(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] row = new int[width];
        int[][] grey = new int[height][width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int rgb = row[x];
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                grey[y][x] = (299 * r + 587 * g + 114 * b) / 1000;
            }
        }
        return grey;
    }
}
