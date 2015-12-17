/**
 * Created by apple on 9/17/15.
 */

/**
 *  The NEAR operator for all retrieval models.  The NEAR operator stores
 *  information about its parameter terms, for example "apple pie" in the query
 *  "#NEAR/2 (apple pie).
 *
 */
import java.io.*;
import java.util.ArrayList;
import java.util.IllegalFormatCodePointException;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class QryIopNear extends QryIop {
    private int dis;

    public QryIopNear(int dis) {
        this.dis = dis;
    }

    /**
     *  Indicates whether the query has a match.
     *  @param r The retrieval model that determines what is a match
     *  @return True if the query matches, otherwise false.
     */
    //public boolean docIteratorHasMatch (RetrievalModel r) {
   // }

    /**
     *  Initialize the query operator (and its arguments), including any
     *  internal iterators; this method must be called before iteration
     *  can begin.
     *  @param r A retrieval model
     */
    public void initialize(RetrievalModel r) throws IOException {
        super.initialize(r);

        initializeNear(r);
        int df = this.invertedList.df;
        double tmp = Math.log((Idx.getNumDocs() - df + 0.5) / (df + 0.5));
        this.idf = tmp > 0 ? tmp : 0;
        this.avgLen = Idx.getSumOfFieldLengths(this.field) / (double) Idx.getDocCount(this.field);

    }
    /**
    * Initialize the Near's inverted list by merging its parameters'
    * lists.
    *
    * @param r A retrieval model
    */
    private void initializeNear(RetrievalModel r) throws IOException {
        int size = this.args.size();
        if (size < 2)
            throw new IllegalArgumentException("Near should have at least two parameters");
        int []pos = new int[size];
        QryIop q = (QryIop)this.args.get(0);
        while (q.docIteratorHasMatch(r)) {
            SortedSet<Integer> posSet = new TreeSet<Integer>();
            int doc_id = q.docIteratorGetMatch(); //get the fist doc_id
            InvList.DocPosting posting = q.docIteratorGetMatchPosting(); //get the inverted list
            for (int i = 0; i < size; i ++)
                pos[i] = 0;
            for (int i = pos[0]; i < posting.positions.size();) {
                int tmp = posting.positions.get(i);
                int re = recursiveMerge(tmp, doc_id, 1, r, pos);
                if (re >= 0) { //re represents the last position of the marged list
                    posSet.add(re);
                    pos[0] = i;
                    for (int j = 0; j < size; j++)
                        pos[j]++;
                    i = pos[0];
                } else if (re == -2) {
                    break;
                } else {
                    i ++;
                    pos[0] = i;
                }
            }
            if (posSet.size() >= 1)
                this.invertedList.appendPosting(doc_id, new ArrayList<Integer>(posSet));
            q.docIteratorAdvancePast(doc_id);
        }

    }
    /**
     * Merge two term's inverted list
     * @param pos Last inverted list
     * @param q term needed to be merged
     * @return the last position of the list if found, -2 if one of the terms
     *         doesn't exist in the document specified by doc_id.
     */
    private int recursiveMerge (int lastIndex, int doc_id, int pos, RetrievalModel r, int... loopPos) {
        if (pos == this.args.size())
            return lastIndex;
        QryIop q = (QryIop)this.args.get(pos);
        q.docIteratorAdvanceTo(doc_id);
        if (!q.docIteratorHasMatch(r) || q.docIteratorGetMatch() != doc_id)
            return -2;
        InvList.DocPosting postions = q.docIteratorGetMatchPosting();
        int size = postions.positions.size();
        int i = loopPos[pos];
        for (i = loopPos[pos]; i < size; i ++) {
            int tmp = postions.positions.get(i);
            if (tmp - lastIndex <= this.dis && tmp > lastIndex) {
                int re = recursiveMerge(tmp, doc_id, pos + 1, r, loopPos);
                if (re >= 0) {
                    loopPos[pos] = i;
                    return re;
                } else if (re == -2) {
                    return -2;
                } else {
                    return -1;
                }
            } else if (tmp - lastIndex > this.dis){
                return -1;
            }
        }

        return -1;
    }
    /**
    * Evaluate the query operator; the result is an internal inverted
    * list that may be accessed via the internal iterators.
    *
    * @throws IOException Error accessing the Lucene index.
    */
    protected void evaluate() throws IOException {
        if (this.args.size() < 2)
            throw new IllegalArgumentException("Near Operator should have at lease two arguments");
        this.invertedList = new InvList();
    }

    /**
     * Get a string version of this query operator.
     *
     * @return The string version of this query operator.
     */
    public String toString() {
        return ("near/" + dis);
    }
}
