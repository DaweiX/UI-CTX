import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.cli.*;
import scala.Tuple2;
import scala.Tuple4;
import soot.*;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.data.CategoryDefinition;
import soot.jimple.infoflow.methodSummary.data.provider.LazySummaryProvider;
import soot.jimple.infoflow.methodSummary.taintWrappers.SummaryTaintWrapper;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.options.Options;
import soot.util.Chain;
import soot.util.IterableSet;
import soot.util.queue.QueueReader;

import javax.xml.stream.XMLStreamException;
import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Formatter;
import java.util.logging.*;

@SuppressWarnings("SpellCheckingInspection")
public class Main {
    static int SOOT_DF_TIMEOUT = 3;   // dataflow
    static int SOOT_CB_TIMEOUT = 3;   // callback
    static int SOOT_PR_TIMEOUT = 0;   // path rec
    static int SOOT_CG_THREAD_NUM = 16;
    static boolean isForceWriteMode;
    public static String ANDROID_JARS;
    static String apkPath;
    static String outRootPath;
    static String currentOutPath;
    static Chain<SootClass> appClasses = null;
    static Logger log;
    static boolean sootConfigReady = false;
    static boolean isSpark = false;
    private static boolean writeMinJimpleFiles;
    static boolean option_jimple;
    // static final ExecutorService exec = Executors.newFixedThreadPool(SOOT_CG_THREAD_NUM);
    static Level level;
    static CommandLine cmd;
    static String FLOW_FILE_NAME = "data_flow.xml";
    static String INFO_FILE_NAME = "add_info.json";
    static String STR_FILE_NAME = "in_code_str.json";
    static String CONT_FILE_NAME = "ui_context.json";
    static Set<GEdge> ucEdgeSet;
    static Set<GEdge> layoutEdgeSet;
    static Set<GNode> layoutNodeSet;
    static Set<GEdge> cgEdgeSet;
    static Set<GNode> cgNodeSet;
    static List<GNode> allNodeList;
    static List<GEdge> allEdgeList;
    private static final String OPTION_INPUT = "i";
    private static final String OPTION_OUTPUT = "o";
    private static final String OPTION_LOGGING = "l";
    private static final String OPTION_PLATFORM = "p";
    private static final String OPTION_JIMPLE = "j";
    private static final String OPTION_ALL_JIMPLE = "jj";
    private static final String OPTION_NO_UI = "nu";
    private static final String OPTION_NO_LOG = "nl";
    private static final String OPTION_NO_CODE_STR = "ns";
    private static final String OPTION_CG_ALGO = "a";
    private static final String OPTION_HELP = "h";
    private static final String OPTION_FORCE_WRITE = "f";
    private static final String OPTION_TIME_CB = "tcb";
    private static final String OPTION_TIME_DF = "tdf";
    private static final String OPTION_TIME_PR = "tpr";
    private static final String OPTION_SOOT_THREAD = "t";
    private static final String OPTION_SAVE_FLOW = "sf";
    private static final String OPTION_OPCODE_PATH = "op";
    static LazySummaryProvider lazySummaryProvider;
    static Map<String, Set<String>> findEdges;
    static Map<String, Set<String>> useEdges;
    // ui_id@xml: class, method, type, value
    static Map<String, Tuple4<String, String, String, String>> hardcodeStrings;
    // we leave the switch noise reduction step after subgraph search
    // so, we must note down the switch edges as a preliminary step
    static Map<String, Set<Tuple2<String, String>>> switchEdges;
    static Map<String, Set<Tuple2<String, String>>> threadEdges;
    static List<String> apkList;
    static boolean singleApk;
    static Map<String, HashMap<String, HashSet<String>>> events;

