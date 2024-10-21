import soot.util.IterableSet;

import java.util.Set;

@SuppressWarnings("unused declaration")
public class GContainer {
    static Set<GNode> gNodes;
    static Set<GEdge> gEdges;

    public GContainer() {
        gNodes = new IterableSet<>();
        gEdges = new IterableSet<>();
    }

    public GNode findNodeByID(int uuid) {
        return null;
    }
}
