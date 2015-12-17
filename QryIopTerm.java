/**
 * Copyright (c) 2015, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.util.*;

/**
 *  The TERM operator for all retrieval models.  The TERM operator stores
 *  information about a query term, for example "apple" in the query
 *  "#AND (apple pie).  Although it may seem odd to use a query
 *  operator to store a term, doing so makes it easy to build
 *  structured queries with nested query operators.
 *
 */
public class QryIopTerm extends QryIop {

    private String term;

    /**
     *  The term is assumed to match the body field.
     *  @param termString A term string.
     */
    public QryIopTerm(String termString) {
        this.term = termString;
        this.field = "body";    // Default field if none is specified.
        this.idf = 0;
        this.avgLen = 0;
    }

    /**
     *  The term matches in the specified field.
     *  @param termString A term string.
     *  @param fieldString A field string.
     */
    public QryIopTerm(String termString, String fieldString) {
        this.term = termString;
        this.field = fieldString;
        this.idf = 0;
        this.avgLen = 0;
    }

    /**
     *  Evaluate the query operator; the result is an internal inverted
     *  list that may be accessed via the internal iterators.
     *  @throws IOException Error accessing the Lucene index.
     */
    protected void evaluate() throws IOException {
        this.invertedList = new InvList(this.term, this.field);
        int df = this.invertedList.df;
        double tmp = Math.log((Idx.getNumDocs() - df + 0.5) / (df + 0.5));
        this.idf = tmp > 0 ? tmp : 0;
        this.avgLen = Idx.getSumOfFieldLengths(this.field) / (double) Idx.getDocCount(this.field);
    }

    /**
     *  Get a string version of this query operator.
     *  @return The string version of this query operator.
     */
    public String toString() {
        return (this.term + "." + this.field);
    }
}
