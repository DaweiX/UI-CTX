"""Generate sub-graphs from KG, and reduce the graphs"""
import argparse
import multiprocessing
from concurrent.futures import ThreadPoolExecutor, as_completed
import sys
import os
import time
from os.path import join, exists, isfile, getsize, dirname
from os import listdir
from typing import List, Iterable
from time import perf_counter
from functools import partial
from xml.dom.minidom import parseString, Element

import pandas as pd
from pandas import Series

from filter import Filter
from util import data_flow_xml2json, xml_init
import json
import numpy as np
from tqdm import tqdm
import traceback

root_path = os.path.abspath(dirname(__file__))
sys.path.append(root_path)


def error(msg, *args):
    return multiprocessing.get_logger().error(msg, *args)


class LogExceptions(object):
    def __init__(self, _callable):
        self.__callable = _callable
        return

    def __call__(self, *args, **kwargs):
        try:
            result = self.__callable(*args, **kwargs)

        except Exception as _:
            # Here we add some debugging help. If multiprocessing
            # debugging is on, it will arrange to log the traceback
            error(traceback.format_exc())
            # Re-raise the original exception so the Pool worker can
            # clean up
            raise

        # It was fine, give a normal answer
        return result

    pass


# noinspection PyTypeChecker
class BehaviorProcessor:
    def __init__(self, work_path: str,
                 remove_leaf: bool = True,
                 print_no_callee: bool = False,
                 rewrite_uhg_full: bool = False,
                 save_uhg_full: bool = False,
                 rewrite_uhg: bool = False,
                 native_hop: int = -1,
                 find_edge: bool = False,
                 nodes: pd.DataFrame = None,
                 edges: pd.DataFrame = None,
                 debug_mode: bool = False):
        """
        Args:
            native_hop (int): -1 to keep all edges started from a native api call,
              else hop number to keep. e.g., 0 to stop at native apis
        """
        # work_path: output folder for an app
        self.ui_controls_list_all = None
        self._edges = None
        self._behaviors = None

        self._work_path = work_path
        self.behavior_code = 1
        self._skip = False
        self.debug_mode = debug_mode
        self._mundane_calls = None
        self._mundane_classes = None

        self.relation_find = 1
        self.relation_use = 3
        self.relation_hold = 2
        self.relation_call = 0
        self.relation_event = 4
        self.find_edge = find_edge

        self.ui_controls_act = []
        self.ui_controls_all = []
        self.relations = dict()
        self.graph = dict()
        self.ui_code_edges = set()
        self._xml2id = None

        # load edges
        if edges is not None:
            self.edges = edges
        else:
            csv_path = join(self._work_path, "encoding", "edge.csv")
            self.edges: pd.DataFrame = pd.read_csv(
                csv_path, encoding="utf-8"
            )
        # load nodes
        if nodes is not None:
            self._nodes = nodes
        else:
            csv_path = join(self._work_path, "encoding", "node.csv")
            self._nodes: pd.DataFrame = pd.read_csv(
                csv_path, usecols=["Name", "Package", "UId", "XML"],
                encoding="utf-8", dtype={"Package": str}
            )
        not_self_ids = self._nodes[self._nodes["Package"] != "-"]
        not_self_ids = not_self_ids.index.tolist()
        self.not_self_ids = list(map(str, not_self_ids))
        if not rewrite_uhg and not save_uhg_full:
            reduce_file = join(self._work_path, "encoding", "uhg.json")
            if exists(reduce_file):
                if getsize(reduce_file) > 0:
                    if not exists(join(self._work_path, "encoding", "uhg_full.json")):
                        self._skip = True
                        return

        self._data_flows = None

        # self._load_dataflows()

        self.remove_leaf = remove_leaf
        self.print_no_callee = print_no_callee

        # for dfs
        self.visited_nodes = set()
        self.node_stack = set()
        self.linked_edges = set()
        self.api_depth = dict()
        self.native_hop = native_hop

        self.behavior_code = self._load_behaviors(rewrite_uhg_full)

        self.save_uhg_full = save_uhg_full

    @property
    def xml2id(self):
        if self._xml2id is not None:
            return self._xml2id

        self._xml2id = {}
        public_xml = join(self._work_path, "values", "public.xml")
        with open(public_xml, mode="r", encoding="utf-8") as f:
            dom_string = f.read()
            dom = parseString(dom_string)
            xml_init(dom)
            resources: Element = dom.getElementsByTagName("resources")[0]
            elements = resources.getElementsByTagName("public")
            for e in elements:
                if e.getAttribute("type") != "layout":
                    continue
                xml_name = e.getAttribute("name")
                xml_id_hex = e.getAttribute("id")
                self._xml2id[xml_name] = int(xml_id_hex[2:], 16)
        return self._xml2id

    @staticmethod
    def _dec2hex(dec: str) -> str:
        return hex(int(dec)).upper()[2:]

    @property
    def behaviors(self):
        return self._behaviors

    def _load_dataflows(self):
        flow_file = join(self._work_path, "data_flow.json")
        if not exists(flow_file):
            data_flow_xml2json(self._work_path)

        self._data_flows = dict()  # key: ui-id (hex), value: path
        with open(flow_file, encoding="utf-8", mode="r") as f:
            sinks = json.load(f)["results"]
            for sink in sinks:
                sources = sink["sources"]
                for dataflow in sources:
                    dpath = dataflow["path"]
                    source = dpath[0]
                    if "findViewById" not in source:
                        continue
                    _, _id = source.split("==")
                    hex_id = self._dec2hex(_id)
                    self._data_flows[hex_id] = dpath[1:]  # not include findViewById
                    print(f"dataflow from ui {hex_id}, len: {len(dpath)}")

    def _load_behaviors(self, over_write: bool) -> int:
        behavior_file = join(self._work_path, "encoding", "uhg_full.json")
        if exists(behavior_file) and not over_write:
            with open(behavior_file, encoding="utf-8", mode="r") as f:
                self._behaviors = json.load(f)
                # print(f"load {len(self._behaviors)} behaviors")
        else:
            # generate one
            self._build_graph()
            self._write_behavior_file(behavior_file, native_hop=self.native_hop)
        return 0

    def _write_behavior_file(self, print_path: str,
                             native_hop: int = -1):
        """ Split sub-graphs from kg and generate behavior->id json"""
        json_array = []
        while len(self.ui_controls_act) > 0:
            start = time.perf_counter()
            ui_control = self.ui_controls_act[0]
            self.ui_controls_act.remove(ui_control)
            # we assign behaviors based on event callbacks
            # that is, each event corresponds to a behavior
            event_calls = [out_edge[0] for out_edge in self.graph[ui_control]
                           if out_edge[1] == self.relation_event]
            for event_call in event_calls:
                linked_graph, child_uis = self._search_linked_edges(event_call, native_hop)
                if str(ui_control) in child_uis:
                    child_uis.remove(str(ui_control))
                linked_graph.add(f"{ui_control} {event_call} {self.relation_event}")
                linked_graph = list(set(linked_graph))

                time_cost = perf_counter() - start
                # the min path len is 2 (ui -> self-defined -> api)
                if len(linked_graph) > 1:
                    node_ids = np.array([a.split(' ')[:2] for a in linked_graph])
                    node_ids = set(node_ids.flatten())

                    json_object = {
                        "uid": ui_control,
                        "eid": event_call,
                        "size": {
                            "node": len(node_ids),
                            "edge": len(linked_graph)
                        },
                        "edges": linked_graph,
                        "cuid": list(child_uis),
                        "time": time_cost
                    }
                    json_array.append(json_object)

        with open(print_path, mode="w+", encoding="utf-8") as f:
            json.dump(json_array, f)

        # print(f"{len(json_array)} behaviors ready in {print_path}")

        self._behaviors = json_array

    def _search_linked_edges(self, node_id: int, native_hop: int):
        child_uis = set()
        self.node_stack = set()
        self.linked_edges = set()
        self.visited_nodes = set()
        self.node_stack.add(node_id)
        self.visited_nodes.add(node_id)
        # build subgraph started from and with the given node
        self._dfs(native_hop)
        # append other ui-code use links for the subgraph
        for ui_code_edge in self.ui_code_edges:
            _ui, _call, _relation = ui_code_edge
            if _call in self.visited_nodes:
                if _relation >= self.relation_use:
                    self.linked_edges.add(f"{_ui} {_call} {_relation}")
            # we merge ui subgraph when all its outgoing
            # edges appear in the current graph
            if _ui not in child_uis:
                sub_edges = self._get_sub_edges_behavior_gen(_ui)
                existence = [e in self.linked_edges for e in sub_edges]
                if len(existence) > 0:
                    if all(existence):
                        # merge
                        child_uis.add(_ui)

        # self.visited_nodes.remove(node_id)
        return self.linked_edges, child_uis

    def _dfs(self, native_hop: int):
        while len(self.node_stack) > 0:
            current_node = self.node_stack.pop()
            children = self._get_valid_children(current_node, native_hop)
            for c in children:
                if c not in self.visited_nodes:
                    self.node_stack.add(c)
                    self.visited_nodes.add(c)

    def _get_valid_children(self, node_id: str, native_hop: int) -> list:
        new_children = []
        if node_id not in self.graph:
            return new_children

        out_edges = self.graph[node_id]
        for out_edge in out_edges:
            child_node, relation = out_edge
            # if child_node not in self.visited_nodes:
            name = self._nodes.iloc[child_node]["Name"]
            # stop at noise nodes
            if not self.debug_mode:
                if self._is_noise(name):
                    continue
            # apply reduction according to native api
            if native_hop > -1 and relation == self.relation_call:
                native_or_tpl_p = (self._nodes.iloc[node_id]["Package"] != "-")
                native_or_tpl_c = (self._nodes.iloc[child_node]["Package"] != "-")
                if native_or_tpl_c:
                    if not native_or_tpl_p:
                        self.api_depth[node_id] = -1
                    elif node_id not in self.api_depth:
                        self.api_depth[node_id] = 0
                    self.api_depth[child_node] = self.api_depth[node_id] + 1
                    if self.api_depth[child_node] > native_hop:
                        continue

            new_children.append(child_node)
            self.linked_edges.add(f"{node_id} {child_node} {relation}")

        # all the call edges in KG is identical, so we do not need to
        # remove repeated ones (i.e., cast the list to a set)
        return new_children

    @property
    def mundane_calls(self):
        if self._mundane_calls is not None:
            return self._mundane_calls
        results = []
        with open(join(dirname(__file__), "lists", "mundane_calls.txt"),
                  encoding="utf-8", mode="r") as f:
            for line in f:
                if line.startswith("//"):
                    continue
                results.append(line.strip())
        self._mundane_calls = results
        return self._mundane_calls

    @property
    def mundane_classes(self):
        if self._mundane_classes is not None:
            return self._mundane_classes
        results = []
        with open(join(dirname(__file__), "lists", "mundane_classes.txt"),
                  encoding="utf-8", mode="r") as f:
            for line in f:
                if line.startswith("//"):
                    continue
                results.append(line.strip())
        self._mundane_classes = results
        return self._mundane_classes

    def _is_noise(self, name: str) -> bool:
        if "(" not in name:
            return True
        # to avoid mistakes, judge by both class name and function name
        class_name, fun_name = name.split(":")
        fun_name = fun_name.split("(")[0]
        is_noise_call = any([ni in fun_name for ni in self.mundane_calls])
        is_noise_class = any([ni in class_name for ni in self.mundane_classes])
        return is_noise_call or is_noise_class

    def _build_graph(self):
        # find edges including ui
        for row in self.edges.itertuples():
            head = getattr(row, "From")
            tail = getattr(row, "To")
            relation = getattr(row, "Type")
            # identify ui controls
            if relation == self.relation_hold:
                self.ui_controls_all.append(tail)

                # for hold edges, we reverse head and
                # tail to serve for sequence search
                if tail not in self.graph:
                    self.graph[tail] = [(head, relation)]
                else:
                    self.graph[tail].append((head, relation))
            elif relation == self.relation_find or \
                    relation == self.relation_use or \
                    relation == self.relation_event:
                if relation == self.relation_find:
                    if not self.find_edge:
                        continue
                self.ui_controls_all.append(head)
                # we take event as the first edge
                # and set the behavior id to the ui id
                if relation == self.relation_event:
                    self.ui_controls_act.append(head)

                self.ui_code_edges.add((head, tail, relation))

                if head not in self.graph:
                    self.graph[head] = [(tail, relation)]
                else:
                    self.graph[head].append((tail, relation))
                # # also need to reverse edges for find/use
                # if tail not in self.graph:
                #     self.graph[tail] = [(head, relation)]
                # else:
                #     self.graph[tail].append((head, relation))
            else:
                # other edges, e.g., normal call
                if head not in self.graph:
                    self.graph[head] = [(tail, relation)]
                else:
                    self.graph[head].append((tail, relation))

            self.ui_controls_act = list(set(self.ui_controls_act))
            self.ui_controls_act.sort()

    def not_leaf_calls(self):
        all_nodes = [str(i) for i in range(len(self._nodes))]
        # print("outer:", self._edges[:, 0])
        not_leaf_index = np.where(np.isin(all_nodes, self._edges[:, 0]))[0]
        # print("not leaf:", not_leaf_index)
        return map(str, not_leaf_index)

    def _get_sub_edges_behavior_gen(self, node_id: str):
        """get edges started from a node"""
        out_edges = self.graph[node_id]
        return [f"{node_id} {e[0]} {e[1]}" for e in out_edges]

    # noinspection SpellCheckingInspection
    def reduce_behavior(self,
                        use_filter: bool = False,
                        remove_leaf: bool = False):
        """ Reduce behaviors and generate `uhg.json`"""
        if self._skip:
            return
        reduce_json = join(self._work_path, "encoding", "uhg.json")
        # if exists(reduce_json):
        #     return
        new_json_obj = []
        start_time = perf_counter()
        if self._behaviors is None:
            return
        for i, behavior in enumerate(self._behaviors):
            _stime = perf_counter()
            uid = behavior["uid"]
            size_old = behavior["size"]
            edges = behavior["edges"]
            event = behavior["eid"]
            cuid = behavior["cuid"]
            self._edges = np.array([e.split(' ') for e in edges])
            if use_filter:
                ft = Filter()
                ft.load_data(self._work_path)
                # only if neither interesting ui nor call exists should we abandon the behavior
                if not ft.should_keep_by_ui(uid):
                    _nodes = np.unique(self._edges.flatten())
                    if not ft.should_keep_by_method(_nodes):
                        continue
            # ---------------------------------------
            # 1. remove events triggered by other uis
            # ---------------------------------------
            remove_index = np.where(self._edges[:, 2] == str(self.relation_event))
            event_index = np.where(self._edges[:, 0] == str(uid))
            new_edges: np.ndarray = np.delete(self._edges, remove_index, axis=0)
            if self.ui_controls_list_all is None:
                self.ui_controls_list_all = [int(n[0]) for n in new_edges
                                             if int(n[2]) != self.relation_call]

            # ---------------------------------------
            # 2. remove switch noises
            # ---------------------------------------
            to_removed_sne_rec = []
            parent_tbd = []
            add_info_file = join(self._work_path, "add_info.json")
            with open(add_info_file, mode="r", encoding="utf-8") as f:
                sne = json.load(f)["switchEdges"]

            sne_id2keys = {}
            for _uxid in sne:
                _id = _uxid.split("$")[0]
                if _id not in sne_id2keys:
                    sne_id2keys[_id] = [_uxid]
                else:
                    sne_id2keys[_id].append(_uxid)

            # init noise edges
            ui_node = self._nodes.loc[int(uid)]
            uid_dec = str(ui_node["UId"])
            xml = ui_node["XML"].split(".xml")[0]
            xml_dec = self.xml2id[xml]
            uxid = f"{uid_dec}${xml_dec}"
            entry = []
            if uxid in sne:
                entry = sne[uxid]
            else:
                # ensure that we do not miss any branch noise
                if uid_dec in sne_id2keys:
                    for uxid_candidate in sne_id2keys[uid_dec]:
                        event_name = self._nodes.loc[int(event)]["Name"]

                        for n in sne[uxid_candidate]:
                            na = n["_1"]
                            if na == event_name:
                                entry = sne[uxid_candidate]
                                print(f"determine layout: {uxid} -> {uxid_candidate}")
                                break

            case_edges = []
            for ii, n in enumerate(entry):
                na, nb = n["_1"], n["_2"]
                nb: str = nb[nb.index('<'):nb.index('>') + 1]
                na_id = self._nodes[self._nodes["Name"] == na].index.tolist()
                nb_id = self._nodes[self._nodes["Name"] == nb].index.tolist()
                if len(na_id) == 0:
                    parent_tbd.append(nb)
                    continue
                else:
                    na_id = na_id[0]
                    if len(nb_id) > 0:
                        # we can accurately locate the caller and callee
                        case_edges.append([str(na_id), str(nb_id[0])])
                        continue
                    else:
                        # otherwise, try to match the inherited method
                        method_name = nb[nb.index(':'):]
                        na_sub_edges = self._edges[self._edges[:, 0] == str(na_id)]
                        na_callees = na_sub_edges[:, 1]
                        for na_callee in na_callees:
                            na_callee = int(na_callee)
                            full_name = self._nodes.iloc[na_callee]["Name"]
                            if method_name in full_name:
                                case_edges.append([str(na_id), str(na_callee)])

            case_edges = np.array(case_edges)
            caller_list_all = new_edges[:, 0]

            if len(case_edges):
                caller_list = case_edges[:, 0]

                should_keep = [f"{e[0]} {e[1]} 0" for e in case_edges]
                should_keep_nodes = [e[1] for e in case_edges]

                # get all edges from all calls that contains switch-case blocks
                new_edges_filtered = new_edges[np.where(np.isin(caller_list_all, caller_list))[0]]

                to_removed_sne = [e for e in new_edges_filtered if ' '.join(e) not in should_keep]

                visited_nodes = set()
                for sne in to_removed_sne:
                    # we should use edges instead of calls to construct stack
                    sne_str = ' '.join(sne)
                    to_removed_sne_rec.append(sne_str)
                    node_stack = {sne[1]}
                    while len(node_stack) > 0:
                        current_node = node_stack.pop()
                        if current_node in should_keep_nodes:
                            continue
                        sub_edges = self._edges[self._edges[:, 0] == current_node]
                        edge_strs = [' '.join(se) for se in sub_edges]
                        to_removed_sne_rec.extend(edge_strs)
                        visited_nodes.add(current_node)
                        for n in range(len(sub_edges)):
                            cee = sub_edges[n, 1]
                            if cee not in visited_nodes:
                                node_stack.add(cee)

            to_removed_sne_rec = list(set(to_removed_sne_rec))

            # ---------------------------------------
            # 3. remove no-callee self-defined edges
            # ---------------------------------------
            no_callee_index = []

            # select all self -> self edges for candidates
            # requires more computing, so can also disable this part
            if remove_leaf:
                candidates = []
                excluded_des = {}
                call_edges = new_edges[np.where(new_edges[:, 2] == str(self.relation_call))]
                for call in call_edges:
                    caller = self._nodes.iloc[int(call[0])]
                    callee = self._nodes.iloc[int(call[1])]
                    if caller["Package"] == callee["Package"] == "-":
                        candidates.append([int(call[0]), int(call[1])])

                # we first remove all edges that do not reach api
                while self.remove_leaf:
                    removed = len(no_callee_index)
                    for c in candidates:
                        outer_type_callee = (
                            self.next_edges_type(c[1], excluded_des.get(c[1], [])))
                        if outer_type_callee == -1:
                            # no callee for this callee, we can safely
                            # remove all the edges to it
                            # note: may include ui -> callee edges
                            index = np.where(new_edges[:, 1] == str(c[1]))
                            no_callee_index.extend(index[0].tolist())
                            candidates.remove(c)
                            if c[0] not in excluded_des:
                                excluded_des[c[0]] = [c[1]]
                            else:
                                excluded_des[c[0]].append(c[1])
                    if len(no_callee_index) == removed:
                        # exit when no more no-callee edges to be removed
                        break

            all_remove_index: list = no_callee_index
            # noinspection PyTypeChecker
            new_edges = np.delete(new_edges, all_remove_index, axis=0)
            new_edges = np.vstack((self._edges[event_index], new_edges))
            new_edges: List[str] = [" ".join(e) for e in new_edges]
            old_edge_num = len(new_edges)
            new_edges: list = list(set(new_edges).difference(to_removed_sne_rec))
            all_nodes = np.unique(np.array([i.split(' ') for i in new_edges])
                                  [:, :2].flatten())
            edge_num_switch = old_edge_num - len(new_edges)
            _etime = perf_counter()

            # save new behavior
            json_obj = {
                "uid": uid,
                "eid": event,
                "size": {
                    "node": len(all_nodes),
                    "edge": len(new_edges),
                    "old": size_old
                },
                "reduce": {
                    "time": _etime - _stime,
                    "switch noise": edge_num_switch,
                },
                "edges": new_edges,
                "cuid": cuid
            }

            if len(parent_tbd):
                # output inherit methods
                json_obj["inherit_methods"] = list(set(parent_tbd))
            if self.remove_leaf:
                json_obj["reduce"]["sleaf"] = len(no_callee_index)

            new_json_obj.append(json_obj)

        end_time = perf_counter()
        self._behaviors = None
        new_json_obj.insert(0, {
            "time": end_time - start_time,
        })

        if not self.debug_mode:
            with open(reduce_json, encoding="utf-8", mode="w+") as f:
                json.dump(new_json_obj, f)

            if not self.save_uhg_full:
                behavior_json = join(self._work_path, "encoding", "uhg_full.json")
                if isfile(behavior_json):
                    os.remove(behavior_json)

    def _to_calls(self, node_id: int) -> Series:
        _out_edges = self.edges[self.edges["From"] == node_id]
        return _out_edges["To"]

    def next_edges_type(self, node: int, excluded_des: Iterable) -> int:
        to_calls = self._to_calls(node)
        if len(set(to_calls).difference(excluded_des)) == 0:
            # no out edge
            return -1

        if set(self._nodes.iloc[to_calls]["Package"]) == set("-"):
            # all next calls are self-defined
            return 0
        else:
            # has api call
            return 1

    def is_connected_to_ui(self, node: int) -> bool:
        to_edges_types = self.edges[self.edges["To"] == node]["Type"].tolist()
        return sum(to_edges_types) > 0

    def __del__(self):
        del self._behaviors
        del self.edges
        del self._edges
        del self._nodes
        del self.graph