    public static void main(String[] args) throws ParseException, IOException, URISyntaxException {
        int code = initCmdOptions(args);
        if (code != 0) {
            return;
        }

        // ensure output path
        File outFolder = new File(outRootPath);
        if (!outFolder.exists()) {
            boolean result = outFolder.mkdirs();
            if (!result) {
                throw new RuntimeException(
                   String.format("make dir fails for output folder: %s", outRootPath));
            }
        } else {
            System.out.println(outRootPath + " exists");
        }

        // init apk list
        if (new File(apkPath).isDirectory()) {
            apkList = Util.listDir(new File(apkPath), false);
            if (apkList == null) {
                throw new RuntimeException(String.format("No valid input apks found in %s!", apkPath));
            }

            List<String> toRemoved = new ArrayList<>();
            for (String f : apkList) {
                if (!f.endsWith(".apk")) toRemoved.add(f);
            }
            apkList.removeAll(toRemoved);
            singleApk = false;
        } else {
            singleApk = true;
        }

        // init output dir
        File outDir = new File(outRootPath);
        if (!outDir.exists()) {
            boolean mkOp = outDir.mkdirs();
            if (!mkOp) System.err.printf("make dir fails: %s%n", outDir);
        } else {
            System.out.println("output path (exists): " + outRootPath);
        }

        // use StubDroid (with the summaries)
        lazySummaryProvider = new LazySummaryProvider("summariesManual");

        if (!singleApk) {
            initLogger();
            int i = 0;
            for (String apk : apkList) {
                i += 1;
                runAnalysis(apk, i);
            }
        } else {
            if (cmd.hasOption(OPTION_OPCODE_PATH)) {
                System.out.println("Analyze opcode for " + apkPath);
                String opCodePath = cmd.getOptionValue(OPTION_OPCODE_PATH);
                OpCodeExtractor opCodeExtractor = new OpCodeExtractor(apkPath, opCodePath);
                opCodeExtractor.run();
                System.exit(0);
            }
            initLogger();
            runAnalysis(apkPath, -1);
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    private static void runAnalysis(String apk, int i) throws IOException {
        soot.G.reset();
        sootConfigReady = false;
        option_jimple = false;
        writeMinJimpleFiles = false;

        String path = apk;
        if (apk.endsWith(".apk")) {
            path = apk.substring(0, apk.length() - 4);
        }

        String[] pathPieces = path.split("[/\\\\]");
        String apkName = pathPieces[pathPieces.length - 1];
        if (i > 0) {
            log.info(String.format("(%d/%d) %s", i, apkList.size(), apkName));
        } else {
            log.info("(1/1) " + apkName);
        }
        if (!singleApk) {
            currentOutPath = Paths.get(outRootPath, apkName).toAbsolutePath().toString();
        } else {
            currentOutPath = outRootPath;
        }
        File outPath = new File(currentOutPath);
        if (!outPath.isDirectory()) {
            boolean ignored = outPath.mkdirs();
        }

        String edgeFile = Util.getTestFileName(currentOutPath);

        isForceWriteMode = cmd.hasOption(OPTION_FORCE_WRITE);

        if (new File(edgeFile).exists()) {
            if (!isForceWriteMode && Util.noErrorInEdgeFile(edgeFile)) {
                log.info("no work to do with generating the kg.");
                return;
            } else {
                // clear all the old files
                // we might not need to do this, but it's safer to do so
                if (!cmd.hasOption(OPTION_NO_LOG)) {
                    log.info("remove old encoding files");
                    Util.rmDir(Paths.get(currentOutPath, "encoding"));
                    Path logFilePath = Paths.get(currentOutPath, "code.log");
                    File logFile = new File(logFilePath.toString());
                    if (logFile.exists()) {
                        Files.delete(logFilePath);
                    }
                    initLogger();
                }
            }
        }
        // string map in arsc file
        Object[] arscObject;
        String arscJson = Paths.get(currentOutPath, "arsc_string.json").toAbsolutePath().toString();
        if (!new File(arscJson).exists()) {
            log.severe("no arsc_string file found for apk: "+ apk);
            return;
        }
        arscObject = Util.loadArscJson(arscJson);

        // if no layout files, skip the current app
        Path layoutPath = Paths.get(currentOutPath, "layout");
        File layoutFolder = new File(layoutPath.toAbsolutePath().toString());
        if (!layoutFolder.isDirectory()) {
            log.warning("no valid layout folder detected");
            return;
        }
        // here we do not need to get the xml list
        // only check if layout set is empty instead
        File[] layoutFiles = layoutFolder.listFiles();
        if (layoutFiles == null) {
            return;
        }
        if (layoutFiles.length == 0) {
            log.info("no layout files detected");
            Util.createErrorPlaceHolder(Util.getTestFileName(currentOutPath), "no layout file");
            return;
        }

        InfoflowConfiguration.CallgraphAlgorithm algo;
        if (cmd.hasOption(OPTION_CG_ALGO)) {
            String value = cmd.getOptionValue(OPTION_CG_ALGO).toLowerCase();
            if (value.equals("cha")) {
                algo = InfoflowConfiguration.CallgraphAlgorithm.CHA;
            }
            else if (value.equals("spark")) {
                algo = InfoflowConfiguration.CallgraphAlgorithm.SPARK;
                isSpark = true;
            } else {
                log.warning(String.format("unknown cg algo %s, use CHA", value));
                algo = InfoflowConfiguration.CallgraphAlgorithm.CHA;
            }
        } else {
            log.info("no cg algo specified, use CHA");
            algo = InfoflowConfiguration.CallgraphAlgorithm.CHA;
        }

        // print jimple
        String outDirJimple = Paths.get(currentOutPath , "jimple").toAbsolutePath().toString();
        File finishMark = new File(Paths.get(outDirJimple, "done").toUri());
        if (cmd.hasOption(OPTION_JIMPLE) || cmd.hasOption(OPTION_ALL_JIMPLE)) {
            option_jimple = true;
            if (finishMark.exists() && !isForceWriteMode) {
                log.info("jimple exists, skip");
            } else {
                // output jimple files
                if (cmd.hasOption(OPTION_ALL_JIMPLE)) {
                    try {
                        configSoot(apk, isSpark);
                    } catch (RuntimeException e) {
                        log.severe("error when config soot for all jimple: " + e.getMessage());
                        return;
                    }
                    sootConfigReady = false;

                    try {
                        PackManager.v().getPack("jb");
                        PackManager.v().runPacks();
                        PackManager.v().writeOutput();
                        if (finishMark.mkdir())
                            log.info("jimple code printed");
                    } catch (RuntimeException e) {
                        log.warning("error when getting jimple files");
                    }
                } else {
                    writeMinJimpleFiles = true;
                }
            }
        }

        try {
            // build CG
            soot.G.reset();
            sootConfigReady = false;
            try {
                configSoot(apk, isSpark);
            } catch (RuntimeException e) {
                Util.createErrorPlaceHolder(Util.getTestFileName(currentOutPath),
                        "error: " + Arrays.toString(e.getStackTrace()));
                log.severe("error when config soot: " + e.getMessage());
                return;
            }
            SetupApplication app = configApp(apk, algo);

            printInfo();
            CallGraph cg;

            try {
                // Scene.v().addBasicClass("android.app.IntentService", SootClass.BODIES);
                app.constructCallgraph();
                // app.runInfoflow("lib/SourcesAndSinks-ui.txt");
                cg =  Scene.v().getCallGraph();
                appClasses = Scene.v().getApplicationClasses();

                // first, get ui events. if no events, then we can skip the following steps
                String contextFile = Paths.get(currentOutPath, CONT_FILE_NAME).toAbsolutePath().toString();
                Map<String, Set<String>> viewListFromLayouts = Util.getViewListFromLayouts(contextFile);
                long start = System.currentTimeMillis();
                EventAnalyzer eventAnalyzer = new EventAnalyzer(apk, log, viewListFromLayouts);
                eventAnalyzer.run();
                events = eventAnalyzer.getResults();
                long end = System.currentTimeMillis();
                String timeSpan = String.valueOf(end - start);
                log.info(String.format("event parse time: %s", timeSpan));
                Util.saveEventFile(events, currentOutPath);
                // if no event, no need to process
                if (events.isEmpty()) {
                    return;
                }

            } catch (Exception e) {
                Util.createErrorPlaceHolder(Util.getTestFileName(currentOutPath),
                        "error: " + Arrays.toString(e.getStackTrace()));
                log.severe("error when get cg: " + Arrays.toString(e.getStackTrace()));
                return;
            }

            QueueReader<Edge> edges = cg.listener();

            cgNodeSet = new IterableSet<>();
            cgEdgeSet = new IterableSet<>();

            // add nodes and edges in the call graph
            Dictionary<String, Integer> methodName2Uuid = new Hashtable<>();
            Set<String> visitedSootNodes = new IterableSet<>();

            while (edges.hasNext()) {
                Edge next = edges.next();
                MethodOrMethodContext src = next.getSrc();
                MethodOrMethodContext tgt = next.getTgt();
                String srcString = src.toString();
                String tgtString = tgt.toString();

                // all method has only one hit in soot, so for methods with the
                // same name (e.g., native apis), we only assign one node for it.
                // besides, (1). methods in 3rd libs (2). apps with the same package
                // names will have only one hash, even if they're in different apps
                if (!visitedSootNodes.contains(srcString)) {
                    GNode nodeSrc = new GNode(srcString,
                            GNode.ApkNodeLabels.METHOD.toString());
                    cgNodeSet.add(nodeSrc);
                    visitedSootNodes.add(srcString);
                    methodName2Uuid.put(srcString, nodeSrc.getUuid());
                }
                if (!visitedSootNodes.contains(tgtString)) {
                    GNode nodeTgt = new GNode(tgtString,
                            GNode.ApkNodeLabels.METHOD.toString());
                    cgNodeSet.add(nodeTgt);
                    visitedSootNodes.add(tgtString);
                    methodName2Uuid.put(tgtString, nodeTgt.getUuid());
                }

                cgEdgeSet.add(new GEdge(
                        new GNode(methodName2Uuid.get(srcString), srcString),
                        new GNode(methodName2Uuid.get(tgtString), tgtString),
                        GEdge.ApkRelationships.CALL.toString()));
            }

            String encodingPath = Paths.get(currentOutPath, Util.sEncoding).toAbsolutePath().toString();
            File encodingDir = new File(encodingPath);
            if (!encodingDir.exists()) {
                boolean mkOp = encodingDir.mkdirs();
                log.info(String.format("mkdir (%s): %s", mkOp, encodingPath));
            }

            log.info(String.format("Call graph size (soot): %d = %d (node) + %d (edge)",
                    cgNodeSet.size() + cgEdgeSet.size(), cgNodeSet.size(), cgEdgeSet.size()));

            // Call graph
            for (GNode n : cgNodeSet) {
                GNode.updateNodeType(n);
                GNode.addNodeClass(n);
            }

            allNodeList = new ArrayList<>();
            allNodeList.addAll(cgNodeSet);
            allEdgeList = new ArrayList<>();
            allEdgeList.addAll(cgEdgeSet);

            // move jimple files
            if (writeMinJimpleFiles) {
                File jimpleFolder = new File(outDirJimple);
                if (jimpleFolder.isDirectory()) {
                    log.info("Remove folder: "+ outDirJimple);
                    Util.rmDir(Paths.get(outDirJimple));
                }

                boolean mkdir = jimpleFolder.mkdir();
                log.info(String.format("writing jimple in: %s", outDirJimple));
                if (!mkdir) {
                    log.warning(String.format("mkdir %s fails", jimpleFolder.getName()));
                }

                File srcFolder = new File("sootOutput");
                File[] jimpleFiles = srcFolder.listFiles();
                if (jimpleFiles != null) {
                    for (File jimpleFile : jimpleFiles) {
                        File dst = new File(String.valueOf(Paths.get(outDirJimple, jimpleFile.getName())));
                        // the dst file not exists, so no need to delete it
                        boolean rename = jimpleFile.renameTo(dst);
                        if (!rename) {
                            log.warning(String.format(String.format("rename %s to %s fails",
                                    jimpleFile.getName(), dst.getName())));
                        }
                    }
                    // add the finish mark
                    if (finishMark.mkdir())
                        log.info("jimple code printed");
                } else {
                    log.warning("sootOutput folder not accessed");
                }
            }

            // UI graph
            AppendLayout layoutHandler;
            if (!cmd.hasOption(OPTION_NO_UI)) {
                layoutHandler = new AppendLayout(currentOutPath, arscObject);
                layoutHandler.run();
                // ArrayList<String> viewIds = layoutHandler.getViewIds();

                log.info(String.format("layout graph size: %d = %d (node) + %d (edge)",
                        layoutHandler.getNodeNum() + layoutHandler.getEdgeNum(),
                        layoutHandler.getNodeNum(), layoutHandler.getEdgeNum()));

                layoutEdgeSet = layoutHandler.getLayoutEdgeSet();
                layoutNodeSet = layoutHandler.getLayoutNodeSet();
                allNodeList.addAll(layoutNodeSet);
                allEdgeList.addAll(layoutEdgeSet);

                Map<String, Integer> id2uuid = new HashMap<>();
                for (GNode n: layoutNodeSet) {
                    String id = n.getAttribute("id");
                    if (id != null) {
                        id = id.replace("android:", "");
                        id = id.toUpperCase();
                        id2uuid.put(id, n.getUuid());
                    }
                }

                // Add UI-code links (find, use)
                CodeParser parser = new CodeParser(log, appClasses, arscJson);
                parser.run();
                log.info(String.format("Class: %s", appClasses.size()));
                String infoFile = Paths.get(currentOutPath, INFO_FILE_NAME).toAbsolutePath().toString();
                findEdges = parser.getFindEdges();
                useEdges = parser.getUseEdges();
                long start = System.currentTimeMillis();
                // dive into methods, handle branches and threads
                parser.parseMethods(events);
                switchEdges = parser.getSwitchEdges();
                threadEdges = parser.getThreadEdges();
                long end = System.currentTimeMillis();
                String timeSpan = String.valueOf(end - start);
                log.info(String.format("branch parse time: %s", timeSpan));
                hardcodeStrings = parser.getHardcodeStrings();
                saveInfo(infoFile);
                String contextFile = Paths.get(currentOutPath, CONT_FILE_NAME).toAbsolutePath().toString();
                Util.mergeCodeString(contextFile, hardcodeStrings);
                if (!cmd.hasOption(OPTION_NO_CODE_STR)) {
                    String codeStrFile = Paths.get(currentOutPath, STR_FILE_NAME).toAbsolutePath().toString();
                    Util.saveInCodeString(codeStrFile, hardcodeStrings);
                }

                ucEdgeSet = new IterableSet<>();
                try {
                    for (Map.Entry<String, Set<String>> entry : findEdges.entrySet()) {
                        String id = entry.getKey();
                        Set<String> methods = entry.getValue();
                        for (String method: methods) {
                            log.finer(String.format("%s --> %s", id, method));
                            try {
                                int uuid1 = id2uuid.get("@" + id.toUpperCase());
                                int uuid2 = methodName2Uuid.get(method);
                                ucEdgeSet.add(new GEdge(new GNode(uuid1), new GNode(uuid2),
                                        GEdge.ApkRelationships.FIND.toString()));
                            } catch (NullPointerException ignored) {
                                log.finest(String.format("cannot find uuid for ui (id=%s)", id));
                            }
                        }
                    }
                    allEdgeList.addAll(ucEdgeSet);
                    // log.info(String.format("links between the two graphs: %d / %d", ucEdgeSet.size(), linkNum));
                } catch (StackOverflowError ignored) {
                    log.warning("stack over flow when find links");
                    return;
                }
            } else {
                log.warning("UI is excluded in the graph!");
            }
            Map<Integer, Integer> nodeHashToId = Util.saveNodes(currentOutPath, allNodeList);
            Util.saveEdges(currentOutPath, nodeHashToId, allEdgeList);
            log.info("graph is ready");
        } catch (RuntimeException e) {
            log.severe("runtime error: " + e.getMessage());
            Util.createErrorPlaceHolder(Util.getTestFileName(currentOutPath),
                    "runtime error: " + Arrays.toString(e.getStackTrace()));
        } catch (IOException e) {
            log.severe("io error: " + e.getMessage());
            Util.createErrorPlaceHolder(Util.getTestFileName(currentOutPath),
                    "io error: " + Arrays.toString(e.getStackTrace()));
        }
        catch (URISyntaxException | XMLStreamException e) {
            log.severe("xml error: " + e.getMessage());
        }
    }

    private static int initCmdOptions(String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();

        org.apache.commons.cli.Options options = getOptions();

        cmd = parser.parse(options, args);
        if (cmd.hasOption(OPTION_HELP)) {
            HelpFormatter helpFormatter = new HelpFormatter();
            String header = "Run customized code analysis";
            helpFormatter.printHelp("Main", header, options, "", true);
            return 1;
        }
        if (cmd.hasOption(OPTION_TIME_DF)) {
            SOOT_DF_TIMEOUT = Integer.parseInt(cmd.getOptionValue(OPTION_TIME_DF));
        }
        if (cmd.hasOption(OPTION_TIME_CB)) {
            SOOT_CB_TIMEOUT = Integer.parseInt(cmd.getOptionValue(OPTION_TIME_CB));
        }
        if (cmd.hasOption(OPTION_TIME_PR)) {
            SOOT_PR_TIMEOUT = Integer.parseInt(cmd.getOptionValue(OPTION_TIME_PR));
        }
        if (cmd.hasOption(OPTION_SOOT_THREAD)) {
            SOOT_CG_THREAD_NUM = Integer.parseInt(cmd.getOptionValue(OPTION_SOOT_THREAD));
        }
        if (cmd.hasOption(OPTION_PLATFORM)) {
            ANDROID_JARS = cmd.getOptionValue(OPTION_PLATFORM);
        }
        if (cmd.hasOption(OPTION_INPUT)) {
            apkPath = cmd.getOptionValue(OPTION_INPUT);
        } else {
            System.out.println("Input path or apk file is required. Use -h for help.");
            return 1;
        }
        if (cmd.hasOption(OPTION_OUTPUT)) {
            outRootPath = cmd.getOptionValue(OPTION_OUTPUT);
        } else {
            outRootPath = apkPath + "_Data";
        }

        level = Level.INFO;
        if (cmd.hasOption(OPTION_LOGGING)) {
            try {
                level = Level.parse(cmd.getOptionValue(OPTION_LOGGING).toUpperCase());
            } catch (IllegalArgumentException e) {
                log.severe("illegal argument" + e.getMessage());
            }
        }
        return 0;
    }

    private static org.apache.commons.cli.Options getOptions() {
        org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();

        options.addOption(OPTION_INPUT, "input", true, "Input root path for apks");
        options.addOption(OPTION_OUTPUT, "output", true, "Output root path");
        options.addOption(OPTION_PLATFORM, "platforms", true, "Android platform jars");
        options.addOption(OPTION_JIMPLE, "jimple", false, "Print jimple codes for app codes");
        options.addOption(OPTION_ALL_JIMPLE, "all_jimple", false, "Print all jimple codes");
        options.addOption(OPTION_OPCODE_PATH, "op_path", true, "Path to save all op codes");
        options.addOption(OPTION_CG_ALGO, "cg_algo", true,
                "Algo used for building cg (cha | spark)");
        options.addOption(OPTION_NO_UI, "no_ui", false, "Exclude ui graph");
        options.addOption(OPTION_NO_LOG, "no_log", false, "Do not write log to file");
        options.addOption(OPTION_LOGGING, "log_level", true, "Logging level, default: info");
        options.addOption(OPTION_NO_CODE_STR, "no_code_str", false,
                "not save in-code str json");
        options.addOption(OPTION_FORCE_WRITE, "force_write", false,
                "Force write results even when they exist");
        options.addOption(OPTION_SAVE_FLOW, "save control flow",
                false, "Save control flow information");
        options.addOption(OPTION_HELP, "help", false, "Print this help info and exit");
        options.addOption(OPTION_TIME_CB, "timeout_callback", true,
                "Time out for soot calculate callback (unit: min, 0 to default)");
        options.addOption(OPTION_TIME_PR, "timeout_path", true,
                "Time out for soot reconstruct path (unit: min, 0 to default)");
        options.addOption(OPTION_TIME_DF, "timeout_dataflow", true,
                "Time out for soot track dataflow (unit: min, 0 to default)");
        options.addOption(OPTION_SOOT_THREAD, "soot_thread", true,
                "Threads used for soot");
        return options;
    }

    public static void initLogger() throws IOException {
        // init logger
        String logFile, pattern;
        if (!singleApk) {
            logFile = String.format("run_%s.log", System.currentTimeMillis());
        } else {
            logFile = "code.log";
        }
        pattern = Paths.get(outRootPath, logFile).toAbsolutePath().toString();

        log = Logger.getLogger("CodeLog");
        log.setLevel(level);
        if (!pattern.endsWith("code.log") ||
                (pattern.endsWith("code.log") && !new File(pattern).exists()) ||
                (pattern.endsWith("code.log") && isForceWriteMode))
        {
            FileHandler fileHandler = new FileHandler(pattern);
            fileHandler.setLevel(level);
            fileHandler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    String time = new Date().toString();
                    return "[" + time + "]" + "\t" + "[" +  record.getLevel() + "]" +
                            "\t\t" + record.getMessage() + "\n";
                }
            });
            if (!cmd.hasOption(OPTION_NO_LOG)) {
                log.addHandler(fileHandler);
            }
        }
        Util.setLogger(log);
        log.info(String.format("log level: %s", log.getLevel()));
    }

    private static void saveInfo(String outputFile) {
        AdditionalInfo obj = new AdditionalInfo(findEdges, useEdges, switchEdges, threadEdges);
        String jsonData = JSON.toJSONString(obj, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(outputFile, false)) {
            writer.write(jsonData);
        } catch (IOException ignored) {}
    }

    public static void printInfo() {
        log.info(String.format("API ver: %d (min:%d, tgt:%d)",
                Scene.v().getAndroidAPIVersion(),
                Scene.v().getAndroidSDKVersionInfo().minSdkVersion,
                Scene.v().getAndroidSDKVersionInfo().sdkTargetVersion
                ));
    }

    @SuppressWarnings("SpellCheckingInspection")
    public static void configSoot(String apk, boolean isSpark) {
        if (sootConfigReady) {
            log.info("Soot config ready, skip");
            return;
        }
        soot.G.reset();
        Options.v().set_src_prec(Options.src_prec_apk);
        Options.v().set_process_dir(Collections.singletonList(apk));
        Options.v().set_android_jars(ANDROID_JARS);

        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_ignore_resolution_errors(true);
        Options.v().set_ignore_resolving_levels(true);
        Options.v().set_ignore_classpath_errors(true);

        Options.v().set_prepend_classpath(true);
        Options.v().set_app(true);

        Options.v().set_process_multiple_dex(true);
        Options.v().set_no_bodies_for_excluded(false);

        // spark options
        if (isSpark) {
            String phase = "cg.spark";
            Options.v().setPhaseOption(phase, "on");
            Options.v().setPhaseOption(phase, "enabled:true");
            Options.v().setPhaseOption(phase, "simulate-natives:true");
            Options.v().setPhaseOption(phase, "on-fly-cg:true");
            Options.v().setPhaseOption(phase, "propagator:worklist");
        }

        Options.v().setPhaseOption("cg", "all-reachable:true");
        Options.v().setPhaseOption("cg", "safe-newinstance:false");

        if (option_jimple) {
            /*
             Seems not work:
             String outDirJimple = Paths.get(currentOutPath, "jimple").toAbsolutePath().toString();
             Options.v().set_output_dir(outDirJimple);
            */
            Options.v().set_no_writeout_body_releasing(true);
            Options.v().set_output_format(Options.output_format_jimple);
        }
        if (cmd.hasOption(OPTION_OPCODE_PATH)) {
            Options.v().set_output_format(Options.output_format_force_dex);
            Options.v().set_no_writeout_body_releasing(true);
        }

        Scene.v().loadNecessaryClasses();
        Scene.v().loadBasicClasses();
        sootConfigReady = true;
        log.info("config soot ready");
    }

    public static SetupApplication configApp(String apk, InfoflowConfiguration.CallgraphAlgorithm algo) 
            throws URISyntaxException, XMLStreamException {
        InfoflowAndroidConfiguration config = new InfoflowAndroidConfiguration();
        config.getAnalysisFileConfig().setAndroidPlatformDir(ANDROID_JARS);
        config.getAnalysisFileConfig().setTargetAPKFile(apk);
        config.setMaxThreadNum(SOOT_CG_THREAD_NUM);
        config.setSootIntegrationMode(InfoflowConfiguration.SootIntegrationMode.UseExistingInstance);
        config.getPathConfiguration().setPathReconstructionMode(InfoflowConfiguration.PathReconstructionMode.Precise);
        // identify all the paths between a source and a sink
        // InfoflowConfiguration.setPathAgnosticResults(false);

        // start data flow from all findViewById
        config.getSourceSinkConfig().setLayoutMatchingMode(InfoflowConfiguration.LayoutMatchingMode.MatchAll);
        config.getSourceSinkConfig().addSinkCategory(
                new CategoryDefinition(CategoryDefinition.CATEGORY.ALL),
                InfoflowConfiguration.CategoryMode.Include);
       
        config.setCallgraphAlgorithm(algo);
        log.info(String.format("Using CG Algo %s", config.getCallgraphAlgorithm()));
        config.setMergeDexFiles(true);

        // improve accuracy
        config.setStaticFieldTrackingMode(InfoflowConfiguration.StaticFieldTrackingMode.ContextFlowSensitive);
        config.setEnableReflection(true);
        config.getAccessPathConfiguration().setAccessPathLength(500);
        config.setAliasingAlgorithm(InfoflowConfiguration.AliasingAlgorithm.FlowSensitive);

        // callback settings
        config.getCallbackConfig().setEnableCallbacks(true);
        config.getCallbackConfig().setMaxCallbacksPerComponent(500);
        config.getCallbackConfig().setMaxAnalysisCallbackDepth(500);
        config.getCallbackConfig().setFilterThreadCallbacks(false);
        // config.getCallbackConfig().setCallbacksFile("lib/AndroidCallbacks.txt");

        // call-graph only, and select all libs
        config.setTaintAnalysisEnabled(false);
        config.setExcludeSootLibraryClasses(false);
        // config.setIgnoreFlowsInSystemPackages(false);     // Runtime error (BODY)

        // timeouts
        if (SOOT_DF_TIMEOUT > 0)
            config.setDataFlowTimeout(SOOT_DF_TIMEOUT * 60L);
        if (SOOT_CB_TIMEOUT > 0)
            config.getCallbackConfig().setCallbackAnalysisTimeout(SOOT_CB_TIMEOUT * 60);
        if (SOOT_PR_TIMEOUT > 0)
            config.getPathConfiguration().setPathReconstructionTimeout(SOOT_PR_TIMEOUT * 60L);

        SetupApplication analyzer = new SetupApplication(config);
        analyzer.getConfig().setFlowSensitiveAliasing(true);
        analyzer.getConfig().setImplicitFlowMode(InfoflowConfiguration.ImplicitFlowMode.AllImplicitFlows);
        analyzer.getConfig().setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);

        if (cmd.hasOption(OPTION_SAVE_FLOW)) {
            String flowFile = Paths.get(currentOutPath, FLOW_FILE_NAME).toAbsolutePath().toString();
            analyzer.getConfig().getAnalysisFileConfig().setOutputFile(flowFile);
        }

        // init taint wrapper
         SummaryTaintWrapper taintPropagationWrapper = new SummaryTaintWrapper(lazySummaryProvider);
         analyzer.setTaintWrapper(taintPropagationWrapper);

        // disable exception tracking can make the analysis faster
        analyzer.getConfig().setEnableExceptionTracking(false);
        analyzer.getConfig().setWriteOutputFiles(writeMinJimpleFiles);
        analyzer.setCallbackFile("lib/AndroidCallbacks.txt");

        // inject StubDroid hierarchy
        // injectStubDroidHierarchy(taintPropagationWrapper, analyzer);

        return analyzer;
    }

    @Deprecated
    public static void addAdditionalInfoFlowConfig(InfoflowAndroidConfiguration config) {
        config.getPathConfiguration().setPathBuildingAlgorithm(InfoflowConfiguration.PathBuildingAlgorithm.ContextSensitive);
        config.setCodeEliminationMode(InfoflowConfiguration.CodeEliminationMode.NoCodeElimination);
        config.setMergeDexFiles(true);
        config.setIgnoreFlowsInSystemPackages(true);
        config.setEnableLineNumbers(true);
    }

    @SuppressWarnings("SpellCheckingInspection")
    @Deprecated
    public void addAdditionalSootConfig() {
         PackManager.v().getPack("cg");
         PackManager.v().getPack("jb");
         PackManager.v().getPack("wjap.cgg");

         Options.v().set_keep_offset(false);
         Options.v().set_no_bodies_for_excluded(true);
         Options.v().set_verbose(false);

         Options.v().setPhaseOption("cg.spark", "on");
         Options.v().setPhaseOption("all-reachable:true", "on");
         Options.v().set_keep_line_number(true);
         Options.v().set_ignore_resolving_levels(true);
    }
}