import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.jimple.infoflow.android.axml.AXmlAttribute;
import soot.jimple.infoflow.android.axml.AXmlDocument;
import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.axml.ApkHandler;
import soot.jimple.infoflow.android.axml.parsers.AXML20Parser;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class AXmlParser {
    private final StringBuilder builder;
    private final AXmlNode node;
    private HashMap<Integer, String> xmlSrcMap;
    private HashSet<String> subLayouts;
    ApkHandler apkHandler;
    private static final Logger logger = LoggerFactory.getLogger(AXmlParser.class);

    public AXmlParser(AXmlDocument document) {
        node = document.getRootNode();
        builder = new StringBuilder();
    }

    public AXmlParser(AXmlDocument document,
                      List<ARSCFileParser.AbstractResource> resources,
                      ApkHandler handler) {
        node = document.getRootNode();
        builder = new StringBuilder();
        xmlSrcMap = new HashMap<>();
        for (ARSCFileParser.AbstractResource res : resources) {
            xmlSrcMap.put(res.getResourceID(), res.toString());
        }
        // some of the resources might be ref resource in FlowDroid
        for (ARSCFileParser.AbstractResource res : resources) {
            if (res instanceof ARSCFileParser.ReferenceResource) {
                int rid = ((ARSCFileParser.ReferenceResource) res).getReferenceID();
                String v = xmlSrcMap.get(rid);
                if (v == null) {
                    logger.warn("reference ID {} not found", rid);
                }
                xmlSrcMap.put(res.getResourceID(), v);
            } else if (!(res instanceof ARSCFileParser.StringResource)) {
                logger.warn("unknown resource type: {}", res.getClass());
            }
        }
        subLayouts = new HashSet<>();
        this.apkHandler = handler;
    }

    private void read(AXmlNode node) {
        List<AXmlNode> children = node.getChildren();
        int size = children.size();
        if (size > 0) {
            builder.append(this.getNodeStr(node));
            for (AXmlNode child : children) {
                read(child);
            }
            builder.append("</");
            builder.append(node.getTag());
            builder.append(">");
        } else {
            builder.append(this.getNodeStr(node).replace(">", "/>"));
        }
    }

    private String XMLSafe(String value) {
        return value
            .replaceAll("&", "&amp;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&apos;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;");
    }

    private void readLayout(AXmlNode node) {
        // when readLayout, the difference to read
        // is that we need to parse include nodes
        logger.debug(node.getTag());
        if (node.getTag().equals("include")) {
            if (!node.hasAttribute("layout")) {
                logger.warn("no layout attr found in node: {}", node);
            } else {
                String idStr = String.valueOf(node.getAttribute("layout").getValue());
                int id = Integer.parseInt(idStr);
                if (!xmlSrcMap.containsKey(id)) {
                    logger.warn("unknown included layout id: {}", id);
                } else {
                    String layoutName = xmlSrcMap.get(id);
                    subLayouts.add(layoutName);
                    try {
                        InputStream inputStream = apkHandler.getInputStream(layoutName);
                        AXML20Parser parser = new AXML20Parser();
                        parser.parseFile(IOUtils.toByteArray(inputStream));
                        AXmlDocument document = parser.getDocument();
                        AXmlNode rootNode = document.getRootNode();
                        readLayout(rootNode);
                    } catch (IOException ignored) {
                        logger.warn("io exception when loading include layout");
                    }
                }
            }
        }
        List<AXmlNode> children = node.getChildren();
        int size = children.size();
        if (size > 0) {
            builder.append(this.getNodeStr(node));
            for (AXmlNode child : children) {
                readLayout(child);
            }
            builder.append("</");
            builder.append(node.getTag());
            builder.append(">");
        } else {
            builder.append(this.getNodeStr(node).replace(">", "/>"));
        }
    }

    private String getNodeStr(AXmlNode aXmlNode) {
        StringBuilder builder = new StringBuilder();
        builder.append("<");
        builder.append(aXmlNode.getTag());
        Map<String, AXmlAttribute<?>> attributes = aXmlNode.getAttributes();
        for (Map.Entry<String, AXmlAttribute<?>> attr : attributes.entrySet()) {
            String key = attr.getKey();
            String value = attr.getValue().getValue().toString();
            builder.append(" ");
            builder.append(key);
            builder.append("=\"");
            builder.append(XMLSafe(value));
            builder.append("\"");
        }
        builder.append(">");
        return builder.toString();
    }

    @Override
    public String toString() {
        if (xmlSrcMap == null) {
            read(node);
        } else {
            readLayout(node);
        }
        return builder.toString();
    }

    public HashSet<String> getSubLayouts() {
        return subLayouts;
    }
}
