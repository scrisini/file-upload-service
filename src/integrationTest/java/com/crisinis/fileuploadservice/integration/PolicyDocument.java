package com.crisinis.fileuploadservice.integration;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

/**
 * A synthetic insurance policy schedule drawn in memory as a PNG: known values the model must read
 * back, and no real person's data in the repository.
 */
final class PolicyDocument {

    private PolicyDocument() {
    }

    static byte[] png(String policyNumber) {
        String[] lines = {
                "EXAMPLE MUTUAL INSURANCE LTD",
                "Home and Contents Insurance - Policy Schedule",
                "",
                "Policy number:      " + policyNumber,
                "Policy holder:      Alex Example",
                "Date of birth:      14/03/1979",
                "Insured address:    22 Test Avenue, Melbourne VIC 3000",
                "",
                "Period of cover:    01/08/2026 to 31/07/2027",
                "Annual premium:     AUD 1,456.80",
                "Sum insured:        AUD 650,000.00",
                "Excess:             AUD 750.00",
        };
        BufferedImage image = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 30));
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], 60, 90 + i * 60);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return png.toByteArray();
    }
}
