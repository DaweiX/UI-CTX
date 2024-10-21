import argparse
import json
import os
from typing import List

import networkx as nx
import matplotlib.pyplot as plt
from os.path import join, exists, basename, dirname, abspath
from os import makedirs
import numpy as np
import pandas as pd
from tqdm import tqdm


class GraphDrawer:

    def __init__(self, work_path, out_folder: str="", single: bool = True):
        self.work_path = work_path
        if len(out_folder) == 0:
            out_folder = join(work_path, "behaviors")
        self.out_folder = out_folder
        if not exists(self.out_folder):
            makedirs(self.out_folder)
        self.nodes = None
        self._ui_context = None
        if not single:
            self.root_path = work_path
        else:
            self.root_path = None

    @staticmethod
    def _create_digraph(graph: dict):
        digraph = nx.DiGraph()
        digraph.clear()
        for from_node, to_nodes in graph.items():
            for to_node in to_nodes:
                digraph.add_edge(from_node, to_node)
        return digraph

    @staticmethod
    def _draw_digraph(digraph, name: str, save_path: str = "",
                      show: bool = False):
        if show:
            color_dict = {"0": "blue", "1": "brown",
                          "2": "purple", "3": "orange",
                          "4": "orange", "5": "green"}
            # pos = nx.drawing.nx_agraph.graphviz_layout(digraph, prog="dot")
            # pos = nx.nx_pydot.graphviz_layout(digraph, prog="neato")
            pos = nx.drawing.nx_agraph.graphviz_layout(digraph, prog="neato")
            # nodes
            types = []
            names = []
            for n in digraph.nodes:
                types.append(digraph.nodes.get(n)["type"])
                names.append(digraph.nodes.get(n)["Name"])

            types_set = set(types)
            ts = np.array(types)
            for _type in types_set:
                _indices = np.argwhere(ts == _type)
                _indices.squeeze()
                node_list = np.array(np.array(names)[_indices])
                node_list = np.squeeze(node_list, 1).tolist()
                # noinspection SpellCheckingInspection
                options = {"node_color": "white", "alpha": 0.9, "edgecolors": color_dict[_type]}
                nx.draw_networkx_nodes(digraph, pos, nodelist=node_list, **options)

            # node labels
            nx.draw_networkx_labels(digraph, pos, font_size=6)

            # edges
            nx.draw_networkx_edges(digraph, pos, edge_color="gray")

            # edge labels
            edge_labels = nx.get_edge_attributes(digraph, "relation")
            nx.draw_networkx_edge_labels(digraph, pos, edge_labels, font_size=6)
            plt.title(name)
            plt.tight_layout()
            plt.axis("off")
            # plt.show()
            plt.savefig(join(save_path, f"{name}.svg"))
        if len(save_path) > 0:
            # file with too long name will not be created successfully
            name = '-'.join(name.replace(',', '-').split('-')[:5])
            # conda install -c conda-forge pygraphviz
            nx.nx_agraph.write_dot(digraph, join(save_path, f"{name}.dot"))

    def draw_graphs_from_behaviors(self, target_fids: list = None,
                                   is_reduce: bool = True, out_path=None):
        if target_fids is not None:
            target_fids = [str(tt) for tt in target_fids]
        if self.root_path is None:
            self.draw(target_fids, is_reduce, out_path)
        else:
            apks = os.listdir(self.root_path)
            with tqdm(total=len(apks), desc="print graphs") as bar:
                for apk in apks:
                    self.work_path = join(self.root_path, apk)
                    self.draw(target_fids, is_reduce, out_path)
                    self._ui_context = None
                    bar.update()

    def draw(self, target_fids: list = None, is_reduce: bool = True, out_path=None):
        node_file = join(self.work_path, "encoding", "node.csv")
        if not exists(node_file):
            return
        self.nodes = pd.read_csv(node_file)
        # print(len(nodes))
        with open(join(self.work_path, "encoding",
                       f"uhg{'' if is_reduce else '_full'}.json"),
                  encoding="utf-8", mode="r") as ff:
            behaviors = json.loads(ff.read())
            for i, behavior in enumerate(behaviors):
                if "uid" not in behavior:
                    continue
                uid = behavior["uid"]
                if target_fids is not None:
                    if uid not in target_fids:
                        continue
                name = f"{basename(self.work_path)}-{i}"
                name = name.strip().replace(" ", "-")
                out_folder = self.out_folder if out_path is None else out_path
                self._draw_single_behavior(behavior=behavior, nodes=self.nodes,
                                           name=name, out_path=out_folder)

    def _draw_single_behavior(self, behavior: dict, nodes: pd.DataFrame,
                              name: str, out_path: str):
        digraph = nx.DiGraph()
        relation2label = {
            0: "call",
            3: "use",
            4: "event"
        }

        def _add_edge(_head, _tail, _relation):
            # ui nodes are always heads
            head_name = _head["Name"]
            tail_name = _tail["Name"]
            if _head["UI"] == 1:
                head_name += str(_head["UId"])
                _hint, _text = self._find_hint_text(_head)
                if len(_text):
                    # node name with % will raise an error of networkx
                    _text = _text.replace("%", "[percent]")
                    digraph.add_node(_text)
                    digraph.add_edge(head_name, _text, label="text")
                if len(_hint):
                    _hint = _hint.replace("%", "[percent]")
                    digraph.add_node(_hint)
                    digraph.add_edge(head_name, _hint, label="hint")
            digraph.add_node(head_name)
            digraph.add_node(tail_name)
            digraph.add_edge(head_name, tail_name,
                             label=relation2label[_relation])

        if exists(join(out_path, f"{name}.dot")):
            return
        edges: List[str] = behavior["edges"]
        if len(edges) == 0:
            return

        # digraph.clear()
        for e in edges:
            head_id, tail_id, relation = e.split(" ")
            head_id, tail_id = int(head_id), int(tail_id)
            relation = int(relation)
            head = nodes.iloc[head_id]
            tail = nodes.iloc[tail_id]
            _add_edge(head, tail, relation)

        self._draw_digraph(digraph=digraph, name=name, save_path=out_path)

    def _find_hint_text(self, node):
        result = ["", ""]
        xml = node["XML"]
        uid = str(node["UId"])
        if xml in self.ui_context:
            x = self.ui_context[xml]
            if uid in x:
                u = x[uid]
                if "hint" in u:
                    result[0] = u["hint"]
                if "text" in u:
                    result[1] = u["text"]
        return result

    @property
    def ui_context(self):
        if self._ui_context is not None:
            return self._ui_context
        else:
            with open(join(self.work_path, "ui_context.json"), mode="r",
                      encoding="utf-8") as f:
                self._ui_context = json.load(f)
            return self._ui_context


