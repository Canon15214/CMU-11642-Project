/**
 * Copyright (c) 2015, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;

/**
 *  The OR operator for all retrieval models.
 */
public class QrySopOr extends QrySop {

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch(RetrievalModel r) {
        return this.docIteratorHasMatchMin(r);
    }

    /**
     *  Get a score for the document that docIteratorHasMatch matched.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getScore(RetrievalModel r) throws IOException {

        if (r instanceof RetrievalModelUnrankedBoolean) {
            return this.getScoreUnrankedBoolean(r);
        } else if (r instanceof RetrievalModelRankedBoolean) {
            return this.getScoreRankedBoolean(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the OR operator.");
        }
    }

    /**
     *  Get a default score for the document.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    public double getDefaultScore(RetrievalModel r, int docid) throws IOException {
        double lambda = ((RetrievalModelIndri) r).lambda;
        double mu = ((RetrievalModelIndri) r).mu;
        return 0.0;
    }

    /**
     *  getScore for the UnrankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreUnrankedBoolean(RetrievalModel r) throws IOException {
        if (this.docIteratorHasMatch(r))
            return 1.0;
        else
            return 0.0;
    }

    /**
     *  getScore for the RankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreRankedBoolean(RetrievalModel r) throws IOException {
        int max_score = Integer.MIN_VALUE;
        int doc_id = this.docIteratorGetMatch();
        for (Qry q_i : this.args) {
            if (q_i instanceof QrySopOr) {
                if (q_i.docIteratorGetMatch() != doc_id) //check if parameters exist in doc_id
                    continue;
                ;
                int tmp = (int) ((QrySopOr) q_i).getScore(r);
                max_score = max_score > tmp ? max_score : tmp;
            } else if (q_i instanceof QrySopScore) { //check if parameters exist in doc_id
                if (q_i.args.get(0).docIteratorHasMatch(r) && q_i.args.get(0).docIteratorGetMatch() != doc_id)
                    continue;
                int tmp = (int) ((QrySopScore) q_i).getScore(r);
                max_score = max_score > tmp ? max_score : tmp;
            } else if (q_i instanceof QrySopAnd) { //check if parameters exist in doc_id
                if (!q_i.docIteratorHasMatch(r) || q_i.docIteratorGetMatch() != doc_id)
                    continue;
                int tmp = (int) ((QrySopAnd) q_i).getScore(r);
                max_score = max_score > tmp ? max_score : tmp;
            }
        }
        return max_score;
    }

}
