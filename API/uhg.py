from node import *


class UHG:
    def __init__(self, obj: dict, apk: str) -> None:
        self.apk = apk
        self._wid = obj["uid"]
        self._eid = obj["eid"]
        self._node_size = obj["size"]["node"]
        self._edge_size = obj["size"]["edge"]
        self._edge_list = []
        for e in obj["edges"]:
            s, e, _ = e.split()
            start_id, end_id = int(s), int(e)
            self._edge_list.append((start_id, end_id))
        # parsed objects
        self._widget = None
        self._event = None
        self._edges = None

    @property
    def size(self):
        return {
            "node": self._node_size,
            "edge": self._edge_size
        }

    @property
    def widget(self):
        return self._widget

    @property
    def wid(self):
        return self._wid

    @widget.setter
    def widget(self, node: WidgetNode):
        self._widget = node

    @property
    def event(self):
        return self._event
    
    @property
    def eid(self):
        return self._eid

    @event.setter
    def event(self, node: MethodNode):
        self._event = node

    @property
    def edges(self):
        return self._edges

    def load_edges(self, nodes):
        self._edges = []
        for e in self._edge_list:
            n1, n2 = e
            self._edges.append((nodes.iloc[n1], nodes.iloc[n2]))

    def __len__(self):
        return len(self._edge_list)

    def get_edge(self, index: int):
        start, end = self._edges[index]
        start, end = Node(start), Node(end)
        return start, end
