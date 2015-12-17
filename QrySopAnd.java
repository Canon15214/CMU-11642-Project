/**
 * Copyright (c) 2015, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;

/*
 *  The OR operator for all retrieval models.
 */
public class QrySopAnd extends QrySop {

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch(RetrievalModel r) {
        if (r instanceof RetrievalModelIndri)
            return this.docIteratorHasMatchMin(r);
        else
            return this.docIteratorHasMatchAll(r);
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
        } else if (r instanceof RetrievalModelIndri) {
            return this.getScoreIndri(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the AND operator.");
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
        double score = 1.0;
        for (Qry q_i : this.args) {
            score *= ((QrySop)q_i).getDefaultScore(r, docid);
        }
        return Math.pow(score, 1.0 / this.args.size());
    }

    /**
     *  getScore for the UnrankedBoolean retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreUnrankedBoolean(RetrievalModel r) throws IOException {
        if (this.docIteratorHasMatchAll(r))
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
        double min_score = Integer.MAX_VALUE;
        for (Qry q_i : this.args) {
            if (q_i instanceof QrySopOr) {
                double tmp = ((QrySopOr) q_i).getScore(r);
                min_score = min_score < tmp ? min_score : tmp;
            } else if (q_i instanceof QrySopScore) {
                double tmp = ((QrySopScore) q_i).getScore(r);
                min_score = min_score < tmp ? min_score : tmp;
            } else if (q_i instanceof QrySopAnd) {
                double tmp = ((QrySopAnd) q_i).getScore(r);
                min_score = min_score < tmp ? min_score : tmp;
            }
        }
        return min_score;
    }
    /**
     *  getScore for the Indri retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IOException Error accessing the Lucene index
     */
    private double getScoreIndri(RetrievalModel r) throws IOException {
        double score = 1;
        int docid = this.docIteratorGetMatch();
        for (Qry q_i : this.args) {
            if (q_i.docIteratorHasMatch(r) && q_i.docIteratorGetMatch() == docid)
                score *= ((QrySop) q_i).getScore(r);
            else
                score *= ((QrySop) q_i).getDefaultScore(r, docid);
        }
        return Math.pow(score, 1.0 / this.args.size());
    }

}

