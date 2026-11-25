public class HelloFromJni {
    public static String run() {
        Runtime rt = Runtime.getRuntime();
        return "Hello from JNI, java.version=" + System.getProperty("java.version")
                + ", vm=" + System.getProperty("java.vm.name")
                + ", maxMemory=" + (rt.maxMemory() / 1024 / 1024) + "MB"
                + ", processors=" + rt.availableProcessors();
    }

    public static String runSound() {
        try {
            int mixers = javax.sound.sampled.AudioSystem.getMixerInfo().length;
            javax.sound.sampled.AudioFormat fmt =
                    new javax.sound.sampled.AudioFormat(44100f, 16, 2, true, false);
            javax.sound.sampled.DataLine.Info info =
                    new javax.sound.sampled.DataLine.Info(
                            javax.sound.sampled.SourceDataLine.class, fmt);
            if (!javax.sound.sampled.AudioSystem.isLineSupported(info)) {
                return "mixers=" + mixers + ", lineSupported=false";
            }
            javax.sound.sampled.SourceDataLine line =
                    (javax.sound.sampled.SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
            line.open(fmt, 44100 * 4);
            line.start();
            byte[] buf = new byte[44100 * 4];
            for (int i = 0; i < 44100; i++) {
                short v = (short) (Math.sin(2 * Math.PI * 880 * i / 44100.0) * 4800);
                buf[i * 4] = (byte) (v & 0xff);
                buf[i * 4 + 1] = (byte) ((v >> 8) & 0xff);
                buf[i * 4 + 2] = buf[i * 4];
                buf[i * 4 + 3] = buf[i * 4 + 1];
            }
            int written = line.write(buf, 0, buf.length);
            line.drain();
            line.stop();
            line.close();
            return "mixers=" + mixers + ", lineSupported=true, wrote=" + written;
        } catch (Throwable t) {
            return "FAILED: " + t;
        }
    }

    public static String runFonts() {
        try {
            int families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames().length;
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(240, 80, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 20));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int width = fm.stringWidth("UMC font test");
            g.drawString("UMC font test", 8, 40);
            g.dispose();
            return "families=" + families + ", stringWidth=" + width + ", ascent=" + fm.getAscent();
        } catch (Throwable t) {
            return "FAILED: " + t;
        }
    }

    public static String runProcess() {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", "echo hello-from-process")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            return "rc=" + rc + ", out=" + out;
        } catch (Throwable t) {
            return "FAILED: " + t;
        }
    }
}
