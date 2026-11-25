import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;

/**
 * Runtime feature probe: AWT fonts and javax.sound.
 * Run on the device with bin/java; prints the result or exception of each check.
 */
public class FeatureProbe {
    public static void main(String[] args) {
        System.out.println("== AWT ==");
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            System.out.println("headless=" + GraphicsEnvironment.isHeadless());
            String[] fams = ge.getAvailableFontFamilyNames();
            System.out.println("font families=" + fams.length);
            for (int i = 0; i < Math.min(8, fams.length); i++) {
                System.out.println("  - " + fams[i]);
            }
        } catch (Throwable t) {
            System.out.println("font enumeration FAILED: " + t);
        }

        try {
            BufferedImage img = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("UMC font test", 8, 40);
            System.out.println("drawString ok, stringWidth=" + fm.stringWidth("UMC font test")
                    + ", ascent=" + fm.getAscent());
            g.dispose();
        } catch (Throwable t) {
            System.out.println("drawString FAILED: " + t);
        }

        System.out.println("== SOUND ==");
        try {
            Mixer.Info[] mixers = AudioSystem.getMixerInfo();
            System.out.println("mixers=" + mixers.length);
            for (Mixer.Info mi : mixers) {
                System.out.println("  - " + mi.getName() + " / " + mi.getDescription());
            }
        } catch (Throwable t) {
            System.out.println("AudioSystem FAILED: " + t);
        }

        try {
            javax.sound.sampled.AudioFormat fmt =
                    new javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false);
            javax.sound.sampled.DataLine.Info info =
                    new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, fmt);
            System.out.println("lineSupported=" + AudioSystem.isLineSupported(info));
            javax.sound.sampled.SourceDataLine line =
                    (javax.sound.sampled.SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, 44100 * 4);
            line.start();
            // One second of a 440 Hz sine wave at 20% amplitude.
            byte[] buf = new byte[44100 * 4];
            for (int i = 0; i < 44100; i++) {
                short v = (short) (Math.sin(2 * Math.PI * 440 * i / 44100.0) * 6553);
                buf[i * 4] = (byte) (v & 0xff);
                buf[i * 4 + 1] = (byte) ((v >> 8) & 0xff);
                buf[i * 4 + 2] = buf[i * 4];
                buf[i * 4 + 3] = buf[i * 4 + 1];
            }
            int written = line.write(buf, 0, buf.length);
            line.drain();
            line.stop();
            line.close();
            System.out.println("playback ok, wrote=" + written);
        } catch (Throwable t) {
            System.out.println("playback FAILED: " + t);
        }

        System.out.println("PROBE DONE");
    }
}
