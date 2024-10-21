import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import soot.jimple.infoflow.android.resources.ARSCFileParser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unused declaration")
public class AppendLayout {
    String rootPath;
    String layoutPath;
    List<Path> layoutFiles;
    int controlNum = 0;
    int containerNum = 0;
    int edgeNum = 0;
    int textNum = 0;
    ArrayList<String> cypherList;
    private String currentFile;

    private final Set<GNode> layoutNodeSet;
    private final Set<GEdge> layoutEdgeSet;

    private final Map<String, String> idToText;
    private final Map<String, String> nameToText;
    private final Map<String, Integer> control2Num;

    public AppendLayout(String rootPath, Object[] arsc) throws IOException {
        this.rootPath = rootPath;
        this.layoutPath = Paths.get(rootPath, "layout").toAbsolutePath().toString();

        try (Stream<Path> pathStream = Files.walk(Paths.get(this.layoutPath))) {
            layoutFiles = pathStream.filter((s) -> s.toString().endsWith(".xml")).collect(Collectors.toList());
        }
        cypherList = new ArrayList<>();
        layoutEdgeSet = new HashSet<>();
        layoutNodeSet = new HashSet<>();
        idToText = new HashMap<>();
        nameToText = new HashMap<>();
        control2Num = new HashMap<>();
        int i = -1;
        boolean isJson = arsc[0] instanceof com.alibaba.fastjson2.JSONObject;
        if (isJson) {
            for (Object resource: arsc) {
                i += 1;
                try {
                    int id = ((com.alibaba.fastjson2.JSONObject) resource).getIntValue("resourceID");
                    String hexId = Integer.toHexString(id);
                    String str = ((com.alibaba.fastjson2.JSONObject) resource).getString("value");
                    String name = ((com.alibaba.fastjson2.JSONObject) resource).getString("resourceName");
                    idToText.put(hexId, str);
                    nameToText.put(name, str);
                } catch (ClassCastException e) {
                    // TODO: referenceID
                }
            }
        } else {
            for (Object resource: arsc) {
                i += 1;
                try {
                    int id = ((ARSCFileParser.StringResource) resource).getResourceID();
                    String hexId = Integer.toHexString(id);
                    String str = ((ARSCFileParser.StringResource) resource).getValue();
                    String name = ((ARSCFileParser.StringResource) resource).getResourceName();
                    idToText.put(hexId, str);
                    nameToText.put(name, str);
                } catch (ClassCastException e) {
                    // TODO: referenceID
                }
            }
        }
    }

    public void run() {
        for (Path layoutFile: layoutFiles) {
            try {
                String filePathStr = layoutFile.toAbsolutePath().toString();
                currentFile = filePathStr;
                SAXReader reader = new SAXReader();
                Document document = reader.read(new File(filePathStr));
                Element root = document.getRootElement();
                control2Num.clear();
                walkXMLNodes(root, 0);
            } catch (DocumentException ignored) {}
        }
    }

    private void walkXMLNodes(Element element, int parentUUId) {
        String name = element.getName();
        String type;
        String id = null;
        String text;
        String xmlName = currentFile.replace(this.layoutPath, "").
                substring(1).replace(File.separator, ":");
        if (name.toLowerCase().endsWith("layout") ||
            name.toLowerCase().endsWith("container")) {
            type = GNode.ApkNodeLabels.CONTAINER.toString();
            containerNum ++;
        } else {
            type = GNode.ApkNodeLabels.CONTROL.toString();
            controlNum ++;
        }

        Attribute attrId = element.attribute("id");
        if (attrId != null) {
            id = attrId.getValue();
        }
        if (control2Num.containsKey(name)) {
            control2Num.replace(name, control2Num.get(name) + 1);
        } else {
            control2Num.put(name, 1);
        }

        // to avoid same uuid values for controls without id in one xml
        // note that the same id can occur >1 times in one xml
        String salt = control2Num.get(name) + id;
        GNode gNode = new GNode(name, type, xmlName + salt);
        gNode.addAttribute("id", id);
        gNode.addAttribute("xml", xmlName);

        Attribute attrText = element.attribute("text");
        if (attrText != null) {
            text = getText(attrText.getValue());
            text = text.replace("\"","\\\"");
            text = text.replace("\n","\\n");
            gNode.addAttribute("text", text);
            textNum++;
        }
        if (name.equalsIgnoreCase("include")) {
            Attribute attrLayout = element.attribute("layout");
            if (attrLayout != null) {
                String layout = attrLayout.getValue();
                gNode.addAttribute("layout", layout);
            }
        }

        layoutNodeSet.add(gNode);

        int uuid = gNode.getUuid();
        // add an edge between current element and its parent
        if (parentUUId != 0) {
            layoutEdgeSet.add(new GEdge(
                    new GNode(parentUUId), new GNode(uuid),
                    GEdge.ApkRelationships.HOLD.toString()));
            edgeNum ++;
        }

        Iterator<?> iterator = element.elementIterator();
        while (iterator.hasNext()) {
            Element childElement = (Element) iterator.next();
            walkXMLNodes(childElement, uuid);
        }
    }

    private String getText(String input) {
        input = input.toLowerCase();
        // find text by resource name (e.g., "@string/hello")
        // note that texts like "/" exist
        if(input.contains("/") && !input.endsWith("/")) {
            String name = input.split("/")[1];
            return nameToText.getOrDefault(name, input);
        }
        // find text by resource id (e.g., "@7f823fd1")
        if (input.startsWith("@") && input.length() > 1) {
            input = input.substring(1);
            return idToText.getOrDefault(input, input);
        }
        // empty or hard-code field
        return input;
    }

    public int getLayoutFileNum() {
        return layoutFiles.size();
    }

    public int getControlNum() {
        return controlNum;
    }

    public int getContainerNum() {
        return containerNum;
    }

    public int getTextNum() {
        return textNum;
    }

    public int getEdgeNum() {
        return edgeNum;
    }

    public int getNodeNum() {
        return containerNum + controlNum;
    }

    public Set<GNode> getLayoutNodeSet() {
        return layoutNodeSet;
    }

    public Set<GEdge> getLayoutEdgeSet() {
        return layoutEdgeSet;
    }
}
