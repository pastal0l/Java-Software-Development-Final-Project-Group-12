package domain;
public class Ball {
    public final double x;
    public final double y;

    // domain/Ball.java — add these two methods
    public double getX() { return x; }
    public double getY() { return y; }

    public Ball(double x, double y) {
        this.x = x;
        this.y = y;
    }
}
