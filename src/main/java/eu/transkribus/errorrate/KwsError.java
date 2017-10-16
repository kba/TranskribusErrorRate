/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.transkribus.errorrate;

//github.com/Transkribus/TranskribusErrorRate.git
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.panayotis.gnuplot.JavaPlot;
import com.twelvemonkeys.util.LinkedSet;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;

import eu.transkribus.errorrate.aligner.BaseLineAligner;
import eu.transkribus.errorrate.htr.ErrorModuleDynProg;
import eu.transkribus.errorrate.kws.KWSEvaluationMeasure;
import eu.transkribus.errorrate.kws.KeywordExtractor;
import eu.transkribus.errorrate.kws.measures.IRankingMeasure;
import eu.transkribus.errorrate.kws.measures.IRankingStatistic;
import eu.transkribus.errorrate.normalizer.StringNormalizerLetterNumber;
import eu.transkribus.errorrate.types.KWS;
import eu.transkribus.errorrate.types.KWS.GroundTruth;
import eu.transkribus.errorrate.types.KWS.Result;
import eu.transkribus.errorrate.types.KWS.Word;
import eu.transkribus.errorrate.util.PlotUtil;
import eu.transkribus.interfaces.IStringNormalizer;
import eu.transkribus.interfaces.ITokenizer;
import eu.transkribus.tokenizer.TokenizerCategorizer;
import eu.transkribus.tokenizer.categorizer.CategorizerWordMergeGroups;

import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import org.apache.commons.math3.util.Pair;

/**
 * Parser to make {@link ErrorModuleDynProg} accessible for the console.
 *
 * @author gundram
 */
public class KwsError {

    private static final Logger LOG = Logger.getLogger(KwsError.class.getName());
    private final Options options = new Options();

    public KwsError() {
        options.addOption("h", "help", false, "show this help");
        options.addOption("p", "pages", true, "path to a file which contains the pathes to the PAGE-Xml-files (as <groundtruth> a keyword-list can be set. if <groundtruth> is not set, all possible keywords of the validation are used)");
        options.addOption("s", "substring", false, "if 'p' is set: a keyword can be a substring af a word.");
        options.addOption("m", "metrics", true, ",-seperated list of methods " + Arrays.toString(IRankingMeasure.Measure.values()));
        options.addOption("d", "display", false, "display PR-Curve");
        options.addOption("i", "index", false, "result file contains index of result file list, not the path to the image");
        options.addOption("k", "keywords", true, "if no kw list is given, generated kw list is written to given path");
    }

