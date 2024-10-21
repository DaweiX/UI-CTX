from os.path import join, exists
import pandas as pd
import json

from node import *
from uhg import UHG


class Apk:
    def __init__(self, apk_path: str, apk: str = None) -> None:
        self._apk_path = apk_path
        self._edges = None
        self._nodes = None
        self._opcode = None
        self._ui_context = None
        uhg_json = join(self._apk_path, "uhg.json")
        if not exists(uhg_json):
            raise FileNotFoundError(filename=uhg_json)
        
        with open(uhg_json, "r") as f:
            self._uhg_list = json.load(f)

        self._uhg_objects: list[UHG] = None
        self.apk = apk

    @property
    def nodes(self):
        if self._nodes is not None:
            return self._nodes
        
        node_csv = join(self._apk_path, "node.csv")
        if not exists(node_csv):
            raise FileNotFoundError(filename=node_csv)
        self._nodes = pd.read_csv(node_csv, low_memory=False)

        return self._nodes

    def _get_node(self, id):
        return self.nodes.iloc[id]

    @property
    def edges(self):
        if self._edges is not None:
            return self._edges
        
        edge_csv = join(self._apk_path, "edge.csv")
        if not exists(edge_csv):
            raise FileNotFoundError(filename=edge_csv)
        self._edges = pd.read_csv(edge_csv, low_memory=False)

        return self._edges

    @property
    def ui_context(self):
        if self._ui_context is not None:
            return self._ui_context
        
        ui_context_json = join(self._apk_path, "ui_context.json")
        if not exists(ui_context_json):
            raise FileNotFoundError(filename=ui_context_json)
        
        with open(ui_context_json, "r", encoding="utf-8") as f:
            self._ui_context = json.load(f)

        return self._ui_context
    
    @property
    def opcode(self):
        if self._opcode is not None:
            return self._opcode
        
        opcode_json = join(self._apk_path, "op_code.json")
        if not exists(opcode_json):
            raise FileNotFoundError(filename=opcode_json)
        
        with open(opcode_json, "r") as f:
            self._opcode = json.load(f)

        return self._opcode
    
    def __len__(self):
        return len(self._uhg_list)
    
    @property
    def uhgs(self):
        if self._uhg_objects is not None:
            return self._uhg_objects
        
        self._uhg_objects = []
        for uhg in self._uhg_list:
            uhg_obj = UHG(uhg, self.apk)
            # set widget
            wid = uhg_obj.wid
            widget = WidgetNode(self._get_node(wid))
            uhg_obj.widget = widget
            # set event
            eid = uhg_obj.eid
            event = MethodNode(self._get_node(eid))
            uhg_obj.event = event
            # set edges
            uhg_obj.load_edges(self.nodes)
            self._uhg_objects.append(uhg_obj)

        return self._uhg_objects
    