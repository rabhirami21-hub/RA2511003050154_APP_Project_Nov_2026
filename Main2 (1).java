import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Grayscale image encryption using a 2D Henon chaotic map.
 *
 * Steps
 *  1. 2D Henon map generates chaotic series X and Y.
 *  2. X is sorted ascending; the index of each old element in the sorted
 *     series gives a permutation -> pixels are scrambled (confused image).
 *  3. Y is converted to integers 0..255 -> key image.
 *  4. confused image XOR key image -> encrypted image.
 *  5. Original / confused / encrypted images and their histograms are
 *     shown in separate windows.
 *
 * OOP concepts used
 *  - Abstraction   : ChaoticMap and CipherStage are abstract classes.
 *  - Inheritance   : HenonMap2D extends ChaoticMap; ScrambleStage and
 *                    XorStage extend CipherStage; ImagePanel and
 *                    HistogramPanel extend JPanel.
 *  - Polymorphism  : the encryptor treats every stage as a CipherStage and
 *                    calls encrypt()/decrypt() on whichever object it is.
 *  - Encapsulation : all fields are private and reached through methods.
 *
 * Compile : javac Main.java
 * Run     : java Main [path/to/image.png]
 */
public class Main2 {

    public static void main(String[] args) throws Exception {
        File file;
        if (args.length > 0) {
            file = new File(args[0]);
        } else {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select an image");
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                System.out.println("No image selected.");
                return;
            }
            file = chooser.getSelectedFile();
        }

        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            System.out.println("Could not read image: " + file);
            return;
        }

        GrayImage original = GrayImage.fromBufferedImage(src);

        // Secret key: Henon parameters and initial values
        ChaoticMap map = new HenonMap2D(1.4, 0.3, 0.1, 0.3);
        ImageEncryptor encryptor = new ImageEncryptor(map);

        GrayImage confused = encryptor.getScrambleResult(original);
        GrayImage encrypted = encryptor.encrypt(original);
        GrayImage decrypted = encryptor.decrypt(encrypted);

        System.out.println("Image size   : " + original.getWidth() + " x " + original.getHeight());
        System.out.println("Decryption OK: " + Arrays.equals(original.getPixels(), decrypted.getPixels()));

        SwingUtilities.invokeLater(() -> {
            showPanelWindow("Images: Original | Confused | Encrypted", 20, 20,
                    titled("Original Image", new ImagePanel(original, 320)),
                    titled("Confused (Scrambled) Image", new ImagePanel(confused, 320)),
                    titled("Encrypted Image", new ImagePanel(encrypted, 320)));
            showPanelWindow("Histograms: Original | Confused | Encrypted", 20, 450,
                    titled("Original", new HistogramPanel(original.histogram(), "Histogram - Original")),
                    titled("Confused", new HistogramPanel(confused.histogram(), "Histogram - Confused")),
                    titled("Encrypted", new HistogramPanel(encrypted.histogram(), "Histogram - Encrypted")));
        });
    }

    /** Wraps a panel with a titled border. */
    private static JPanel titled(String title, JPanel inner) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(BorderFactory.createTitledBorder(title));
        wrapper.add(inner, BorderLayout.CENTER);
        return wrapper;
    }

    /** One window containing the given panels side by side. */
    private static void showPanelWindow(String title, int x, int y, JPanel... panels) {
        JFrame f = new JFrame(title);
        JPanel row = new JPanel(new GridLayout(1, panels.length, 8, 0));
        row.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        for (JPanel p : panels) row.add(p);
        f.add(row);
        f.pack();
        f.setLocation(x, y);
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setVisible(true);
    }
}

/* ======================================================================
 *  Data class (Encapsulation)
 * ====================================================================== */
class GrayImage {
    private final int width;
    private final int height;
    private final int[] pixels; // values 0..255, row-major

    public GrayImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static GrayImage fromBufferedImage(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        int[] p = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                p[y * w + x] = gray.getRaster().getSample(x, y, 0);
        return new GrayImage(w, h, p);
    }

    public BufferedImage toBufferedImage() {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                img.getRaster().setSample(x, y, 0, pixels[y * width + x]);
        return img;
    }

