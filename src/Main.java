import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

public class Main {

    private static final int WARMUP_ITERS  = 10;
    private static final int MEASURE_ITERS = 15;

    private static final double TURN_EPS = 1e-4;
    private static final double TWO_PI   = 2.0 * Math.PI;

    private static volatile long SINK = 0;

    public static void main(String[] args) throws Exception {
        int[] sizes = {1_000, 10_000, 100_000, 1_000_000, 5_000_000, 10_000_000};
        int[] threadCounts = {1, 2, 4, 6, 8, 12, 16};

        int cores = Runtime.getRuntime().availableProcessors();
        int maxThreads = cores;
        for (int t : threadCounts) maxThreads = Math.max(maxThreads, t);

        Runtime rt = Runtime.getRuntime();
        String env = String.format(Locale.US,
                "JVM=%s %s; OS=%s %s (%s); availableProcessors=%d; maxMemory=%.1f GB",
                System.getProperty("java.vendor"), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), cores, rt.maxMemory() / (1024.0 * 1024 * 1024));
        System.out.println(env);
        System.out.println("Olcum: " + MEASURE_ITERS + " tekrarin medyani (warmup: "
                + WARMUP_ITERS + " tur), TEK paylasilan havuz.");
        System.out.println();

        runCorrectnessTests();
        System.out.println();

        ExecutorService pool = Executors.newFixedThreadPool(maxThreads);

        try (PrintWriter sizeCsv = newCsv("results.csv");
             PrintWriter thrCsv  = newCsv("results_threads.csv")) {

            sizeCsv.println("N,cores,seq_med_ms,seq_min_ms,seq_std_ms,par_med_ms,par_min_ms,par_std_ms,speedup,efficiency,convex");
            thrCsv.println("threads,seq_med_ms,par_med_ms,par_std_ms,speedup,efficiency,karp_flatt");

            System.out.println("Veri buyuklugune gore olceklenme (C = " + cores + " thread):");
            System.out.printf("%-12s | %-13s | %-13s | %-8s | %-8s | %-8s%n",
                    "Nokta (N)", "Senkron (ms)", "Paralel (ms)", "Speedup", "Verim", "Convex?");
            System.out.println("-------------+---------------+---------------+----------+----------+--------");
            for (int n : sizes) {
                List<Point> poly = generateConvexPolygon(n);
                runBenchmark(n, cores, poly, pool, sizeCsv);
            }

            int bigN = 10_000_000;
            List<Point> bigPoly = generateConvexPolygon(bigN);
            System.out.println();
            System.out.println("Thread sayisina gore olceklenme (N = " + fmt(bigN) + "):");
            System.out.printf("%-9s | %-13s | %-13s | %-8s | %-8s | %-10s%n",
                    "Threads", "Senkron (ms)", "Paralel (ms)", "Speedup", "Verim", "Karp-Flatt");
            System.out.println("----------+---------------+---------------+----------+----------+-----------");
            for (int t : threadCounts) {
                runThreadScaling(bigPoly, t, pool, thrCsv);
            }
        } finally {
            pool.shutdown();
        }

