/**
 * Created by apple on 9/30/15.
 */
import java.io.*;
public class QrySopSum extends  QrySop{

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch (RetrievalModel r) {
        return this.docIteratorHasMatchMin(r);
    }

    /**
     *  Get a score for the document that docIteratorHasMatch matched.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getScore (RetrievalModel r) throws IOException {
        if (! (r instanceof RetrievalModelBM25)) {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " only support BM25 model");
        }

        double score = 0;
        int doc_id = this.docIteratorGetMatch();
        for (Qry q_i : this.args) {
            if (!(q_i instanceof  QryIop || q_i instanceof QrySop)) {
                throw new IllegalArgumentException
                        (r.getClass().getName() + " doesn't support the OR operator.");
            }
            if (q_i.docIteratorHasMatch(r) && q_i.docIteratorGetMatch() == doc_id) {
                score += ((QrySop) q_i).getScore(r);
            }
        }
        return score;
    }

    @Override
    public double getDefaultScore(RetrievalModel r, int docid) throws IOException {
        return 0;
    }

}