    public int[] histogram() {
        int[] h = new int[256];
        for (int v : pixels) h[v]++;
        return h;
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }
    public int getSize()   { return pixels.length; }
    public int[] getPixels() { return pixels; } // internal use by cipher stages
}

/* ======================================================================
 *  Abstraction + Inheritance : chaotic maps
 * ====================================================================== */
abstract class ChaoticMap {
    private final double x0;
    private final double y0;
    private final int transientSteps; // iterations discarded to avoid transient effect

    protected ChaoticMap(double x0, double y0, int transientSteps) {
        this.x0 = x0;
        this.y0 = y0;
        this.transientSteps = transientSteps;
    }

    protected double getX0() { return x0; }
    protected double getY0() { return y0; }
    protected int getTransientSteps() { return transientSteps; }

    /** Next state {x, y} from the current state {x, y}. */
    protected abstract double[] next(double x, double y);

    /** Generates two chaotic series of the given length: [0] = X, [1] = Y. */
    public double[][] generate(int length) {
        double[] xs = new double[length];
        double[] ys = new double[length];
        double x = x0, y = y0;
        for (int i = 0; i < transientSteps; i++) {
            double[] s = next(x, y);
            x = s[0];
            y = s[1];
            if (Double.isNaN(x) || Math.abs(x) > 10) {
                throw new IllegalStateException("Henon map diverged: choose initial values (x0, y0) "
                        + "inside the attractor basin, e.g. (0.1, 0.3) with a=1.4, b=0.3");
            }
        }
        for (int i = 0; i < length; i++) {
            double[] s = next(x, y);
            x = s[0];
            y = s[1];
            if (Double.isNaN(x) || Double.isInfinite(x) || Math.abs(x) > 10) {
                throw new IllegalStateException("Henon map diverged: choose initial values (x0, y0) "
                        + "inside the attractor basin, e.g. (0.1, 0.3) with a=1.4, b=0.3");
            }
            xs[i] = x;
            ys[i] = y;
        }
        return new double[][] { xs, ys };
    }
}

/** 2D Henon map:  x(n+1) = 1 - a*x(n)^2 + y(n),  y(n+1) = b*x(n) */
class HenonMap2D extends ChaoticMap {
    private final double a;
    private final double b;

    public HenonMap2D(double a, double b, double x0, double y0) {
        super(x0, y0, 1000);
        this.a = a;
        this.b = b;
    }

    @Override
    protected double[] next(double x, double y) {
        return new double[] { 1.0 - a * x * x + y, b * x };
    }
}

/* ======================================================================
 *  Abstraction + Inheritance + Polymorphism : cipher stages
 * ====================================================================== */
abstract class CipherStage {
    private final String name;

    protected CipherStage(String name) { this.name = name; }

    public String getName() { return name; }

    public abstract GrayImage encrypt(GrayImage in);
    public abstract GrayImage decrypt(GrayImage in);
}

/** Confusion: pixel scrambling using the sorting index of the chaotic series. */
class ScrambleStage extends CipherStage {
    private final int[] permutation;

    public ScrambleStage(double[] chaoticSeries) {
        super("Scramble");
        this.permutation = buildPermutation(chaoticSeries);
    }

    /** permutation[i] = old index of the element that lands at position i after ascending sort. */
    private static int[] buildPermutation(double[] series) {
        Integer[] idx = new Integer[series.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> series[i]));
        int[] p = new int[idx.length];
        for (int i = 0; i < p.length; i++) p[i] = idx[i];
        return p;
    }

    @Override
    public GrayImage encrypt(GrayImage in) {
        int[] src = in.getPixels();
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[permutation[i]];
        return new GrayImage(in.getWidth(), in.getHeight(), out);
    }

    @Override
    public GrayImage decrypt(GrayImage in) {
        int[] src = in.getPixels();
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[permutation[i]] = src[i];
        return new GrayImage(in.getWidth(), in.getHeight(), out);
    }
}

/** Diffusion: XOR with a key image derived from a chaotic series. */
class XorStage extends CipherStage {
    private final int[] keyImage;

    public XorStage(double[] chaoticSeries) {
        super("XOR with key image");
        this.keyImage = toKeyImage(chaoticSeries);
    }

