import java.util.List;
import java.util.concurrent.Callable;

public class ConvexCheckerTask implements Callable<ChunkResult> {

    public static final int SIGN_NONE  =  0;
    public static final int SIGN_LEFT  = +1;
    public static final int SIGN_RIGHT = -1;

    private final List<Point> points;
    private final int startIndex;
    private final int endIndex;

    public ConvexCheckerTask(List<Point> points, int startIndex, int endIndex) {
        this.points = points;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
    }

    @Override
    public ChunkResult call() {
        final int n = points.size();
        boolean sawPositive = false;
        boolean sawNegative = false;
        double turnSum = 0.0;

        for (int i = startIndex; i < endIndex; i++) {
            Point p1 = points.get(i);
            Point p2 = points.get((i + 1) % n);
            Point p3 = points.get((i + 2) % n);

            double e1x = p2.x - p1.x, e1y = p2.y - p1.y;
            double e2x = p3.x - p2.x, e2y = p3.y - p2.y;

            double cross = e1x * e2y - e1y * e2x;
            double dot   = e1x * e2x + e1y * e2y;

            if (cross > 0) sawPositive = true;
            else if (cross < 0) sawNegative = true;

            turnSum += Math.atan2(cross, dot);

            if (sawPositive && sawNegative) {
                return new ChunkResult(SIGN_NONE, turnSum, true);
            }
        }

        int sign = sawPositive ? SIGN_LEFT : (sawNegative ? SIGN_RIGHT : SIGN_NONE);
        return new ChunkResult(sign, turnSum, false);
    }
}
