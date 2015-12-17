/**
 * Created by apple on 10/3/15.
 */
public class RetrievalModelIndri extends RetrievalModel{
    public double mu;
    public double lambda;
    public RetrievalModelIndri(String mu, String lambda) {
        this.mu = Double.valueOf(mu);
        this.lambda = Double.valueOf(lambda);
    }
    public String defaultQrySopName () {
        return new String("#and");
    }

}
