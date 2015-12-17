import java.util.ArrayList;

/**
 * Created by apple on 10/23/15.
 */
import java.util.*;
public abstract class QryWSop extends QrySop{
    public ArrayList<Double> weightArray;
    public double sumOfWeight;
    public void setWeightArray(ArrayList<Double>weightArray) {
        this.weightArray = weightArray;
    }
    public void setSumOfWeight(double sum) {
        this.sumOfWeight = sum;
    }
}
