/*
 *  Copyright (c) 2015, Carnegie Mellon University.  All Rights Reserved.
 *  Version 3.1.
 */

import java.io.*;
import java.util.*;
import java.io.PrintWriter;
import java.util.concurrent.ExecutionException;

import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * QryEval is a simple application that reads queries from a file,
 * evaluates them against an index, and writes the results to an
 * output file.  This class contains the main method, a method for
 * reading parameter and query files, initialization methods, a simple
 * query parser, a simple query processor, and methods for reporting
 * results.
 * <p>
 * This software illustrates the architecture for the portion of a
 * search engine that evaluates queries.  It is a guide for class
 * homework assignments, so it emphasizes simplicity over efficiency.
 * Everything could be done more efficiently and elegantly.
 * <p>
 * The {@link Qry} hierarchy implements query evaluation using a
 * 'document at a time' (DaaT) methodology.  Initially it contains an
 * #OR operator for the unranked Boolean retrieval model and a #SYN
 * (synonym) operator for any retrieval model.  It is easily extended
 * to support additional query operators and retrieval models.  See
 * the {@link Qry} class for details.
 * <p>
 * The {@link RetrievalModel} hierarchy stores parameters and
 * information required by different retrieval models.  Retrieval
 * models that need these parameters (e.g., BM25 and Indri) use them
 * very frequently, so the RetrievalModel class emphasizes fast access.
 * <p>
 * The {@link Idx} hierarchy provides access to information in the
 * Lucene index.  It is intended to be simpler than accessing the
 * Lucene index directly.
 * <p>
 * As the search engine becomes more complex, it becomes useful to
 * have a standard approach to representing documents and scores.
 * The {@link ScoreList} class provides this capability.
 */
public class QryEval {

    //  --------------- Constants and variables ---------------------

    private static final String USAGE =
            "Usage:  java QryEval paramFile\n\n";

    private static final EnglishAnalyzerConfigurable ANALYZER =
            new EnglishAnalyzerConfigurable(Version.LUCENE_43);
    private static final String[] TEXT_FIELDS =
            {"body", "title", "url", "inlink"};

    private static PrintWriter writer;
    private static boolean fb = false;
    private static String fbRankingFile = "";
    private static String fbExpansionQueryFile = "";
    private static int fbDocs = -1;
    private static int fbTerms = -1;
    private static double fbMu =-1;
    private static double fbOrigWeight = -1;
    private static String globalExpandedQuery = "";
    private static Map<String, ScoreList>scoreListData = new HashMap<>();

    //  --------------- Methods ---------------------------------------

    /**
     * @param args The only argument is the parameter file name.
     * @throws Exception Error accessing the Lucene index.
     */
    public static void main(String[] args) throws Exception {
        //  This is a timer that you may find useful.  It is used here to
        //  time how long the entire program takes, but you can move it
        //  around to time specific parts of your code.

        Timer timer = new Timer();
        timer.start();

        //  Check that a parameter file is included, and that the required
        //  parameters are present.  Just store the parameters.  They get
        //  processed later during initialization of different system
        //  components.

        if (args.length < 1) {
            throw new IllegalArgumentException(USAGE);
        }

        Map<String, String> parameters = readParameterFile(args[0]);

        //  Configure query lexical processing to match index lexical
        //  processing.  Initialize the index and retrieval model.

        ANALYZER.setLowercase(true);
        ANALYZER.setStopwordRemoval(true);
        ANALYZER.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);

        Idx.initialize(parameters.get("indexPath"));
        RetrievalModel model = initializeRetrievalModel(parameters);
//        File trecEval = new File(parameters.get("trecEvalOutputPath"));
 //       PrintStream out = new PrintStream(new FileOutputStream(trecEval));
        writer = new PrintWriter(parameters.get("trecEvalOutputPath"), "UTF-8");

        //  Perform experiments.
        if (fb && !fbRankingFile.equals(""))
            readDocumentRanking(fbRankingFile);
        processQueryFile(parameters.get("queryFilePath"), model);

        //  Clean up.

