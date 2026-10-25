package net.onelitefeather.blackhole.backend.staffprofile;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Cropping the head out of a skin: pure image work, no network. */
class StaffHeadRendererTest {

    /** A blank skin of the given size, as PNG bytes. */
    static byte[] skin(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aHeadOfA64PxSkinIs64PxSquare() throws IOException {
        BufferedImage head = ImageIO.read(new ByteArrayInputStream(StaffHeadRenderer.head64(skin(64, 64))));

        assertEquals(64, head.getWidth());
        assertEquals(64, head.getHeight());
    }

    @Test
    void aSkinTooSmallToHoldAHeadIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> StaffHeadRenderer.head64(skin(8, 8)));
    }
}
