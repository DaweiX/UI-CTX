import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import scala.Tuple4;
import soot.SootClass;
import soot.jimple.JasminClass;

public class Util {
    static final String sEncoding = "encoding";
    static final String sEdge = "edge";
    static final String sNode = "node";
    static final String[] COLUMN_HEADER_NODE = {
            "Name", "Hash",
            "Java", "Android", "UI",            // type
            "Class",                            // for code
            "XML", "UId"                        // for ui
    };

    static final String[] COLUMN_HEADER_EDGE = {
            "From", "To", "Type"
    };

    static Logger log;

    public static void setLogger(Logger logger) {
        log = logger;
    }

    public static List<String> listDir(File dir, boolean listSub) {
        ArrayList<String> results = new ArrayList<>();
        if (!dir.exists() || !dir.isDirectory()) return null;
        String[] fs = dir.list();
        if (fs == null) return null;
        if (!listSub) {
            for (String f : fs) {
                results.add(dir + File.separator + f);
            }
        }
        else for (String f : fs) {
            File file = new File(dir, f);
            if (file.isFile()) results.add(dir + File.separator + file.getName());
            else {
                List<String> filesInSubDir = listDir(file, true);
                results.addAll(filesInSubDir);
            }
        }
        return results;
    }

    public static Map<Integer, Integer> saveNodes(String outputPath,
                                                 List<GNode> nodeList) throws IOException {
        String fileName = outputPath + File.separator + sEncoding + File.separator + sNode + ".csv";
        File nodeFile = new File(fileName);
        if (!nodeFile.exists()) {
            boolean fileCreated = nodeFile.createNewFile();
            if (fileCreated) {
                log.fine(sNode + " file created");
            }
        }
        Map<Integer, Integer> nodeHashToId = new HashMap<>();
        try(FileWriter writer = new FileWriter(fileName, false)) {
            writer.write(",");
            writer.write(join(COLUMN_HEADER_NODE, ",") + "\n");
            int i = -1;
            for (GNode node : nodeList) {
                i += 1;
                nodeHashToId.put(node.getUuid(), i);
                StringBuilder builder = new StringBuilder();
                // node index
                builder.append(i);
                builder.append(",");
                // node name
                builder.append("\"");
                builder.append(node.name);
                builder.append("\"");
                builder.append(",");
                // node hash
                builder.append(node.getUuid());
                builder.append(",");
                int typeId = node.getTypeID();
                // node type: java
                builder.append(typeId == GNode.ApkNodeLabels.JAVA.ordinal() ? 1 : 0);
                builder.append(",");
                // node type: android
                builder.append(typeId == GNode.ApkNodeLabels.ANDROID.ordinal() ? 1 : 0);
                builder.append(",");
                // node type: ui
                builder.append(typeId >= GNode.ApkNodeLabels.CONTROL.ordinal() ? 1 : 0);
                builder.append(",");
                // class
                String clazz = node.getAttribute("class");
                if (clazz != null) {
                    builder.append(clazz);
                } else {
                    builder.append(-1);
                }
                builder.append(",");
                // xml
                String xml = node.getAttribute("xml");
                if (xml != null) {
                    builder.append(xml);
                } else {
                    builder.append(-1);
                }
                builder.append(",");
                // uid
                String uid = node.getAttribute("id");
                if (uid != null) {
                    builder.append(uid);
                } else {
                    builder.append(-1);
                }
                builder.append("\n");
                writer.write(builder.toString());
            }
        } catch (IOException e) {
            log.severe(String.format("error when writing node file: %s", e.getMessage()));
        }
        return nodeHashToId;
    }

    public static void saveEdges(String outputPath,
                                 Map<Integer, Integer> nodeHashToId,
                                 List<GEdge> edgeList) throws IOException {
        String fileName = outputPath + File.separator + sEncoding + File.separator + sEdge + ".csv";
        File edgeFile = new File(fileName);
        if (!edgeFile.exists()) {
            boolean fileCreated = edgeFile.createNewFile();
            if (fileCreated) {
                log.fine(sEdge + " file created");
            }
        }
        try (FileWriter writer = new FileWriter(fileName, false)) {
            writer.write(",");
            writer.write(join(COLUMN_HEADER_EDGE, ",") + "\n");
            int i = -1;
            for (GEdge edge : edgeList) {
                i += 1;
                String builder = i + "," +
                        nodeHashToId.get(edge.src.getUuid()) +
                        "," +
                        nodeHashToId.get(edge.tgt.getUuid()) +
                        "," +
                        GEdge.ApkRelationships.valueOf(edge.relation).ordinal() +
                        "\n";
                writer.write(builder);
            }
        } catch (IOException e) {
            log.severe(String.format("error when writing edge file: %s", e.getMessage()));
        }
    }

