import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.io.IOUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlpull.v1.XmlPullParserException;
import soot.jimple.infoflow.android.axml.AXmlDocument;
import soot.jimple.infoflow.android.axml.parsers.AXML20Parser;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ResourceParser {
    private final String outPath;
    private final String apk;
    private static final String ARSC_FILE_NAME = "arsc_string.json";
    private static final String VALUES_FOLDER = "values";
    private ARSCFileParser arscFileParser;
    private Object[] arscObject;
    private List<ARSCFileParser.AbstractResource> drawables;
    private static final Logger logger = LoggerFactory.getLogger(ResourceParser.class);
    APKHandler apkHandler;

    public ResourceParser(String apk, APKHandler handler, String outPath) {
        this.outPath = Paths.get(outPath).toAbsolutePath().toString();
        this.apk = Paths.get(apk).toAbsolutePath().toString();
        if (!new File(outPath).isDirectory()) {
            boolean ignored = new File(outPath).mkdir();
        }
        this.apkHandler = handler;
    }

    public void getARSCMap() {
        if (arscFileParser == null) return;
        String arscJson = Paths.get(outPath, ARSC_FILE_NAME).toAbsolutePath().toString();
        List<ARSCFileParser.AbstractResource> resources;
        resources = arscFileParser.findResourcesByType("string");
        arscObject = resources.toArray();
        String jsonData = JSON.toJSONString(arscObject, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(arscJson, false)) {
            writer.write(jsonData);
        } catch (IOException ignored) {}
    }

    public void getManifest() {
        String manifestXML = Paths.get(outPath, "manifest.xml").toAbsolutePath().toString();
        try (ProcessManifest processManifest = new ProcessManifest(apk)) {
            AXmlDocument document = processManifest.getAXml().getDocument();
            AXmlParser aXmlParser = new AXmlParser(document);
            FileOutputStream outputStream = new FileOutputStream(manifestXML);
            OutputFormat format = OutputFormat.createPrettyPrint();
            XMLWriter writer = new XMLWriter(outputStream, format);
            writer.write(DocumentHelper.parseText(aXmlParser.toString()));
            writer.close();
        } catch (IOException | XmlPullParserException | DocumentException e) {
            logger.error(e.toString());
        }
    }

    public void getLayoutFiles() {
        HashSet<String> subLayouts = new HashSet<>();
        Path subLayoutListJson = Paths.get(outPath, "sub_layout.json").toAbsolutePath();
        AXML20Parser parser = new AXML20Parser();

        List<ARSCFileParser.AbstractResource> layouts = arscFileParser.findResourcesByType("layout");
        for (ARSCFileParser.AbstractResource layout : layouts) {
            if (!(layout instanceof ARSCFileParser.StringResource)) continue;
            logger.debug("current layout: {}", layout);
            try {
                InputStream inputStream = apkHandler.getHandler().getInputStream(layout.toString());
                parser.parseFile(IOUtils.toByteArray(inputStream));
                AXmlDocument document = parser.getDocument();
                AXmlParser aXmlParser = new AXmlParser(document, layouts, apkHandler.getHandler());
                String layoutStr = layout.toString();
                if (layoutStr.contains("/")) {
                    String[] split = layoutStr.split("/");
                    layoutStr = split[split.length - 1];
                }
                Path xmlPath = Paths.get(outPath, "layout", layoutStr).toAbsolutePath();
                File subFolder = xmlPath.getParent().toAbsolutePath().toFile();
                if (!subFolder.exists()) {
                    boolean ignored = subFolder.mkdirs();
                }
                FileOutputStream outputStream = new FileOutputStream(xmlPath.toString());
                OutputFormat format = OutputFormat.createPrettyPrint();
                XMLWriter writer = new XMLWriter(outputStream, format);
                String xmlData = aXmlParser.toString();
                writer.write(DocumentHelper.parseText(xmlData));
                writer.close();
                subLayouts.addAll(aXmlParser.getSubLayouts());
            } catch (IOException | DocumentException e) {
                logger.warn("{}{}", String.format("bad xml when print layout %s: ", layout),
                        e.getMessage());
            } catch (RuntimeException e) {
                logger.warn("error when parse layout (name: {})", layout);
            }
        }
        // write a json file to record sub layouts (to skip)
        String jsonData = JSON.toJSONString(subLayouts, JSONWriter.Feature.PrettyFormat);
        try (FileWriter writer = new FileWriter(subLayoutListJson.toString(), false)) {
            writer.write(jsonData);
        } catch (IOException ignored) {}
    }

    public void getMenuFiles() {
        AXML20Parser parser = new AXML20Parser();

        List<ARSCFileParser.AbstractResource> menus = arscFileParser.findResourcesByType("menu");
        for (ARSCFileParser.AbstractResource menu : menus) {
            if (!(menu instanceof ARSCFileParser.StringResource)) continue;
            logger.debug("current menu: {}", menu);
            try {
                InputStream inputStream = apkHandler.getHandler().getInputStream(menu.toString());
                parser.parseFile(IOUtils.toByteArray(inputStream));
                AXmlDocument document = parser.getDocument();
                AXmlParser aXmlParser = new AXmlParser(document, menus, apkHandler.getHandler());
                String menuStr = menu.toString();
                if (menuStr.contains("/")) {
                    String[] split = menuStr.split("/");
                    menuStr = split[split.length - 1];
                }
                Path xmlPath = Paths.get(outPath, "menu", menuStr).toAbsolutePath();
                File subFolder = xmlPath.getParent().toAbsolutePath().toFile();
                if (!subFolder.exists()) {
                    boolean ignored = subFolder.mkdirs();
                }
                FileOutputStream outputStream = new FileOutputStream(xmlPath.toString());
                OutputFormat format = OutputFormat.createPrettyPrint();
                XMLWriter writer = new XMLWriter(outputStream, format);
                writer.write(DocumentHelper.parseText(aXmlParser.toString()));
                writer.close();
            } catch (IOException | DocumentException e) {
                logger.warn("{}{}", String.format("bad xml when print menu %s: ", menu),
                        e.getMessage());
            } catch (RuntimeException e) {
                logger.warn("error when parse menu (name: {})", menu);
            }
        }
    }

    public void getValues() throws IOException {
        String valuesPath = Paths.get(outPath, VALUES_FOLDER).toAbsolutePath().toString();
        File valuesFolder = new File(valuesPath);
        if (!valuesFolder.exists()) {
            boolean ignored = valuesFolder.mkdirs();
        }

        if (arscFileParser == null) {
            arscFileParser = new ARSCFileParser();
        }
        arscFileParser.parse(apk);
        ARSCFileParser.ResPackage c = arscFileParser.getPackages().get(0);
        // values/public.xml
        String publicXML = Paths.get(outPath, VALUES_FOLDER, "public.xml").toAbsolutePath().toString();
        Document document = DocumentHelper.createDocument();
        Element resources = document.addElement("resources");
        for (ARSCFileParser.ResType resType: c.getDeclaredTypes()) {
            Collection<ARSCFileParser.AbstractResource> rs = resType.getAllResources();
            for (ARSCFileParser.AbstractResource r: rs) {
                Element public_ = resources.addElement("public");
                public_.addAttribute("type", resType.getTypeName());
                public_.addAttribute("name", r.getResourceName());
                public_.addAttribute("id", String.format("0x%08x", r.getResourceID()));
            }
        }
        FileOutputStream outputStream = new FileOutputStream(publicXML);
        OutputFormat format = OutputFormat.createPrettyPrint();
        XMLWriter writer = new XMLWriter(outputStream, format);
        writer.write(document);
        writer.close();
        getBoolValues();
        getIntegersValues();
        getDrawableValues();
        // we ignore attr, id, style, dim and color
    }

    private void getBoolValues() throws IOException {
        // values/bools
        List<ARSCFileParser.AbstractResource> bools = arscFileParser.findResourcesByType("bool");
        String boolXml = Paths.get(outPath, VALUES_FOLDER, "bools" + ".xml").
                toAbsolutePath().toString();
        Document document = DocumentHelper.createDocument();
        Element resources = document.addElement("resources");
        for (ARSCFileParser.AbstractResource bool: bools) {
            ARSCFileParser.BooleanResource r = (ARSCFileParser.BooleanResource) bool;
            Element r_ = resources.addElement("bool");
            r_.addAttribute("name", r.getResourceName());
            r_.addText(String.valueOf(r.getValue()));
        }
        FileOutputStream outputStream = new FileOutputStream(boolXml);
        OutputFormat format = OutputFormat.createPrettyPrint();
        XMLWriter writer = new XMLWriter(outputStream, format);
        writer.write(document);
        writer.close();
    }

    private void getIntegersValues() throws IOException {
        // values/integers
        List<ARSCFileParser.AbstractResource> integers = arscFileParser.findResourcesByType("integer");
        String idXml = Paths.get(outPath, VALUES_FOLDER, "integers" + ".xml").
                toAbsolutePath().toString();
        Document document = DocumentHelper.createDocument();
        Element resources = document.addElement("resources");
        for (ARSCFileParser.AbstractResource integer: integers) {
            if (integer instanceof ARSCFileParser.IntegerResource) {
                ARSCFileParser.IntegerResource r = (ARSCFileParser.IntegerResource) integer;
                Element r_ = resources.addElement("integer");
                r_.addAttribute("name", r.getResourceName());
                r_.addText(String.valueOf(r.getValue()));
            } else if (integer instanceof ARSCFileParser.FloatResource) {
                ARSCFileParser.FloatResource r = (ARSCFileParser.FloatResource) integer;
                Element r_ = resources.addElement("integer");
                r_.addAttribute("name", r.getResourceName());
                r_.addText(String.valueOf(r.getValue()));
            }
        }
        FileOutputStream outputStream = new FileOutputStream(idXml);
        OutputFormat format = OutputFormat.createPrettyPrint();
        XMLWriter writer = new XMLWriter(outputStream, format);
        writer.write(document);
        writer.close();
    }

    private void getDrawableValues() throws IOException {
        // values/drawables
        drawables = arscFileParser.findResourcesByType("drawable");
        String drawableXml = Paths.get(outPath, VALUES_FOLDER, "drawables" + ".xml").
                toAbsolutePath().toString();
        Document document = DocumentHelper.createDocument();
        Element resources = document.addElement("resources");
        for (ARSCFileParser.AbstractResource drawable: drawables) {
            Element r_ = resources.addElement("item");
            r_.addAttribute("type", "drawable");
            r_.addAttribute("name", drawable.getResourceName());
            r_.addText(String.valueOf(drawable.getResourceID()));
        }
        FileOutputStream outputStream = new FileOutputStream(drawableXml);
        OutputFormat format = OutputFormat.createPrettyPrint();
        XMLWriter writer = new XMLWriter(outputStream, format);
        writer.write(document);
        writer.close();
    }

    public Object[] getArscStringObject() {
        return arscObject;
    }

    public List<ARSCFileParser.AbstractResource> getDrawables() {
        return drawables;
    }
}
