import java.util.Random;

public class SkierRide {
    private int skierID;
    private int resortID;
    private int liftID;
    private final int seasonID = 2025;
    private final int dayID = 1;
    private int time;

    // Constructor
    public SkierRide(int skierID, int resortID, int liftID, int time) {
        this.skierID = skierID;
        this.resortID = resortID;
        this.liftID = liftID;
        this.time = time;
    }

    public static SkierRide GenerateRandomSkierRide() {
        Random rand = new Random();
        int skierID = rand.nextInt(100000) + 1;
        int resortID = rand.nextInt(10) + 1;
        int liftID = rand.nextInt(40) + 1;
        int time = rand.nextInt(360) + 1;

        return new SkierRide(skierID, resortID, liftID, time);
    }

    @Override
    public String toString() {
        return "{" +
                "\"skierID\": " + skierID +
                ", \"resortID\": " + resortID +
                ", \"liftID\": " + liftID +
                ", \"seasonID\": " + seasonID +
                ", \"dayID\": " + dayID +
                ", \"time\": " + time +
                '}';
    }
}