    /** Converts chaotic real numbers into integers 0..255. */
    private static int[] toKeyImage(double[] series) {
        int[] k = new int[series.length];
        for (int i = 0; i < k.length; i++) {
            long v = (long) Math.floor(Math.abs(series[i]) * 1e14);
            k[i] = (int) (v % 256);
        }
        return k;
    }

    public GrayImage getKeyImage(int w, int h) {
        return new GrayImage(w, h, keyImage.clone());
    }

    private GrayImage xor(GrayImage in) {
        int[] src = in.getPixels();
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] ^ keyImage[i];
        return new GrayImage(in.getWidth(), in.getHeight(), out);
    }

    @Override
    public GrayImage encrypt(GrayImage in) { return xor(in); }

    @Override
    public GrayImage decrypt(GrayImage in) { return xor(in); } // XOR is its own inverse
}

/* ======================================================================
 *  Encryptor : works with the abstract types only (Polymorphism)
 * ====================================================================== */
class ImageEncryptor {
    private final ChaoticMap map;

    public ImageEncryptor(ChaoticMap map) { this.map = map; }

    private CipherStage[] buildStages(int size) {
        double[][] series = map.generate(size);
        return new CipherStage[] {
            new ScrambleStage(series[0]), // X series -> permutation
            new XorStage(series[1])       // Y series -> key image
        };
    }

    /** Output of the first stage only (the confused image). */
    public GrayImage getScrambleResult(GrayImage img) {
        return buildStages(img.getSize())[0].encrypt(img);
    }

    public GrayImage encrypt(GrayImage img) {
        GrayImage cur = img;
        for (CipherStage s : buildStages(img.getSize())) {
            cur = s.encrypt(cur); // dynamic dispatch
        }
        return cur;
    }

    public GrayImage decrypt(GrayImage img) {
        CipherStage[] stages = buildStages(img.getSize());
        GrayImage cur = img;
        for (int i = stages.length - 1; i >= 0; i--) {
            cur = stages[i].decrypt(cur);
        }
        return cur;
    }
}

/* ======================================================================
 *  GUI components (Inheritance from JPanel)
 * ====================================================================== */
class ImagePanel extends JPanel {
    private final BufferedImage image;

    public ImagePanel(GrayImage img, int maxSide) {
        this.image = img.toBufferedImage();
        double scale = Math.min(1.0, (double) maxSide / Math.max(image.getWidth(), image.getHeight()));
        setPreferredSize(new Dimension(
                (int) (image.getWidth() * scale), (int) (image.getHeight() * scale)));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        double sc = Math.min((double) getWidth() / image.getWidth(),
                             (double) getHeight() / image.getHeight());
        int w = (int) (image.getWidth() * sc), h = (int) (image.getHeight() * sc);
        g.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
    }
}

class HistogramPanel extends JPanel {
    private final int[] hist;
    private final String title;

    public HistogramPanel(int[] hist, String title) {
        this.hist = hist;
        this.title = title;
        setPreferredSize(new Dimension(380, 300));
        setBackground(Color.WHITE);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        int left = 50, right = 20, top = 35, bottom = 40;
        int w = getWidth() - left - right;
        int h = getHeight() - top - bottom;

        int max = 1;
        for (int v : hist) max = Math.max(max, v);

        g2.setColor(Color.BLACK);
        g2.drawString(title, left, 20);
        g2.drawLine(left, top, left, top + h);
        g2.drawLine(left, top + h, left + w, top + h);

        g2.setColor(new Color(60, 90, 180));
        double barW = (double) w / 256;
        for (int i = 0; i < 256; i++) {
            int bh = (int) ((double) hist[i] / max * h);
            int x = left + (int) (i * barW);
            g2.fillRect(x, top + h - bh, Math.max(1, (int) Math.ceil(barW)), bh);
        }

        g2.setColor(Color.BLACK);
        for (int t = 0; t <= 255; t += 51) {
            int x = left + (int) (t * barW);
            g2.drawLine(x, top + h, x, top + h + 4);
            g2.drawString(String.valueOf(t), x - 8, top + h + 18);
        }
        g2.drawString("255", left + w - 12, top + h + 18);
        g2.drawString("Pixel intensity", left + w / 2 - 35, top + h + 33);
        g2.drawString(String.valueOf(max), 8, top + 5);
        g2.drawString("0", 35, top + h);
    }
}