        timer.stop();
        //writer.print("Time: " + timer );
        System.out.println("Time: " + timer);
        writer.close();
    }

    /**
     * Allocate the retrieval model and initialize it using parameters
     * from the parameter file.
     *
     * @return The initialized retrieval model
     * @throws IOException Error accessing the Lucene index.
     */
    private static RetrievalModel initializeRetrievalModel(Map<String, String> parameters)
            throws IOException {

        RetrievalModel model = null;
        String modelString = parameters.get("retrievalAlgorithm").toLowerCase();

        if (modelString.equals("unrankedboolean")) {
            model = new RetrievalModelUnrankedBoolean();
        } else if (modelString.equals("rankedboolean")) {
            model = new RetrievalModelRankedBoolean();
        } else if (modelString.equals("bm25")) {
            String k_1 = parameters.get("BM25:k_1");
            String b = parameters.get("BM25:b");
            String k_3 = parameters.get("BM25:k_3");
            model = new RetrievalModelBM25(k_1, b, k_3);
        } else if (modelString.equals("indri")) {
            String mu = parameters.get("Indri:mu");
            String lambda = parameters.get("Indri:lambda");
            model = new RetrievalModelIndri(mu, lambda);
        } else {
            throw new IllegalArgumentException
                    ("Unknown retrieval model " + parameters.get("retrievalAlgorithm"));
        }

        return model;
    }

    /**
     * Return a query tree that corresponds to the query.
     *
     * @param qString A string containing a query.
     * @param qTree   A query tree
     * @throws IOException Error accessing the Lucene index.
     */
    static Qry parseQuery(String qString, RetrievalModel model) throws IOException {

        //  Add a default query operator to every query. This is a tiny
        //  bit of inefficiency, but it allows other code to assume
        //  that the query will return document ids and scores.

        String defaultOp = model.defaultQrySopName();
        qString = defaultOp + "(" + qString + ")";

        //  Simple query tokenization.  Terms like "near-death" are handled later.

        StringTokenizer tokens = new StringTokenizer(qString, "\t\n\r ,()", true);
        String token = null;

        //  This is a simple, stack-based parser.  These variables record
        //  the parser's state.

        Qry currentOp = null;
        Stack<Qry> opStack = new Stack<Qry>();
        boolean weightExpected = false;
        Stack<Double> weightStack = new Stack<>();

        //  Each pass of the loop processes one token. The query operator
        //  on the top of the opStack is also stored in currentOp to
        //  make the code more readable.

        while (tokens.hasMoreTokens()) {

            token = tokens.nextToken();
            //System.out.println(token);

            if (token.matches("[ ,(\t\n\r]")) {
                continue;
            } else if (token.equals(")")) {    // Finish current query op.

                // If the current query operator is not an argument to another
                // query operator (i.e., the opStack is empty when the current
                // query operator is removed), we're done (assuming correct
                // syntax - see below).
                if (currentOp instanceof QryWSop) {
                    ArrayList<Double> weightArray = new ArrayList<>();
                    double sum = 0;
                    for (int i = 0; i < currentOp.args.size(); i ++) {
                        double weight = weightStack.peek();
                        sum += weight;
                        weightArray.add(weight);
                        weightStack.pop();
                    }
                    ((QryWSop)currentOp).setWeightArray(weightArray);
                    ((QryWSop)currentOp).setSumOfWeight(sum);
                }

                opStack.pop();

                if (opStack.empty())
                    break;

                // Not done yet.  Add the current operator as an argument to
                // the higher-level operator, and shift processing back to the
                // higher-level operator.
                Qry arg = currentOp;
                currentOp = opStack.peek();
                currentOp.appendArg(arg);
                if (currentOp instanceof QryWSop)
                    weightExpected = true;

            } else if (token.equalsIgnoreCase("#or")) {
                currentOp = new QrySopOr();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#and")) {
                currentOp = new QrySopAnd();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#wand")) {
                currentOp = new QrySopWAnd();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
                weightExpected = true;
            } else if (token.equalsIgnoreCase("#sum")) {
                currentOp = new QrySopSum();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#wsum")) {
                currentOp = new QrySopWSum();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
                weightExpected = true;
            } else if (token.toLowerCase().contains("#near")) {
                int delimiter = token.indexOf('/');
                if (delimiter < 0)
                    throw new IllegalArgumentException("Near Operator needs a parameter");
                int dis = Integer.parseInt(token.substring(delimiter + 1));

                currentOp = new QryIopNear(dis);
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.toLowerCase().contains("#window")) {
                int delimiter = token.indexOf('/');
                if (delimiter < 0)
                    throw new IllegalArgumentException("Near Operator needs a parameter");
                int dis = Integer.parseInt(token.substring(delimiter + 1));

                currentOp = new QryIopWindow(dis);
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else if (token.equalsIgnoreCase("#syn")) {
                currentOp = new QryIopSyn();
                currentOp.setDisplayName(token);
                opStack.push(currentOp);
            } else {
                if (currentOp instanceof QryWSop) {
                    try {
                        if (weightExpected) {
                            double weight = Double.parseDouble(token);
                        /*if (weight < 1 && weight >= 0) {
                            weightStack.push(weight);
                            continue;
                        }*/
                            weightStack.push(weight);
                            weightExpected = false;
                            continue;
                        }
                    } catch (NumberFormatException e) {

                    }
                }



                //  Split the token into a term and a field.

                int delimiter = token.indexOf('.');
                String field = null;
                String term = null;
                if (!fb) {
                    if (delimiter < 0) {
                        field = "body";
                        term = token;
                    } else {
                        field = token.substring(delimiter + 1).toLowerCase();
                        term = token.substring(0, delimiter);
                    }
                    if ((field.compareTo("url") != 0) &&
                            (field.compareTo("keywords") != 0) &&
                            (field.compareTo("title") != 0) &&
                            (field.compareTo("body") != 0) &&
                            (field.compareTo("inlink") != 0)) {
                        throw new IllegalArgumentException("Error: Unknown field " + token);
                    }
                } else {
                    term = token;
                    field = "body";
                }

                //  Lexical processing, stopwords, stemming.  A loop is used
                //  just in case a term (e.g., "near-death") gets tokenized into
                //  multiple terms (e.g., "near" and "death").

                String t[] = tokenizeQuery(term);
                if (currentOp instanceof QryWSop)
                    weightExpected = true;

                for (int j = 0; j < t.length; j++) {
                    Qry termOp = new QryIopTerm(t[j], field);
                    currentOp.appendArg(termOp);
                }
                if (currentOp instanceof QryWSop && t.length == 0)
                    weightStack.pop();
            }
        }


        //  A broken structured query can leave unprocessed tokens on the opStack,

        if (tokens.hasMoreTokens()) {
            throw new IllegalArgumentException
                    ("Error:  Query syntax is incorrect.  " + qString);
        }

        return currentOp;
    }

    /**
     * Remove degenerate nodes produced during query parsing, for
     * example #NEAR/1 (of the) that can't possibly match. It would be
     * better if those nodes weren't produced at all, but that would
     * require a stronger query parser.
     */
    static boolean parseQueryCleanup(Qry q) {

        boolean queryChanged = false;

        // Iterate backwards to prevent problems when args are deleted.

        for (int i = q.args.size() - 1; i >= 0; i--) {

            Qry q_i = q.args.get(i);

            // All operators except TERM operators must have arguments.
            // These nodes could never match.

            if ((q_i.args.size() == 0) &&
                    (!(q_i instanceof QryIopTerm))) {
                q.removeArg(i);
                queryChanged = true;
            } else

                // All operators (except SCORE operators) must have 2 or more
                // arguments. This improves efficiency and readability a bit.
                // However, be careful to stay within the same QrySop / QryIop
                // subclass, otherwise the change might cause a syntax error.

                if ((q_i.args.size() == 1) &&
                        (!(q_i instanceof QrySopScore))) {

                    Qry q_i_0 = q_i.args.get(0);

                    if (((q_i instanceof QrySop) && (q_i_0 instanceof QrySop)) ||
                            ((q_i instanceof QryIop) && (q_i_0 instanceof QryIop))) {
                        q.args.set(i, q_i_0);
                        queryChanged = true;
                    }
                } else

                    // Check the subtree.

                    if (parseQueryCleanup(q_i))
                        queryChanged = true;
        }

        return queryChanged;
    }

    /**
     * Print a message indicating the amount of memory used. The caller
     * can indicate whether garbage collection should be performed,
     * which slows the program but reduces memory usage.
     *
     * @param gc If true, run the garbage collector before reporting.
     */
    public static void printMemoryUsage(boolean gc) {

        Runtime runtime = Runtime.getRuntime();

        if (gc)
            runtime.gc();

        writer.println("Memory used:  "
                + ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)) + " MB");
    }

    /**
     * Process one query.
     *
     * @param qString A string that contains a query.
     * @param model   The retrieval model determines how matching and scoring is done.
     * @return Search results
     * @throws IOException Error accessing the index
     */
    static ScoreList processQuery(String qid, String qString, RetrievalModel model)
            throws IOException {

        Qry q = parseQuery(qString, model);

        // Optimize the query.  Remove query operators (except SCORE
        // operators) that have only 1 argument. This improves efficiency
        // and readability a bit.

        if (q.args.size() == 1) {
            Qry q_0 = q.args.get(0);

            if (q_0 instanceof QrySop) {
                q = q_0;
            }
        }

        while ((q != null) && parseQueryCleanup(q))
            ;

        // Show the query that is evaluated

        //System.out.println("    --> " + q);

        //judge if needed to do query expansion
       if (fb == false) {
           return doQuery(q, model);
       } else {
           ScoreList r = new ScoreList();
           if (fbRankingFile.equals("")) {
               r = doQuery(q, model);
               r.sort();
           } else {
               if (!scoreListData.containsKey(qid))
                   throw new IOException("Error in ranking file!");
               r = scoreListData.get(qid);
           }
           String defaultOp = model.defaultQrySopName();
           qString = defaultOp + "(" + qString + ")";
           String expendedQuery = expendQuery(q, r);
           String newQuery = "#wand ( " + String.valueOf(fbOrigWeight) + " " + qString + " "
                   + String.valueOf(1 - fbOrigWeight) + " " + expendedQuery + " )";
           //System.out.println(newQuery);

           Qry newQ = parseQuery(newQuery, model);
           if (newQ.args.size() == 1) {
               Qry q_0 = newQ.args.get(0);

               if (q_0 instanceof QrySop) {
                   newQ = q_0;
               }
           }

           while ((newQ != null) && parseQueryCleanup(newQ))
               ;
           r = doQuery(newQ, model);
           return r;
       }
    }

    /**
     * Expand query
     *
     * @param oldQuery
     * @param scorelist of documents
     */
    static String expendQuery(Qry q, ScoreList r) throws IOException{
        Map<String, Double>data = new HashMap<>();
        Map<String, ArrayList<Integer>>record = new TreeMap<>();
        Map<String, Double>pleData = new TreeMap<>();
        Map<Integer,TermVector>termVectorMap = new TreeMap<>();
        r.sort();
        //r.truncate(fbDocs);
        for (int i = 0; i < fbDocs && i < r.size(); i ++) {
            TermVector tmpVector = new TermVector(r.getDocid(i), "body");
            termVectorMap.put(r.getDocid(i), tmpVector);
            double docScore = r.getDocidScore(i);
            double docLen = Idx.getFieldLength("body", r.getDocid(i));
            for (int j = 1; j < tmpVector.stemsLength(); j++) {
                String term = tmpVector.stemString(j);
                if (term.contains(".") || term.contains(","))
                    continue;
                if (record.containsKey(term)) {
                    ArrayList<Integer> tmp = record.get(term);
                    tmp.add(r.getDocid(i));
                    record.put(term, tmp);
                } else {
                    ArrayList<Integer> newList = new ArrayList<>();
                    newList.add(r.getDocid(i));
                    record.put(term, newList);
                }
                double ple;
                if (!pleData.containsKey(term)) {
                    ple = (double) tmpVector.totalStemFreq(j) / ((double) Idx.getSumOfFieldLengths("body"));
                    pleData.put(term, ple);
                } else {
                    ple = pleData.get(term);
                }
                double ptd = ((double)tmpVector.stemFreq(j)  + fbMu * ple) / (double)(docLen + fbMu);
                double score = ptd * docScore * (Math.log(1.0 / ple));
                if (data.containsKey(term)) {
                    double oldScore = data.get(term);
                    oldScore = oldScore + score;
                    data.put(term, oldScore);
                } else {
                    data.put(term, score);
                }
            }
        }

        for (String term : record.keySet()) {
            ArrayList<Integer> docData = record.get(term);
            for (int i = 0; i < fbDocs && i < r.size(); i ++) {
                if (docData.contains(r.getDocid(i)))
                    continue;
                TermVector tmpVector = termVectorMap.get(r.getDocid(i));
                double docScore = r.getDocidScore(i);
                double docLen = Idx.getFieldLength("body", r.getDocid(i));
                double ple = pleData.get(term);
                double ptd = (fbMu * ple) / (double)(docLen + fbMu);
                double score = ptd * docScore * (Math.log(1.0 / ple));
                if (data.containsKey(term)) {
                    double oldScore = data.get(term);
                    oldScore = oldScore + score;
                    data.put(term, oldScore);
                } else {
                    data.put(term, score);
                }
            }
        }


        PriorityQueue<Map.Entry<String, Double>> list = new PriorityQueue<Map.Entry<String, Double>>(data.size(),
            new Comparator<Map.Entry<String, Double>>() {
                @Override
                public int compare(Map.Entry<String, Double> o1,
                                   Map.Entry<String, Double> o2) {
                    return o2.getValue().compareTo(o1.getValue());
                }
            }
        );
        list.addAll(data.entrySet());

        String newQuery = "#wand ( ";
        for (int i = 0;i < fbTerms; i ++) {
            String score = String.format("%.4f", list.peek().getValue());
            String term = list.peek().getKey();
            newQuery = newQuery + " " + score + " " + term;
            list.poll();
        }
        newQuery += " )";
        globalExpandedQuery = newQuery;
        return newQuery;
    }

    /**
     * Read document ranking from fbInitialRankingFile
     *
     * @param fileName
     * @throws IOException Error accessing the Lucene index.
     */

    static void readDocumentRanking(String fileName) throws IOException {
        File documentFile = new File(fileName);

        if (!documentFile.canRead()) {
            throw new IllegalArgumentException
                    ("Can't read " + fileName);
        }
        ScoreList r = new ScoreList();

        Scanner scan = new Scanner(documentFile);
        String line = null;
        String qid = "-1";
        do {
            line = scan.nextLine();
            String[] pair = line.split(" ");
            try{
                String cur_qid = pair[0].trim();
                if (!cur_qid.equals(qid)) {
                    if (!qid.equals("-1")) {
                        scoreListData.put(qid, r);
                        qid = cur_qid;
                        r = new ScoreList();
                    } else {
                        qid = cur_qid;
                    }
                }
                int docid = Idx.getInternalDocid(pair[2].trim());
                r.add(docid, Double.parseDouble(pair[4].trim()));
            } catch (Exception e) {
                throw new IOException("Get internalDocid error");
            }
        } while (scan.hasNext());
        if (!qid.equals("-1"))
            scoreListData.put(qid, r);
        scan.close();
    }
    /**
     * Use query to do query
     *
     * @param query
     * @param model
     * @throws IOException Error accessing the Lucene index.
     */
    static ScoreList doQuery(Qry q, RetrievalModel model) throws IOException{
        if (q != null) {

            ScoreList r = new ScoreList();

            if (q.args.size() > 0) {        // Ignore empty queries

                q.initialize(model);

                while (q.docIteratorHasMatch(model)) {
                    int docid = q.docIteratorGetMatch();
                    double score = ((QrySop) q).getScore(model);
                    //writer.printf("%d,%.12f\n", docid, score);
                    r.add(docid, score);
                    q.docIteratorAdvancePast(docid);
                }
            }

            return r;
        } else
            return null;
    }

    /**
     * Process the query file.
     *
     * @param queryFilePath
     * @param model
     * @throws IOException Error accessing the Lucene index.
     */
    static void processQueryFile(String queryFilePath,
                                 RetrievalModel model)
            throws IOException {

        BufferedReader input = null;

        try {
            String qLine = null;

            input = new BufferedReader(new FileReader(queryFilePath));

            //  Each pass of the loop processes one query.

            while ((qLine = input.readLine()) != null) {
                int d = qLine.indexOf(':');

                if (d < 0) {
                    throw new IllegalArgumentException
                            ("Syntax error:  Missing ':' in query line.");
                }

                //printMemoryUsage(false);

                String qid = qLine.substring(0, d);
                String query = qLine.substring(d + 1);

                //System.out.println("Query " + qLine);

                ScoreList r = null;

                r = processQuery(qid, query, model);
                if (fb) {
                    printExpandedQuery(qid);
                }

                if (r != null) {
                    printTrecEvalResults(qid, r);
                    //printResults(qid, r);
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            input.close();
        }
    }
    /**
     * Print the expanded query
     *
     * @param qid qid of the original query
     * @throws IOException Error accessing the Lucene index.
     */
    static void printExpandedQuery(String qid) throws IOException{
        PrintWriter writer;
        writer = new PrintWriter(fbExpansionQueryFile, "UTF-8");
        writer.printf(qid + ": " + globalExpandedQuery + "\n");
        writer.close();
    }

    /**
     * Print the query results in trec_eval format
     * <p>
     * THIS OUTPUT IS IN THE FORMAT SPECIFIED IN THE HOMEWORK PAGE, WHICH IS:
     * <p>
     * QueryID Q0 DocID Rank Score RunID
     *
     * @param queryName Original query.
     * @param result    A list of document ids and scores
     * @throws IOException Error accessing the Lucene index.
     */
    static void printTrecEvalResults(String queryName, ScoreList result) throws IOException {

        result.sort();
        result.truncate(100);

        if (result.size() < 1) {
            writer.println(queryName + " Q0 dummy 1 0 ls");
        } else {
            for (int i = 0; i < result.size(); i++) {
                writer.printf(queryName + " Q0 " + Idx.getExternalDocid(result.getDocid(i)) + " "
                        + (i + 1) + " " + "%.12f" + " " + "ls\n", result.getDocidScore(i));
            }
        }
    }

    /**
     * Print the query results.
     * <p>
     * THIS IS NOT THE CORRECT OUTPUT FORMAT. YOU MUST CHANGE THIS METHOD SO
     * THAT IT OUTPUTS IN THE FORMAT SPECIFIED IN THE HOMEWORK PAGE, WHICH IS:
     * <p>
     * QueryID Q0 DocID Rank Score RunID
     *
     * @param queryName Original query.
     * @param result    A list of document ids and scores
     * @throws IOException Error accessing the Lucene index.
     */
    static void printResults(String queryName, ScoreList result) throws IOException {

        System.out.println(queryName + ":  ");
        if (result.size() < 1) {
            System.out.println("\tNo results.");
        } else {
            for (int i = 0; i < result.size(); i++) {
                System.out.println("\t" + i + ":  " + Idx.getExternalDocid(result.getDocid(i)) + ", "
                        + result.getDocidScore(i));
            }
        }
    }

    /**
     * Read the specified parameter file, and confirm that the required
     * parameters are present.  The parameters are returned in a
     * HashMap.  The caller (or its minions) are responsible for
     * processing them.
     *
     * @return The parameters, in <key, value> format.
     */
    private static Map<String, String> readParameterFile(String parameterFileName)
            throws IOException {

        Map<String, String> parameters = new HashMap<String, String>();

        File parameterFile = new File(parameterFileName);

        if (!parameterFile.canRead()) {
            throw new IllegalArgumentException
                    ("Can't read " + parameterFileName);
        }

        Scanner scan = new Scanner(parameterFile);
        String line = null;
        do {
            line = scan.nextLine();
            String[] pair = line.split("=");
            parameters.put(pair[0].trim(), pair[1].trim());
        } while (scan.hasNext());

        scan.close();
        if (parameters.containsKey("fb") && !parameters.get("fb").toLowerCase().equals("false"))
            fb = true;
        if (parameters.containsKey("fbInitialRankingFile"))
            fbRankingFile = parameters.get("fbInitialRankingFile");
        if (parameters.containsKey(("fbExpansionQueryFile")))
            fbExpansionQueryFile = parameters.get("fbExpansionQueryFile");
        if (parameters.containsKey("fbDocs"))
            fbDocs = Integer.parseInt(parameters.get("fbDocs"));
        if (parameters.containsKey("fbTerms"))
            fbTerms = Integer.parseInt(parameters.get("fbTerms"));
        if (parameters.containsKey("fbMu"))
            fbMu = Double.parseDouble(parameters.get("fbMu"));
        if (parameters.containsKey("fbOrigWeight"))
            fbOrigWeight = Double.parseDouble(parameters.get("fbOrigWeight"));

        if (!(parameters.containsKey("indexPath") &&
                parameters.containsKey("queryFilePath") &&
                parameters.containsKey("trecEvalOutputPath") &&
                parameters.containsKey("retrievalAlgorithm") ||
                (parameters.containsKey("BM25:k_1") &&
                parameters.containsKey("BM25:k_3") &&
                parameters.containsKey("BM25:b")) || (
                parameters.containsKey("Indri:mu") &&
                parameters.containsKey("Indri:lambda")))) {
            throw new IllegalArgumentException
                    ("Required parameters were missing from the parameter file.");
        }

        return parameters;
    }

    /**
     * Given a query string, returns the terms one at a time with stopwords
     * removed and the terms stemmed using the Krovetz stemmer.
     * <p>
     * Use this method to process raw query terms.
     *
     * @param query String containing query
     * @return Array of query tokens
     * @throws IOException Error accessing the Lucene index.
     */
    static String[] tokenizeQuery(String query) throws IOException {

        TokenStreamComponents comp =
                ANALYZER.createComponents("dummy", new StringReader(query));
        TokenStream tokenStream = comp.getTokenStream();

        CharTermAttribute charTermAttribute =
                tokenStream.addAttribute(CharTermAttribute.class);
        tokenStream.reset();

        List<String> tokens = new ArrayList<String>();

        while (tokenStream.incrementToken()) {
            String term = charTermAttribute.toString();
            tokens.add(term);
        }

        return tokens.toArray(new String[tokens.size()]);
    }

}