    private static Result getHyp(File path) {
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        try {
            return gson.fromJson(new FileReader(path), Result.class);
        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static GroundTruth getGT(File path) {
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        try {
            return gson.fromJson(new FileReader(path), GroundTruth.class);
        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    private Result filter(Result result, List<String> kw) {
        Set<Word> words = new LinkedHashSet<>();
        for (Word keyword : result.getKeywords()) {
            if (kw.contains(keyword.getKeyWord())) {
                words.add(keyword);
            }
        }
        return new Result(words);
    }

    private Pair<String[], String[]> getListsPageAndIndex(CommandLine cmd) {
        File listFile = new File(cmd.getOptionValue('p'));
        if (!listFile.exists()) {
            help("file " + listFile.getPath() + " containing the xml-pathes does not exist.");
        }
        String[] pagesFile = null;
        String[] pagesIndex = null;
        try {
            pagesFile = FileUtils.readLines(listFile).toArray(new String[0]);
        } catch (IOException ex) {
            help("file " + listFile.getPath() + " containing the xml-pathes cannot be laoded.", ex);
        }
        if (cmd.hasOption('i')) {
            pagesIndex = new String[pagesFile.length];
            for (int i = 0; i < pagesFile.length; i++) {
                pagesIndex[i] = "" + i;
            }
        } else {
            pagesIndex = pagesFile;
        }
        return new Pair<>(pagesFile, pagesIndex);
    }

    private List<String> getKeywords(KWS.GroundTruth gt) {
        Set<String> resSet = new LinkedHashSet<>();
        for (KWS.Page page : gt.getPages()) {
            for (KWS.Line line : page.getLines()) {
                for (KWS.Line line1 : page.getLines()) {
                    resSet.addAll(line.getKeyword2Baseline().keySet());
                }
            }
        }
        ArrayList<String> res = new ArrayList<>(resSet);
        Collections.sort(res);
        return res;
    }

    public Map<IRankingMeasure.Measure, Double> run(String[] args) {

        CommandLine cmd = null;
        try {
            cmd = new DefaultParser().parse(options, args);

            //Help?
            if (cmd.hasOption("h")) {
                help();
            }
            String[] args1 = cmd.getArgs();
            if (args1.length < 1 || args1.length > 2) {
                help("number of arguments have to be 1 or 2, but is " + args1.length + ".");
            }
            File hypoFile = new File(args1[0]);
            if (!hypoFile.exists()) {
                help("kws result file " + hypoFile.getPath() + " does not exists.");
            }
            Result hyp = null;
            try {
                hyp = getHyp(hypoFile);
            } catch (RuntimeException ex) {
                help("cannot load kws result file '" + hypoFile.getPath() + "'.", ex);
            }
            GroundTruth gt = null;
            if (args1.length == 1) {
                if (!cmd.hasOption('p')) {
                    help("no groundtruth file and no file containing the xml-pathes (-p <file>) is given");
                }
                if (cmd.hasOption('s')) {
                    help("if no keyword list is provided keywords can only be extracted automatically as non-substrings");
                }
                Pair<String[], String[]> listsPageAndIndex = getListsPageAndIndex(cmd);
                final ITokenizer tokIntern = new TokenizerCategorizer(new CategorizerWordMergeGroups());
                final IStringNormalizer sn = new StringNormalizerLetterNumber(null);
                KeywordExtractor kwe = new KeywordExtractor(true);
                ITokenizer tok = new ITokenizer() {
                    @Override
                    public List<String> tokenize(String string) {
                        return tokIntern.tokenize(sn.normalize(string));
                    }
                };
                gt = kwe.getKeywordGroundTruth(
                        listsPageAndIndex.getFirst(),
                        listsPageAndIndex.getSecond(),
                        tok);
                if (cmd.hasOption('k')) {
                    try {
                        FileUtils.writeLines(new File(cmd.getOptionValue('k')), getKeywords(gt));
                    } catch (IOException ex) {
                        help("cannot save keyword list to file " + cmd.getOptionValue('k') + ".", ex);
                    }
                }
            } else {
                File gtFile = new File(args1[1]);
                if (!hypoFile.exists()) {
                    help("kws groundtruth file " + gtFile.getPath() + " does not exists.");
                }
                if (cmd.hasOption('p')) {
                    Pair<String[], String[]> listsPageAndIndex = getListsPageAndIndex(cmd);
                    List<String> readLines = null;
                    try {
                        readLines = FileUtils.readLines(gtFile);
                    } catch (IOException ex) {
                        help("cannot load groundtruth file " + gtFile.getPath() + ".", ex);
                    }

                    KeywordExtractor kwe = new KeywordExtractor(!cmd.hasOption('s'));
                    gt = kwe.getKeywordGroundTruth(
                            listsPageAndIndex.getFirst(),
                            listsPageAndIndex.getSecond(),
                            readLines);
                } else {
                    gt = getGT(gtFile);
                }
            }
            List<IRankingMeasure.Measure> m = new LinkedList<>();
            if (cmd.hasOption('m')) {
                String[] split = cmd.getOptionValue('m').split(",");
                for (String string : split) {
                    m.add(IRankingMeasure.Measure.valueOf(string.trim()));
                }
            } else {
                m.addAll(Arrays.asList(IRankingMeasure.Measure.values()));
            }
            KWSEvaluationMeasure evaluationMeasure = new KWSEvaluationMeasure(new BaseLineAligner());
            evaluationMeasure.setGroundtruth(gt);
            evaluationMeasure.setResults(hyp);
            if (cmd.hasOption('d')) {
                Map<IRankingStatistic.Statistic, double[]> stats = evaluationMeasure.getStats(Arrays.asList(IRankingStatistic.Statistic.PR_CURVE));
                JavaPlot prCurve = PlotUtil.getPRCurve(stats.values().iterator().next());
                PlotUtil.getDefaultTerminal().accept(prCurve);
//                PlotUtil.getImageFileTerminal(new File("")).accept(prCurve);
            }
            return evaluationMeasure.getMeasure(m);
        } catch (ParseException e) {
            help("Failed to parse comand line properties", e);
            return null;
        }
    }

    private void help() {
        help(null, null);
    }

    private void help(String suffix) {
        help(suffix, null);
    }

    private void help(String suffix, Throwable e) {
        // This prints out some help
        if (suffix != null && !suffix.isEmpty()) {
            suffix = "ERROR:\n" + suffix;
            if (e != null) {
                suffix += "\n" + e.getMessage();
            }
        }
        HelpFormatter formater = new HelpFormatter();
        formater.printHelp(
                "java -cp <this-jar>.jar " + KwsError.class.getName() + " <path_result_file> [<path_groundtruth_file>]",
                "This method calculates different measures for KWS results. "
                + "Either the file <path_groundtruth_file> is a json-file with the desired structure, "
                + "or it is a list of keywords. For latter one have to set parameter -p, "
                + "so that the process can generate the groundtruth from these PAGE-Xml files and the keyword list. ",
                options,
                suffix,
                true
        );
        System.exit(0);
    }

    public static void main(String[] args) {
//        args = ("--help").split(" ");
        KwsError erp = new KwsError();
        Map<IRankingMeasure.Measure, Double> res = erp.run(args);
        for (IRankingMeasure.Measure measure : res.keySet()) {
            System.out.println(measure + " = " + res.get(measure));
        }

    }
}
