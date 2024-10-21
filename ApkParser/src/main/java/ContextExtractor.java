import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.io.IOUtils;
import org.dom4j.*;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.infoflow.android.axml.AXmlDocument;
import soot.jimple.infoflow.android.axml.parsers.AXML20Parser;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ContextExtractor {
    private final String workFolder;
    private static final Logger logger = LoggerFactory.getLogger(ContextExtractor.class);
    private List<String> subLayouts = null;
    private ArrayList<View> views;
    private boolean haveImage;
    private ArrayList<String> files = null;
    private final String DRAWABLE_PREFIX = "res/drawable";
    // when search for images, we prefer smaller assets to save space
    @SuppressWarnings("SpellCheckingInspection")
    private final ArrayList<String> DPI_LIST = new ArrayList<>(Arrays.asList(
            "", "-nodpi", "-ldpi", "-mdpi", "-hdpi", "-xhdpi", "-xxhdpi", "-xxxhdpi"
    ));
    private final HashMap<String, String> stringMap;
    private final HashMap<String, String> drawableMap;
    private ZipHandler zipHandler = null;
    private final String apk;
    private final ArrayList<String> draws;
    public ContextExtractor(String apk,
                            String workFolder,
                            Object[] arscStringObject,
                            List<ARSCFileParser.AbstractResource> drawables) {
        this.apk = apk;
        this.workFolder = workFolder;
        this.stringMap = new HashMap<>();
        for (Object o : arscStringObject) {
            if (!(o instanceof ARSCFileParser.StringResource))
                continue;
            ARSCFileParser.StringResource text = (ARSCFileParser.StringResource) o;
            stringMap.put(String.valueOf(text.getResourceID()), text.getValue());
        }
        this.draws = new ArrayList<>();
        this.drawableMap = new HashMap<>();
        for (ARSCFileParser.AbstractResource drawable : drawables) {
            drawableMap.put(String.valueOf(drawable.getResourceID()), drawable.getResourceName());
        }
        // load included layouts
        Path subLayoutJson = Paths.get(this.workFolder, "sub_layout.json").toAbsolutePath();
        StringBuilder jsonString = new StringBuilder();
        String line;
        try {
            InputStreamReader eReader = new InputStreamReader(
                    Files.newInputStream(subLayoutJson), StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(eReader);
            while ((line = reader.readLine()) != null) {
                jsonString.append(line);
            }
            reader.close();
            eReader.close();
            List<String> layouts = JSON.parseArray(jsonString.toString(), String.class);
            this.subLayouts = new ArrayList<>();
            for (String layout : layouts) {
                this.subLayouts.add(layout.replace("/", ":"));
            }
        } catch (IOException e) {
            logger.error(e.toString());
        }
    }

    public void handleLayouts() throws IOException {
        Path contextJson = Paths.get(this.workFolder, "ui_context.json");
        Path layoutPath = Paths.get(this.workFolder, "layout");
        File layoutFolder = layoutPath.toAbsolutePath().toFile();
        if (!layoutFolder.exists()) {
            return;
        }
        try (Stream<Path> fileStream = Files.walk(Paths.get(layoutFolder.toURI()))) {
            List<Path> files = fileStream.filter((s) -> s.toString().endsWith(".xml")).
                    collect(Collectors.toList());
            StringBuilder result = new StringBuilder();
            result.append("{");
            for (Path p : files) {
                File layoutFile = p.toFile();
                views = new ArrayList<>();
                String layoutName = layoutFile.getAbsolutePath().
                        replace(layoutFolder.getAbsolutePath(), "").
                        substring(1).replace(File.separator, ":");
                // we do not handle included layouts since
                // they are already included by other layouts
                if (subLayouts.contains(layoutName)) {
                    continue;
                }
                SAXReader saxReader = new SAXReader();
                try {
                    Document document = saxReader.read(layoutFile);
                    Element root = document.getRootElement();
                    parseNode(root);
                    // save view list
                    if (views.isEmpty())
                        continue;
                    result.append(String.format("\"%s\": {", layoutName));
                    StringBuilder viewsString = new StringBuilder();
                    for (View view : views) {
                        viewsString.append(view.toString());
                        viewsString.append(',');
                    }
                    result.append(viewsString, 0, viewsString.length() - 1);
                    result.append("},");
                } catch (DocumentException e) {
                    logger.debug("bad xml: {}", e.getMessage());
                }
            }
            String resultString = result.substring(0, result.length() - 1);
            resultString = resultString.replace("\n", " ");
            resultString = resultString.replace("\r", "");
            resultString = resultString.replace("\\", "\\\\");
            resultString = resultString.replace("\\\\\"", "\\\"");
            resultString = resultString.isEmpty() ? "{}": String.format("%s}", resultString);
            try (FileWriter writer = new FileWriter(contextJson.toAbsolutePath().toString(),
                    false)) {
                JSONObject jsonObject = JSON.parseObject(resultString);
                String jsonData = JSON.toJSONString(jsonObject, JSONWriter.Feature.PrettyFormat);
                writer.write(jsonData);
            } catch (Exception e) {
                logger.warn("error when writing context json: {}", e.getMessage());
            }
        }
    }

    private void parseNode(Element node) {
        if (node.hasContent()) {
            for (Iterator<Element> it = node.elementIterator(); it.hasNext();) {
                Element childNode = it.next();
                parseNode(childNode);
            }
        } else {
            // only nodes without contents may have visible semantics
            String name = node.getName();
            View tempView = new View(name);
            for (Iterator<Attribute> it = node.attributeIterator(); it.hasNext(); ) {
                Attribute attr = it.next();
                String attrName = attr.getName();
                String attrValue = attr.getValue();
                if (attrName.equals("id")) {
                    tempView.setId(attrValue);
                } else {
                    tempView.putAttr(attrName, attrValue);
                }
            }
            if (!tempView.shouldSkip()) {
                // parse attr values from id to literal
                String _name = tempView.getName();
                String _id = tempView.getId();
                View view;
                if (_id != null) {
                    view = new View(_name, _id);
                } else {
                    view = new View(_name);
                    view.setIndex(views.size());
                }
                for (Map.Entry<String, String> attr: tempView.getAttrs().entrySet()) {
                    String attrName = attr.getKey();
                    String attrValue = attr.getValue();
                    //noinspection SpellCheckingInspection
                    switch (attrName) {
                        // TODO: srcCompat (e.g., svg / vector resources)
                        case "src":
                        case "background":
                            // parse image
                            String draw = drawableMap.get(attrValue);
                            if (draw != null) {
                                view.putAttr(attrName, draw);
                                draws.add(draw);
                            } else {
                                view.putAttr(attrName, "NOT_FOUND");
                            }
                            break;
                        case "hint":
                        case "text":
                            // parse text
                            String text = stringMap.get(attrValue);
                            if (text != null) {
                                view.putAttr(attrName, text);
                            } else {
                                view.putAttr(attrName, "NOT_FOUND");
                            }
                            break;
                        default:
                            logger.error("unknown view attribute: {}", attrName);
                    }
                }
                views.add(view);
            }
        }
    }

    public void extractImages() {
        Set<String> drawSet = new HashSet<>(draws);
        for (String name : drawSet) {
            String imgPath;
            // find image based on name
            if (zipHandler == null) {
                zipHandler = new ZipHandler(apk);
            }
            if (files == null) {
                files = zipHandler.listFiles();
            }
            imgPath = findCandidate(name);
            if (imgPath == null)
                continue;
            if (!haveImage) {
                haveImage = true;
                Path image = Paths.get(workFolder, "image");
                File imageFolder = image.toFile();
                if (!imageFolder.exists()) {
                    boolean ignored = imageFolder.mkdirs();
                }
            }
            String fileName = Paths.get(imgPath).getFileName().toString();
            // save image
            try {
                InputStream inputStream = zipHandler.getInputStream(imgPath);
                Path xmlOutPath = Paths.get(workFolder, "image", fileName);
                if (imgPath.endsWith(".xml")) {
                    // we need to decode the binary xml
                    AXML20Parser parser = new AXML20Parser();
                    parser.parseFile(IOUtils.toByteArray(inputStream));
                    AXmlDocument document = parser.getDocument();
                    AXmlParser aXmlParser = new AXmlParser(document);
                    String xmlOutPathStr = xmlOutPath.toAbsolutePath().toString();
                    FileOutputStream outputStream = new FileOutputStream(xmlOutPathStr);
                    OutputFormat format = OutputFormat.createPrettyPrint();
                    XMLWriter writer = new XMLWriter(outputStream, format);
                    writer.write(DocumentHelper.parseText(aXmlParser.toString()));
                    writer.close();
                } else {
                    // image or something else
                    Files.copy(inputStream, xmlOutPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.error("error when list zip files");
            } catch (DocumentException eXml) {
                logger.warn("bad image xml");
            }
        }
    }

    public String findCandidate(String name) {
        for (String dpi : DPI_LIST) {
            List<String> candidates = files.stream().filter(
                            s -> s.startsWith(DRAWABLE_PREFIX + dpi)).
                    collect(Collectors.toList());
            for (String candidate : candidates) {
                String cName = String.valueOf(Paths.get(candidate).getFileName());
                // some developer may remove the file ext
                // e.g., F36D8B0D05586E219ABABB2EC00067AEEE9F3A8A1F835021B492F0129CEA438B
                if (!cName.contains(".")) {
                    return candidate;
                }
                // remove suffix
                cName = cName.substring(0, cName.lastIndexOf("."));
                if (cName.equalsIgnoreCase(name)) {
                    return candidate;
                }
                // 9-patch images
                if (cName.equalsIgnoreCase(name + ".9")) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