def handle_apk(_path: str, _apk: str,
               rewrite_b: bool,
               save_b: bool,
               rewrite_r: bool,
               hop: int):
    if not exists(join(_path, _apk, "encoding", "node.csv")):
        return
    try:
        b = BehaviorProcessor(join(_path, _apk),
                              rewrite_uhg_full=rewrite_b,
                              save_uhg_full=save_b,
                              rewrite_uhg=rewrite_r,
                              native_hop=hop)

        b.reduce_behavior()
    except Exception:
        raise


def wrapper_handle_apk(args):
    return handle_apk(*args)


def parse_arg_bp(_args: list):
    parser = argparse.ArgumentParser(description="Generate sub-graphs from "
                                                 "the whole KG, and conduct reduction")
    parser.add_argument("path", help="path to input KGs' root", type=str)
    parser.add_argument("--rewrite_behavior", "-rb", action="store_true",
                        help="rewrite uhg_full.json")
    parser.add_argument("--save_behavior", "-sb", action="store_true",
                        help="save full uhg")
    parser.add_argument("--rewrite_reduce", "-rr", action="store_true",
                        help="rewrite uhg.json")
    parser.add_argument("--hop", "-hp", type=int, default=0,
                        help="hop for reduction (default: 0)")
    parser.add_argument("--thread", "-t", type=int, default="4",
                        help="threads to use (default: 4)")
    parser.add_argument("--debug", "-d", action="store_true",
                        help="verbose output (e.g., time cost)")
    return parser.parse_args(_args)


