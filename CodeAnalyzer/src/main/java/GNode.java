import java.util.*;

public class GNode implements Comparable<GNode>{
    private final int uuid;

    public enum ApkNodeLabels {
        METHOD, ANDROID, JAVA, CONTROL, CONTAINER
    }

    public String name;

    private Map<String, String> attributes;

    public String type;

    public Set<GNode> outNodes;

    public GNode(String name, String type){
        this.name = name;
        this.type = type;
        String s = name + type;
        this.uuid = s.hashCode();
    }

    public GNode(String name, String type, String salt){
        this.name = name;
        this.type = type;
        String s = name + salt;
        this.uuid = s.hashCode();
    }

    public GNode(int uuid) {
        this.uuid = uuid;
    }

    public GNode(int uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public int getUuid() { return uuid; }

    @Override
    public int compareTo(GNode o) {
        int anotherId = o.getUuid();
        return Integer.compare(uuid, anotherId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GNode node = (GNode) o;
        return uuid == node.uuid;
    }

    @Override
    public int hashCode() {
        return uuid;
    }

    @SuppressWarnings("unused declaration")
    public void addOutEdge(GNode node) {
        outNodes.add(node);
    }

    @SuppressWarnings("unused declaration")
    public Set<GNode> getOutNodes() {
        return outNodes;
    }

    public int getTypeID() {
        return GNode.ApkNodeLabels.valueOf(this.type.toUpperCase()).ordinal();
    }

    public void addAttribute(String key, String value) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }
        attributes.put(key, value);
    }

    public String getAttribute(String key) {
        return attributes.getOrDefault(key, null);
    }

    public void setType(ApkNodeLabels label) {
        this.type = label.toString();
    }

    public static void updateNodeType(GNode n) {
        String prefix = n.name.split("\\.")[0];
        if (prefix.replace("<android", "").length() < 2 ||
            n.name.startsWith("<com.android.")) {
            // android. and androidx.
            n.setType(GNode.ApkNodeLabels.ANDROID);
        } else if (prefix.replace("<java", "").length() < 2 ||
            n.name.startsWith("<com.sun.")) {
            // java. and javax.
            n.setType(GNode.ApkNodeLabels.JAVA);
        } else {
            n.setType(GNode.ApkNodeLabels.METHOD);
        }
    }

    public static void addNodeClass(GNode n) {
        String clazz = n.name.substring(1, n.name.indexOf(":"));
        n.addAttribute("class", clazz);
    }
}