def parse_arg_draw(args: list):
    parser = argparse.ArgumentParser(description="Output dot graphs for behaviors")
    parser.add_argument("path", help="path to input KGs' root", type=str)
    parser.add_argument("--output", "-o", type=str, default="",
                        help="path for output")
    parser.add_argument("--single_apk", "-s", action="store_true")
    _args = parser.parse_args(args)
    return _args


def add_edge(digraph, _head, _tail, _relation):
    relation2label = {
        0: "call",
        4: "event"
    }
    if _relation not in relation2label:
        return
    head_name = _head["Name"]
    tail_name = _tail["Name"]
    if _head["UI"] == 1:
        head_name += str(_head["UId"])
    digraph.add_node(head_name)
    digraph.add_node(tail_name)
    digraph.add_edge(head_name, tail_name,
                     label=relation2label[_relation])
    

def draw_from_name(name: str, out_folder: str):
    data_path = join(dirname(dirname(abspath(__file__))), "Data")
    root_path = join(data_path, "ui-ctx-data")
    _, apk, bid = name.split('-')
    bid = int(bid)
    work_path = join(root_path, apk)
    node_csv = join(work_path, "node.csv")
    if not exists(node_csv):
        return
    nodes = pd.read_csv(node_csv, low_memory=False)
    behavior_file = join(work_path, "uhg.json")

    if not exists(behavior_file):
        return

    with open(behavior_file, encoding="utf-8", mode="r") as ff:
        behaviors = json.loads(ff.read())
        behavior = behaviors[bid]

    digraph = nx.DiGraph()

    if exists(join(out_folder, f"{name}.dot")):
        return
    edges: List[str] = behavior["edges"]
    if len(edges) == 0:
        return

    for e in edges:
        head_id, tail_id, relation = e.split(" ")
        head_id, tail_id = int(head_id), int(tail_id)
        relation = int(relation)
        head = nodes.iloc[head_id]
        tail = nodes.iloc[tail_id]
        add_edge(digraph, head, tail, relation)

    nx.nx_agraph.write_dot(digraph, join(out_folder, f"{name}.dot"))

if __name__ == "__main":
    import sys
    apk_folder = sys.argv[1]
    # e.g., Data/DemoApk/demo
    a = GraphDrawer(apk_folder)
    a.draw_graphs_from_behaviors()
    # also, we can get a dot graph by calling draw_from_name(name, path)
    # where name is a UHG item in bench.json, path is the output path
