import scala.Tuple2;

import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
class AdditionalInfo {
    Map<String, Set<String>> findEdges;
    Map<String, Set<String>> useEdges;
    Map<String, Set<Tuple2<String, String>>> switchEdges;
    Map<String, Set<Tuple2<String, String>>> threadEdges;

    public AdditionalInfo(Map<String, Set<String>> fe,
                          Map<String, Set<String>> ue,
                          Map<String, Set<Tuple2<String, String>>> se,
                          Map<String, Set<Tuple2<String, String>>> te) {
        this.findEdges = fe;
        this.useEdges = ue;
        this.switchEdges = se;
        this.threadEdges = te;
    }

    // all the getter and setters are required to print json

    public Map<String, Set<String>> getFindEdges() {
        return findEdges;
    }

    public void setFindEdges(Map<String, Set<String>> findEdges) {
        this.findEdges = findEdges;
    }

    public Map<String, Set<String>> getUseEdges() {
        return useEdges;
    }

    public void setUseEdges(Map<String, Set<String>> useEdges) {
        this.useEdges = useEdges;
    }

    public Map<String, Set<Tuple2<String, String>>> getSwitchEdges() {
        return switchEdges;
    }

    public void setSwitchEdges(Map<String, Set<Tuple2<String, String>>> switchEdges) {
        this.switchEdges = switchEdges;
    }

    public Map<String, Set<Tuple2<String, String>>> getThreadEdges() {
        return threadEdges;
    }

    public void setThreadEdges(Map<String, Set<Tuple2<String, String>>> threadEdges) {
        this.threadEdges = threadEdges;
    }
}