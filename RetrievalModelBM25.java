import java.io.IOException;

/**
 * Created by apple on 9/30/15.
 */
public class RetrievalModelBM25 extends RetrievalModel{
    public double k_1;
    public double b;
    public double k_3;
    public  long docNum;
    public RetrievalModelBM25(String k_1, String b, String k_3) throws IOException{
        this.k_1 = Double.valueOf(k_1);
        this.b = Double.valueOf(b);
        this.k_3 = Double.valueOf(k_3);
        this.docNum = Idx.getNumDocs();
    }
    public String defaultQrySopName () {
        return new String("#sum");
    }
}
