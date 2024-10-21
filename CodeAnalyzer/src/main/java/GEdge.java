public class GEdge implements Comparable<GEdge>{
    public int uuid;
    public GNode src;
    public GNode tgt;
    public String relation;

    public enum ApkRelationships {
        CALL, FIND, HOLD, USE, EVENT
    }

    public GNode getSrc() {
        return src;
    }

    public GNode getTgt() {
        return tgt;
    }

    public GEdge(GNode src, GNode tgt, String relation) {
        this.src = src;
        this.tgt = tgt;
        String str = src.getUuid() + tgt.getUuid() + relation;
        this.uuid = str.hashCode();
        this.relation = relation;
    }

    public int getId() {
        return uuid;
    }

    @Override
    public int compareTo(GEdge o) {
        int anotherId = o.getId();
        return Integer.compare(uuid, anotherId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GEdge edge = (GEdge) o;
        return uuid == edge.uuid;
    }

    @Override
    public int hashCode() {
        return uuid;
    }
}