if __name__ == '__main__':
    input_args = parse_arg_bp(sys.argv[1:])
    input_path: str = input_args.path
    thread: int = input_args.thread
    apks = listdir(input_path)
    apks.sort()
    use_threadpool = True
    if use_threadpool:
        if input_args.debug:
            multiprocessing.log_to_stderr()
        with multiprocessing.Pool(thread) as p:
            with tqdm(total=len(apks), desc="processing behaviors") as pbar:
                def print_err(err):
                    print(f"error: {err}")


                def update(_):
                    pbar.update()


                for apk in apks:
                    if input_args.debug:
                        p.apply_async(LogExceptions(handle_apk), (input_path, apk,
                                                                  input_args.rewrite_behavior,
                                                                  input_args.save_behavior,
                                                                  input_args.rewrite_reduce,
                                                                  input_args.hop),
                                      callback=update, error_callback=print_err)
                    else:
                        p.apply_async(handle_apk, (input_path, apk,
                                                   input_args.rewrite_behavior,
                                                   input_args.save_behavior,
                                                   input_args.rewrite_reduce, input_args.hop),
                                      callback=update, error_callback=print_err)

                p.close()
                # noinspection PyTestUnpassedFixture
                p.join()
    else:
        with ThreadPoolExecutor(max_workers=thread) as executor:
            with tqdm(total=len(apks)) as pbar:
                futures = []
                args_list = [(input_path, apk,
                              input_args.rewrite_behavior, input_args.save_behavior,
                              input_args.rewrite_reduce, input_args.hop) for apk in apks]
                for _args in args_list:
                    partial_func = partial(wrapper_handle_apk, _args)
                    future = executor.submit(partial_func)
                    futures.append(future)
                for future in as_completed(futures):
                    _ = future.result()
                    pbar.update(1)
