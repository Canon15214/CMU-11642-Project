/**
 * Copyright (c) 2015, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.lang.IllegalArgumentException;

/**
 *  The SCORE operator for all retrieval models.
 */
public class QrySopScore extends QrySop {

    /**
     *  Document-independent values that should be determined just once.
     *  Some retrieval models have these, some don't.
     */

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    public boolean docIteratorHasMatch(RetrievalModel r) {
        return this.docIteratorHasMatchFirst(r);
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
        } else if (r instanceof RetrievalModelBM25) {
            return this.getScoreBM25(r);
        } else if (r instanceof RetrievalModelIndri) {
            return this.getScoreIndri(r);
        } else {
            throw new IllegalArgumentException
                    (r.getClass().getName() + " doesn't support the SCORE operator.");
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
        double score = 0;

        QryIop q = (QryIop)this.args.get(0);
        double docLen = Idx.getFieldLength(q.field, docid);
        double collectLen = Idx.getSumOfFieldLengths(q.field);
        double mle = q.getCtf() / collectLen;
        score = (1 - lambda) * mu * mle / ((docLen) + mu) + lambda * mle;
        return score;
    }

    /**
     *  getScore for the Unranked retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IllegalArgumentException Error accessing the Lucene index
     */
    public double getScoreUnrankedBoolean(RetrievalModel r) throws IllegalArgumentException {
        if (this.args.size() != 1)
            throw new IllegalArgumentException("Argument size should be 1");
        Qry q = this.args.get(0);
        if (!(q instanceof QryIopTerm || q instanceof QryIopNear || q instanceof QryIopSyn))
            throw new IllegalArgumentException("Argument should be QryIopTerm or QryIopNear or QryIopSyn");
        if (q.docIteratorHasMatch(r))
            return 1;
        else
            return 0;
    }

    /**
     *  getScore for the Ranked retrieval model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IllegalArgumentException Error accessing the Lucene index
     */
    public double getScoreRankedBoolean(RetrievalModel r) throws IllegalArgumentException {
        if (this.args.size() != 1)
            throw new IllegalArgumentException("Argument size should be 1");
        Qry q = this.args.get(0);
        if (!(q instanceof QryIopTerm || q instanceof QryIopNear || q instanceof QryIopSyn))
            throw new IllegalArgumentException("Argument should be QryIopTerm or QryIopNear or QryIopSyn");
        if (q.docIteratorHasMatch(r)) {
            return ((QryIop) q).docIteratorGetMatchPosting().tf;
        } else {
            return 0;
        }
    }

    /**
     *  getScore for the BM25 model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IllegalArgumentException Error accessing the Lucene index
     */
    public double getScoreBM25(RetrievalModel r) throws IOException {
        if (this.args.size() != 1)
            throw new IllegalArgumentException("Argument size should be 1");
        Qry q = this.args.get(0);
        if (!(q instanceof QryIop))
            throw new IllegalArgumentException("Argument should be QryIop instances");
        if (q.docIteratorHasMatch(r)) {
            double idf = ((QryIop) q).idf;
            double tf = ((QryIop) q).docIteratorGetMatchPosting().tf;
            double k_1 = ((RetrievalModelBM25) r).k_1;
            double b = ((RetrievalModelBM25) r).b;
            double k_3 = ((RetrievalModelBM25) r).k_3;
            double doclen = Idx.getFieldLength(((QryIop) q).field, q.docIteratorGetMatch());
            double tfWeight = tf / (tf + k_1 * (1 - b + b * doclen / ((QryIop) q).avgLen));
            double qWeight = (k_3 + 1) * 1 / (k_3 + 1);
            return idf * tfWeight * qWeight;
        } else {
            return 0;
        }
    }
    /**
     *  getScore for the Indri model.
     *  @param r The retrieval model that determines how scores are calculated.
     *  @return The document score.
     *  @throws IllegalArgumentException Error accessing the Lucene index
     */
    public double getScoreIndri(RetrievalModel r) throws IOException {
        if (this.args.size() != 1)
            throw new IllegalArgumentException("Argument size should be 1");
        QryIop q = (QryIop)this.args.get(0);
        if (!(q instanceof QryIop))
            throw new IllegalArgumentException("Argument should be QryIop instances");
        if (q.docIteratorHasMatch(r)) {
            int docid = q.docIteratorGetMatch();
            double lambda = ((RetrievalModelIndri) r).lambda;
            double mu = ((RetrievalModelIndri) r).mu;
            double score = 0;
            double collectLen = Idx.getSumOfFieldLengths(q.field);
            double tf = q.docIteratorGetMatchPosting().tf;
            double mle = q.getCtf() / collectLen;
            double docLen = Idx.getFieldLength(q.field, docid);
            score = (1 - lambda) * (tf + mu * mle) / ((docLen) + mu) + lambda * mle;
            return score;
        } else {
            throw new IllegalArgumentException("Should have matched documents");
        }
    }

    /**

    /**
     *  Initialize the query operator (and its arguments), including any
     *  internal iterators.  If the query operator is of type QryIop, it
     *  is fully evaluated, and the results are stored in an internal
     *  inverted list that may be accessed via the internal iterator.
     *  @param r A retrieval model that guides initialization
     *  @throws IOException Error accessing the Lucene index.
     */
    public void initialize(RetrievalModel r) throws IOException {

        Qry q = this.args.get(0);
        q.initialize(r);
    }

}