        System.out.println();
        System.out.println("results.csv ve results_threads.csv yazildi. (SINK=" + SINK + ")");
    }

    private static void runCorrectnessTests() {
        System.out.println("--- Dogruluk testleri ---");

        check("Duzgun besgen (convex)",       decide(serial(generateConvexPolygon(5))),    true);
        check("Cember 1000-gen (convex)",     decide(serial(generateConvexPolygon(1000))), true);
        check("Concave V ornegi",             decide(serial(makeConcaveSample())),         false);
        check("Pentagram / yildiz (kesisen)", decide(serial(makePentagram())),             false);
        check("Tum-dogrusal (dejenere)",      decide(serial(makeCollinearSample())),       false);

        boolean threw = false;
        try { decide(serial(List.of(new Point(0, 0), new Point(1, 1)))); }
        catch (IllegalArgumentException e) { threw = true; }
        check("n<3 -> IllegalArgumentException", threw, true);
    }

    private static void check(String name, boolean actual, boolean expected) {
        System.out.printf("  [%s] %-34s (beklenen: %s, gelen: %s)%n",
                actual == expected ? "GECTI" : "KALDI", name, expected, actual);
        if (actual != expected) {
            throw new IllegalStateException("Dogruluk testi BASARISIZ: " + name);
        }
    }

    private static void runBenchmark(int n, int cores, List<Point> points,
                                     ExecutorService pool, PrintWriter csv) throws Exception {
        for (int i = 0; i < WARMUP_ITERS; i++) {
            consume(serial(points));
            consume(parallel(points, cores, pool));
        }

        double[] seq = new double[MEASURE_ITERS];
        double[] par = new double[MEASURE_ITERS];
        boolean convex = true;

        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            List<ChunkResult> sRes = serial(points);
            long t1 = System.nanoTime();
            seq[i] = (t1 - t0) / 1_000_000.0;

            long t2 = System.nanoTime();
            List<ChunkResult> pRes = parallel(points, cores, pool);
            long t3 = System.nanoTime();
            par[i] = (t3 - t2) / 1_000_000.0;

            boolean sConvex = decide(sRes);
            boolean pConvex = decide(pRes);
            consume(sRes); consume(pRes);
            if (sConvex != pConvex) throw new IllegalStateException("Senkron/paralel sonuc uyusmuyor!");
            convex = pConvex;
        }

        double seqMed = median(seq), parMed = median(par);
        double speedup = seqMed / parMed;
        double eff = speedup / cores;

        System.out.printf(Locale.US, "%-12s | %-13.3f | %-13.3f | %-8.2f | %-8.2f | %-8s%n",
                fmt(n), seqMed, parMed, speedup, eff, convex);
        csv.printf(Locale.US, "%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%b%n",
                n, cores, seqMed, min(seq), std(seq), parMed, min(par), std(par), speedup, eff, convex);
    }

    private static void runThreadScaling(List<Point> points, int threads,
                                         ExecutorService pool, PrintWriter csv) throws Exception {
        for (int i = 0; i < WARMUP_ITERS; i++) {
            consume(serial(points));
            consume(parallel(points, threads, pool));
        }

        double[] seq = new double[MEASURE_ITERS];
        double[] par = new double[MEASURE_ITERS];
        for (int i = 0; i < MEASURE_ITERS; i++) {
            long t0 = System.nanoTime();
            List<ChunkResult> sRes = serial(points);
            long t1 = System.nanoTime();
            seq[i] = (t1 - t0) / 1_000_000.0;

            long t2 = System.nanoTime();
            List<ChunkResult> pRes = parallel(points, threads, pool);
            long t3 = System.nanoTime();
            par[i] = (t3 - t2) / 1_000_000.0;

            consume(sRes); consume(pRes);
        }

        double seqMed = median(seq), parMed = median(par);
        double speedup = seqMed / parMed;
        double eff = speedup / threads;
        String kf = threads == 1 ? "-"
                : String.format(Locale.US, "%.4f", (1.0 / speedup - 1.0 / threads) / (1.0 - 1.0 / threads));

        System.out.printf(Locale.US, "%-9d | %-13.3f | %-13.3f | %-8.2f | %-8.2f | %-10s%n",
                threads, seqMed, parMed, speedup, eff, kf);
        csv.printf(Locale.US, "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%s%n",
                threads, seqMed, parMed, std(par), speedup, eff, kf);
    }

    private static List<ChunkResult> serial(List<Point> points) {
        requirePolygon(points);
        return List.of(new ConvexCheckerTask(points, 0, points.size()).call());
    }

    private static List<ChunkResult> parallel(List<Point> points, int numTasks,
                                              ExecutorService pool) throws Exception {
        requirePolygon(points);
        int n = points.size();
        int tasks = Math.min(numTasks, n);
        int chunk = n / tasks;

        List<Future<ChunkResult>> futures = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            int start = i * chunk;
            int end = (i == tasks - 1) ? n : start + chunk;
            futures.add(pool.submit(new ConvexCheckerTask(points, start, end)));
        }
        List<ChunkResult> results = new ArrayList<>(tasks);
        for (Future<ChunkResult> f : futures) results.add(f.get());
        return results;
    }

    static boolean decide(List<ChunkResult> chunks) {
        int globalSign = 0;
        double totalTurn = 0.0;
        for (ChunkResult r : chunks) {
            if (r.mixed()) return false;
            totalTurn += r.turnSum();
            if (r.sign() == 0) continue;
            if (globalSign == 0) globalSign = r.sign();
            else if (globalSign != r.sign()) return false;
        }
        if (globalSign == 0) return false;
        return Math.abs(Math.abs(totalTurn) - TWO_PI) < TURN_EPS;
    }

    private static void requirePolygon(List<Point> points) {
        if (points.size() < 3) {
            throw new IllegalArgumentException("Bir poligon en az 3 nokta gerektirir (n="
                    + points.size() + ")");
        }
    }

    private static void consume(List<ChunkResult> chunks) {
        long h = 0;
        for (ChunkResult r : chunks) h += Double.doubleToRawLongBits(r.turnSum()) ^ r.sign();
        SINK += h;
    }

    private static double median(double[] xs) {
        double[] c = xs.clone();
        java.util.Arrays.sort(c);
        int m = c.length / 2;
        return (c.length % 2 == 0) ? (c[m - 1] + c[m]) / 2.0 : c[m];
    }

    private static double min(double[] xs) {
        double m = xs[0];
        for (double x : xs) m = Math.min(m, x);
        return m;
    }

    private static double std(double[] xs) {
        double mean = 0;
        for (double x : xs) mean += x;
        mean /= xs.length;
        double v = 0;
        for (double x : xs) v += (x - mean) * (x - mean);
        return Math.sqrt(v / xs.length);
    }

    private static List<Point> generateConvexPolygon(int n) {
        List<Point> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double a = TWO_PI * i / n;
            pts.add(new Point(Math.cos(a) * 100, Math.sin(a) * 100));
        }
        return pts;
    }

    private static List<Point> makeConcaveSample() {
        return List.of(new Point(0, 0), new Point(4, 0), new Point(4, 4),
                       new Point(2, 2), new Point(0, 4));
    }

    private static List<Point> makePentagram() {
        List<Point> outer = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            double a = Math.PI / 2 + TWO_PI * i / 5;
            outer.add(new Point(Math.cos(a) * 100, Math.sin(a) * 100));
        }
        List<Point> star = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) star.add(outer.get((i * 2) % 5));
        return star;
    }

    private static List<Point> makeCollinearSample() {
        return List.of(new Point(0, 0), new Point(1, 0), new Point(2, 0), new Point(3, 0));
    }

    private static PrintWriter newCsv(String name) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(Path.of(name), StandardCharsets.UTF_8));
    }

    private static String fmt(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }
}
