package net.onelitefeather.blackhole.backend.staffprofile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Cuts the head out of a Minecraft skin: the face (8,8) with its hat layer (40,8) drawn over it, scaled to
 * 64 px with nearest-neighbour sampling. Pure image work; no network.
 */
public final class StaffHeadRenderer {

    /** The side of a stored head, in pixels. */
    public static final int SIZE = 64;

    private static final int HEAD = 8;
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int HAT_X = 40;
    private static final int HAT_Y = 8;
    private static final int MIN_WIDTH = 64;
    private static final int MIN_HEIGHT = 16;

    private StaffHeadRenderer() {
    }

    /**
     * @param skinPng a skin as PNG (64x64, or the legacy 64x32)
     * @return the head as a {@value #SIZE} px square PNG
     * @throws IllegalArgumentException when the bytes are not a PNG or JPEG image, or too small to hold a head
     */
    public static byte[] head64(byte[] skinPng) {
        BufferedImage skin;
        try {
            skin = ImageIO.read(new ByteArrayInputStream(skinPng));
        } catch (IOException e) {
            throw new IllegalArgumentException("the skin is not an image", e);
        }
        if (skin == null || skin.getWidth() < MIN_WIDTH || skin.getHeight() < MIN_HEIGHT) {
            throw new IllegalArgumentException("the skin is too small to hold a head");
        }
        BufferedImage face = new BufferedImage(HEAD, HEAD, BufferedImage.TYPE_INT_ARGB);
        Graphics2D layers = face.createGraphics();
        layers.drawImage(skin.getSubimage(FACE_X, FACE_Y, HEAD, HEAD), 0, 0, null);
        layers.drawImage(skin.getSubimage(HAT_X, HAT_Y, HEAD, HEAD), 0, 0, null);
        layers.dispose();

        BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D scale = scaled.createGraphics();
        scale.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        scale.drawImage(face, 0, 0, SIZE, SIZE, null);
        scale.dispose();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(scaled, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("writing the head failed", e);
        }
    }
}
