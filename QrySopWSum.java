/**
 * Created by apple on 10/23/15.
 */
import java.io.*;
import java.util.*;

public class QrySopWSum extends QryWSop{
    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch(RetrievalModel r) {
        if (r instanceof RetrievalModelIndri)
            return this.docIteratorHasMatchMin(r);
        else
            throw new IllegalArgumentException("WAnd only supports Indri!");
    }

    /**
     *  Get a score for the document that docIteratorHasMatch matched.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getScore(RetrievalModel r) throws IOException {

        if (r instanceof RetrievalModelIndri) {
            return this.getScoreIndri(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " only supports Indri");
        }
    }
    /**
     *  Get a default score for the document.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getDefaultScore(RetrievalModel r, int docid) throws IOException {
        double score = 0.0;
        int index = this.weightArray.size() - 1;
        for (Qry q_i : this.args) {
            double weight = (double)(this.weightArray.get(index--));
            score += (((QrySop) q_i).getDefaultScore(r, docid) * weight / this.sumOfWeight);
        }
        return score;
    }


    /**
     *  getScore for the Indri retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreIndri(RetrievalModel r) throws IOException {
        double score = 0.0;
        int docid = this.docIteratorGetMatch();
        int index = this.weightArray.size() - 1;
        for (Qry q_i : this.args) {
            double weight = (double)(this.weightArray.get(index--));
            if (q_i.docIteratorHasMatch(r) && q_i.docIteratorGetMatch() == docid)
                score += (((QrySop) q_i).getScore(r) * weight / this.sumOfWeight);
            else
                score += (((QrySop) q_i).getDefaultScore(r, docid) * weight / this.sumOfWeight);
        }
        return score;
    }
}