    public static void createErrorPlaceHolder(String testFileName, String message) throws IOException {
        // to leave a placeholder, which will be overwritten if code analysis success
        // if not success, the program will skip the bad app in the next run
        File testFile = new File(testFileName);
        if (!testFile.exists()) {
            String encodingName = Paths.get(testFileName).getParent().toString();
            File encodingPath = new File(encodingName);
            if (!encodingPath.exists()) {
                boolean mk = encodingPath.mkdirs();
                if (!mk) {
                    log.warning(String.format("create encoding path fails: %s", encodingName));
                }
            }
            boolean fileCreated = testFile.createNewFile();
            if (!fileCreated) {
                log.warning(String.format("create placeholder fails: %s", testFileName));
            }
            try (FileWriter writer = new FileWriter(testFile, false)) {
                writer.write(message);
            } catch (IOException ignored) {}
        }
    }

    public static String getTestFileName(String outputPath) {
        // edge.csv is the last output file
        return outputPath + File.separator + sEncoding +
                File.separator + sEdge + ".csv";
    }

    public static boolean noErrorInEdgeFile(String filePath) {
        try {
            InputStreamReader eReader = new InputStreamReader(
                    Files.newInputStream(Paths.get(filePath)), StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(eReader);
            String line = reader.readLine();
            return line.startsWith(",From");
        } catch (IOException e) {
            return false;
        }
    }

    public static Object[] loadArscJson(String inputFile) {
        Object[] obj = null;
        StringBuilder jsonString = new StringBuilder();
        String line;
        try {
            InputStreamReader eReader = new InputStreamReader(
                    Files.newInputStream(Paths.get(inputFile)), StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(eReader);
            while ((line = reader.readLine()) != null) {
                jsonString.append(line);
            }
            reader.close();
            eReader.close();
            List<Object> objList = JSON.parseArray(jsonString.toString(), Object.class);
            obj = objList.toArray();
        } catch (IOException e) {
            log.severe("error when load json: " + e.getMessage());
        }
        return obj;
    }

    public static void rmDir(Path path) throws IOException {
        try (Stream<Path> walker = Files.walk(path)) {
            walker.sorted(Comparator.reverseOrder()).forEach(path1 -> {
                try {
                    Files.delete(path1);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    public static void saveInCodeString(String outputFile,
                                        Map<String, Tuple4<String, String, String, String>> data) {
        StringBuilder builder = new StringBuilder();
        Map<String, List<String>> xmlId2Data = new HashMap<>();
        builder.append("{");
        for (Map.Entry<String, Tuple4<String, String, String, String>> e :
                data.entrySet()) {
            String[] idXml = e.getKey().split("@");
            String id = idXml[0];
            String xml = idXml[1];
            //id, class, method, type, value
            String strData = String.format("%s %s %s %s %s",
                    id,
                    e.getValue()._1(),
                    e.getValue()._2(),
                    e.getValue()._3(),
                    e.getValue()._4().replace("\"", "'"));
            if (!xmlId2Data.containsKey(xml)) {
                xmlId2Data.put(xml, Collections.singletonList(strData));
            } else {
                List<String> tmp = new ArrayList<>(xmlId2Data.get(xml));
                tmp.add(strData);
                xmlId2Data.remove(xml);
                xmlId2Data.put(xml, tmp);
            }
        }
        for (Map.Entry<String, List<String>> entry : xmlId2Data.entrySet()) {
            builder.append(String.format("\"%s\": [", entry.getKey()));
            StringBuilder subBuilder = new StringBuilder();
            for (String d : entry.getValue()) {
                subBuilder.append(String.format("\"%s\"", d));
                subBuilder.append(",");
            }
            String subResult = subBuilder.toString();
            builder.append(subResult, 0, subResult.length() - 1);
            builder.append("],");
        }
        String result = builder.toString();
        if (!data.isEmpty()) {
            result = result.substring(0, result.length() - 1);
        }
        JSONObject jsonObject = JSON.parseObject(String.format("%s}", result));
        String jsonData = JSON.toJSONString(jsonObject, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(outputFile, false)) {
            writer.write(jsonData);
        } catch (IOException ignored) {}
    }

    public static void mergeCodeString(String uiContextJson,
                                       Map<String, Tuple4<String, String,
                                               String, String>> hardcodeStrings) {
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(uiContextJson))) {
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (IOException e) {
            log.severe("error when reading ui context json:" + e.getMessage());
        }
        String jsonString = stringBuilder.toString();
        Map<String, String> xmlId2Name = getXMLIdNameMap(new File(uiContextJson).getParent());
        JSONObject allData = JSON.parseObject(jsonString);
        for (Map.Entry<String, Tuple4<String, String, String, String>> e :
                hardcodeStrings.entrySet()) {
            String[] idXml = e.getKey().split("@");
            String id = idXml[0];
            String xml = xmlId2Name.get(idXml[1]) + ".xml";
            // TODO: add title attribute
            // clazz = e.getValue()._1()
            // method = e.getValue()._2()
            String type = e.getValue()._3().substring(3).toLowerCase();
            String value = e.getValue()._4().replace("\"", "'");
            if (!allData.containsKey(xml)) {
                log.fine(String.format("%s not in ui_context", xml));
                continue;
            }
            JSONObject xmlData = (JSONObject) allData.get(xml);
            if (!xmlData.containsKey(id)) {
                log.warning(String.format("id %s not exist in %s", id, xml));
            } else {
                JSONObject uiData = (JSONObject) xmlData.get(id);
                String newValue;
                if (uiData.containsKey(type)) {
                    String v = (String) uiData.get(type);
                    if (Arrays.asList(v.split("\\|")).contains(value)) {
                        continue;
                    }
                    if (v.equals("NOT_FOUND")) {
                        newValue = value;
                    } else {
                        newValue = String.format("%s|%s", v, value);
                    }
                    uiData.remove(type);
                    uiData.put(type, newValue);
                } else {
                    uiData.put(type, value);
                }
                xmlData.remove(id);
                xmlData.put(id, uiData);
                allData.remove(xml);
                allData.put(xml, xmlData);
            }
        }

        String jsonData = JSON.toJSONString(allData, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(uiContextJson, false)) {
            writer.write(jsonData);
        } catch (IOException ignored) {}
    }

    private static Map<String, String> getXMLIdNameMap(String workPath) {
        Map<String, String> xmlIdNameMap = new HashMap<>();
        String publicXML = Paths.get(workPath, "values", "public.xml").toAbsolutePath().toString();
        try {
            File inputFile = new File(publicXML);
            SAXReader reader = new SAXReader();
            Document document = reader.read(inputFile);
            List<Element> elements = document.getRootElement().elements();
            for (Element element : elements) {
                if (element.attributeValue("type").equals("layout")) {
                    String id = element.attributeValue("id");
                    String name = element.attributeValue("name");
                    xmlIdNameMap.put(String.valueOf(hex2dec(id)), name);
                }
            }
        } catch (DocumentException e) {
           log.severe(String.format("error when read public xml: %s", e));
        }
        return xmlIdNameMap;
    }

    public static int hex2dec(String hex) {
        // remove 0x first
        return Integer.parseInt(hex.substring(2), 16);
    }

    public static String join(String[] array, String splitter) {
        StringBuilder builder = new StringBuilder();
        for (String o : array) {
            builder.append(o);
            builder.append(splitter);
        }
        String result = builder.toString();
        return result.substring(0, result.length() - splitter.length());
    }

    @Deprecated
    public static void printOpCode(SootClass klass, String opCodePath) {
        String fileName = Paths.get(opCodePath, klass.toString().
                replace(":","-").
                replace("<", "").
                replace(">", "").
                replace("?", "").
                replace("*", "").
                replace("|", "")) + ".txt";

        File file = new File(fileName);
        if (file.exists()) return;
        try {
            OutputStream outputStream = Files.newOutputStream(file.toPath());
            PrintWriter writer = new PrintWriter(outputStream);
            JasminClass jasminClass = new soot.jimple.JasminClass(klass);
            jasminClass.print(writer);
            writer.flush();
            writer.close();
        } catch (Exception ignored) {}
    }

    public static void saveEventFile(Map<String, HashMap<String, HashSet<String>>> events, String path) {
        Path eventXML = Paths.get(path, "event.xml");
        try (OutputStream outputStream = Files.newOutputStream(eventXML)) {
            try (PrintWriter writer = new PrintWriter(outputStream)) {
                writer.write("<GUIHierarchy>\n");
                for (Map.Entry<String, HashMap<String, HashSet<String>>> entry : events.entrySet()) {
                    String xml = entry.getKey();
                    writer.write(String.format("  <Activity name=\"%s\">\n", xml));
                    for (Map.Entry<String, HashSet<String>> subEntry : entry.getValue().entrySet()) {
                        String uid = subEntry.getKey();
                        writer.write(String.format("    <View id=\"%s\">\n", uid));
                        for (String event : subEntry.getValue()) {
                            writer.write(String.format("      <EventAndHandler handler=\"%s\" />\n", event));
                        }
                        writer.write("    </View>\n");
                    }
                    writer.write("  </Activity>\n");
                }
                writer.write("</GUIHierarchy>\n");
                writer.flush();
            }
        } catch (Exception ignored) {}
    }

    public static Map<String, Set<String>> getViewListFromLayouts(String uiContextJson) {
        Map<String, Set<String>> results = new HashMap<>();
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(uiContextJson))) {
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (IOException e) {
            log.severe("error when reading ui context json:" + e.getMessage());
        }
        String jsonString = stringBuilder.toString();
        Map<String, String> xmlId2Name = getXMLIdNameMap(new File(uiContextJson).getParent());
        JSONObject allData = JSON.parseObject(jsonString);
        for (String id : xmlId2Name.keySet()) {
            JSONObject data = (JSONObject) allData.get(xmlId2Name.get(id) + ".xml");
            if (data == null) continue;
            Set<String> views = new HashSet<>();
            for (String key: data.keySet()) {
                if (!(key.startsWith("i-")))
                    views.add(key);
            }
            results.put(id, views);
        }
        return results;
    }
}