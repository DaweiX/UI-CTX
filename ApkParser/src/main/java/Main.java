import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    static CommandLine cmd;
    static org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();

    static final String OPTION_INPUT = "i";
    static final String OPTION_OUTPUT = "o";
    static final String OPTION_INPUT_FOLDER = "if";
    static final String OPTION_OUTPUT_FOLDER = "of";
    static final String OPTION_REWRITE = "r";
    static final String OPTION_HELP = "h";
    private static void initCmdOptions(String[] args) throws ParseException {
        CommandLineParser parser = new DefaultParser();

        options.addOption(OPTION_INPUT, "input",
                true, "input apk path");
        options.addOption(OPTION_OUTPUT, "output",
                true, "output path");
        options.addOption(OPTION_INPUT_FOLDER, "input_folder",
                true, "input apk folder path");
        options.addOption(OPTION_OUTPUT_FOLDER, "output_folder",
                true, "output folder path");
        options.addOption(OPTION_REWRITE, "rewrite",
                false, "rewrite existing data");
        options.addOption(OPTION_HELP, "help", false, "Print this help info and exit");
        cmd = parser.parse(options, args);
    }

    static void handleApk(String apk, String out, boolean rewrite) throws IOException {
        if (out.endsWith(".apk")) {
            out = out.substring(0, out.length() - 4);
        }
        Path timeFile = Paths.get(out, "time_resource.txt").toAbsolutePath();
        if (new File(timeFile.toString()).exists()) {
            if (!rewrite) {
                return;
            }
        }
        long start = System.currentTimeMillis();
        APKHandler apkHandler = new APKHandler(apk);
        ResourceParser parser = new ResourceParser(apk, apkHandler, out);
        parser.getValues();
        parser.getManifest();
        parser.getARSCMap();
        parser.getLayoutFiles();
        parser.getMenuFiles();
        ContextExtractor extractor = new ContextExtractor(
                apk, out, parser.getArscStringObject(), parser.getDrawables());
        extractor.handleLayouts();
        extractor.extractImages();
        long end = System.currentTimeMillis();
        String timeSpan = String.valueOf(end - start);
        try (FileWriter writer = new FileWriter(timeFile.toString())) {
            writer.write(timeSpan);
        } catch (IOException ignored) {}
    }

    public static void main(String[] args) throws ParseException, IOException {
        initCmdOptions(args);
        if (cmd.hasOption(OPTION_HELP)) {
            HelpFormatter helpFormatter = new HelpFormatter();
            String header = "Parse and dump app resources";
            helpFormatter.printHelp("Main", header, options, "", true);
            return;
        }
        String outPostfix = "_Data";
        boolean rewrite = cmd.hasOption(OPTION_REWRITE);
        if (cmd.hasOption(OPTION_INPUT_FOLDER)) {
            String ipt = cmd.getOptionValue(OPTION_INPUT_FOLDER);
            String out = cmd.getOptionValue(OPTION_OUTPUT_FOLDER);
            if (out == null) {
                out = ipt + outPostfix;
            }
            FilenameFilter apkFilter = (dir, name) -> name.endsWith(".apk");
            String[] apks = new File(ipt).list(apkFilter);
            if (apks != null) {
                for (String apk : apks) {
                    handleApk(Paths.get(ipt, apk).toAbsolutePath().toString(),
                              Paths.get(out, apk).toAbsolutePath().toString(), rewrite);
                }
            }
        } else {
            if (!cmd.hasOption(OPTION_INPUT)) {
                System.out.println("input apk or folder is required. Use -h for help.");
                return;
            }
            String apk  =cmd.getOptionValue(OPTION_INPUT);
            String out = cmd.getOptionValue(OPTION_OUTPUT);
            if (out == null) {
                out = apk + outPostfix;
            }
            handleApk(apk, out, rewrite);
        }
    }
}
