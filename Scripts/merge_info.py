import logging
import os
from os.path import join, exists, getsize, dirname
import json
import sys
from xml.dom.minidom import Element, parseString
import pandas as pd
from pyexpat import ExpatError
import util
from tqdm import tqdm
import argparse
import multiprocessing


class InfoMerger:
    TYPE_USE = "use", 3
    TYPE_EVENT = "event", 4

    def __init__(self, log_level):
        self.work_dir = None
        self.encoding_dir = None
        self.edge_csv = None
        self.node_csv = None
        self.logger = util.get_logger(str(self.__class__), level=log_level)
        self.id2hash = dict()
        self.nodes = None
        self.calls = None
        self.edges = None
        self._data_loaded = False
        self._tpl_list = None

    def load_data(self, work_dir: str) -> bool:
        self.work_dir = work_dir
        self.encoding_dir = join(self.work_dir, "encoding")
        self._data_loaded = False
        if not os.path.isdir(self.work_dir):
            self.logger.debug(f"{self.work_dir} is not a dictionary")
            return False
        # load hash
        self.node_csv = join(self.encoding_dir, "node.csv")
        self.edge_csv = join(self.encoding_dir, "edge.csv")
        if not exists(self.node_csv):
            self.logger.debug(f"no node csv file found in {self.encoding_dir}")
            return False
        if not exists(self.edge_csv):
            self.logger.debug(f"no edge csv file found in {self.encoding_dir}")
            return False
        self.nodes = pd.read_csv(self.node_csv, index_col=0,
                                 dtype={"Package": str}, low_memory=False)
        self.edges = pd.read_csv(self.edge_csv, index_col=0)
        ui_with_id = self.nodes[self.nodes["UId"] != -1]

        for index, row in ui_with_id.iterrows():
            e_id = index
            ui_id = str(row["UId"]).strip().lower()
            try:
                ui_id = str(hex(int(ui_id)))[2:]
            except ValueError:
                continue
            # when app have multiple controls with the same id
            # the last one will be taken
            self.id2hash[ui_id] = e_id

        self.calls = self.nodes[self.nodes["UI"] == 0]
        self._data_loaded = True
        return True

    def add_edge(self, _update_edge: bool):
        u = self._add_use_links(_update_edge)
        e = self._add_event_edges(_update_edge)
        self.edges.to_csv(self.edge_csv, mode="w")
        return u, e

    def _add_use_links(self, _update_edge: bool) -> int:
        if not self._data_loaded:
            self.logger.debug("data is not successfully loaded")
            return -1
        if self._type_handled(self.TYPE_USE[0]):
            if not _update_edge:
                self.logger.debug(f"{self.TYPE_USE[0]} edges already found in {self.work_dir}")
                return 1
            else:
                self.edges.drop(self.edges[self.edges["Type"] == self.TYPE_USE[1]].index,
                                inplace=True)
        use_edges = []
        methods = set()
        with open(join(self.work_dir, "add_info.json"), mode="r") as f:
            info = json.load(f)["useEdges"]
            for id_hex in info:
                for method in info[id_hex]:
                    edge = (id_hex, method)
                    use_edges.append(edge)
                    methods.add(method)

        calls2id = dict()

        for method in methods:
            row = self.calls[self.calls["Name"] == method]
            m_hash = row["Hash"]
            if len(m_hash) > 0:
                calls2id[method] = m_hash.index.tolist()[0]

        new_num = 0
        for e in use_edges:
            id_hex, method = e
            id_hex = id_hex.lower()
            if id_hex not in self.id2hash:
                continue
            i_ui = self.id2hash[id_hex]
            if method not in calls2id:
                continue
            i_m = calls2id[method]
            self.edges.loc[len(self.edges)] = [i_ui, i_m, self.TYPE_USE[1]]
            new_num += 1

        self.logger.debug(f"{new_num} use edge added")
        self._write_result_mark(self.TYPE_USE[0], new_num)
        return new_num

    def _write_result_mark(self, link_type: str, num: int):
        with open(join(self.work_dir, "encoding", "add_link.txt"),
                  encoding="utf-8", mode="a+") as f:
            f.write(f"{link_type}: {num}\n")

    def _type_handled(self, link_type: str) -> bool:
        add_link_file = join(self.work_dir, "encoding", "add_link.txt")
        if not os.path.exists(add_link_file):
            return False
        with open(add_link_file, encoding="utf-8", mode="r") as f:
            return link_type in f.read()

    def _add_event_edges(self, _update_edge: bool) -> int:
        if not self._data_loaded:
            self.logger.debug("data is not successfully loaded")
            return -1
        if self._type_handled(self.TYPE_EVENT[0]):
            if not _update_edge:
                self.logger.debug(f"{self.TYPE_EVENT[0]} edges already found in {self.work_dir}")
                return 1
            else:
                self.edges.drop(self.edges[self.edges["Type"] == self.TYPE_EVENT[1]].index,
                                inplace=True)
        event_edges = []
        methods = set()
        event_data = self._load_event_data()
        if event_data is None:
            self.logger.debug("0 use edge added since event data is empty")
            self._write_result_mark(self.TYPE_EVENT[0], 0)
            return 0
        for _id in event_data.keys():
            ms = set(event_data[_id])
            for method in ms:
                _id_hex = hex(int(_id)).lower()[2:]
                edge = (_id_hex, method)
                event_edges.append(edge)
                methods.add(method)

        calls2id = dict()
        for method in methods:
            row = self.calls[self.calls["Name"] == method]
            m_hash = row["Hash"]
            if len(m_hash) > 0:
                calls2id[method] = m_hash.index.tolist()[0]

        new_num = 0
        for e in event_edges:
            id_hex, method = e
            if id_hex not in self.id2hash:
                # we only consider controls defined by app developer
                # those from lib or tpl are ignored
                continue
            i_ui = self.id2hash[id_hex]
            if method not in calls2id:
                continue
            i_m = calls2id[method]
            self.edges.loc[len(self.edges)] = [i_ui, i_m, self.TYPE_EVENT[1]]
            new_num += 1

        self.logger.debug(f"{new_num} event edge added")
        self._write_result_mark(self.TYPE_EVENT[0], new_num)
        return new_num

    def _load_event_data(self):
        event_file = join(self.work_dir, "event.xml")
        if not exists(event_file):
            return None
        event_file_maxsize = 40
        if getsize(event_file) > event_file_maxsize * 1024 * 1024:
            self.logger.debug(f"very large event.xml (>{event_file_maxsize} MB)")
            return None
        id2calls = {}

        def _rec(_node: Element, _id: str):
            if _node.hasAttribute("handler"):
                call = _node.getAttribute("handler")
                call = call.replace("&lt;", "<")
                call = call.replace("&gt;", ">")
                if _id not in id2calls:
                    id2calls[_id] = [call]
                else:
                    id2calls[_id].append(call)
            elif _node.hasAttribute("id"):
                _id = _node.getAttribute("id")
                for n in _node.childNodes:
                    _rec(n, _id)

        encoding = "utf-8"
        with open(event_file, mode="r", encoding=encoding) as f:
            try:
                dom_string = f.read()
            except UnicodeDecodeError:
                # 2012: 1013B7...18B6.apk
                return None
            if not dom_string.startswith("<GUI"):
                return None
            try:
                dom = parseString(dom_string)
            except ExpatError:
                return None
            util.xml_init(dom)
            hierarchy: Element = dom.getElementsByTagName("GUIHierarchy")[0]
            pages: list[Element] = hierarchy.childNodes
            for activity_dialog in pages:
                # first child is the root view group in a layout tree
                if activity_dialog.hasChildNodes():
                    for node in activity_dialog.childNodes:
                        _rec(node, "")

        return id2calls

    @property
    def tpl_list(self):
        if self._tpl_list is not None:
            return self._tpl_list
        results = []
        with open(join(dirname(__file__), "lists", "tpl_list.txt"),
                  encoding="utf-8", mode="r") as f:
            for line in f:
                if line.startswith("//"):
                    continue
                results.append(line.strip())
        self._tpl_list = results
        return self._tpl_list

    def update_package(self):
        # load tpl list
        tpl = []
        tpl_file = join(self.work_dir, "tpl.json")
        if exists(tpl_file):
            with open(tpl_file, mode="r") as f:
                json_obj = json.load(f)
                tpl = [j["Package"] for j in json_obj]
                tpl = [t[1:].replace('/', '.') for t in tpl]

        tpl.extend(self.tpl_list)

        # update package
        packages = []
        for call in self.calls.itertuples():
            name = getattr(call, "Name")
            is_native = getattr(call, "Android") + getattr(call, "Java")
            clazz = getattr(call, "Class")
            # only use one char to specific 'self' to save memory
            package = "-"
            # noinspection SpellCheckingInspection
            if is_native:
                # support packages, like
                # android.support.v4.media -> android.media
                # android.support.v7.app.AppCompatActivity -> android.app
                if clazz.startswith("android.support.v"):
                    package = f"android.{clazz.split('.')[3]}"
                elif clazz.startswith("android.support."):
                    package = f"android.{clazz.split('.')[2]}"
                elif clazz.startswith("com.android."):
                    # com.android.internal.telephony
                    package = f"android.{clazz.split('.')[2]}"
                else:
                    package = '.'.join(clazz.split('.')[:2])
            else:
                for _tpl in tpl:
                    if name[1:].startswith(_tpl):
                        package = _tpl
                        break
            packages.append(package)

        # package for ui nodes: - (same as self calls)
        packages.extend("-" * (len(self.nodes) - len(self.calls)))
        # if package exists, drop old ones
        if "Package" in self.nodes.columns:
            self.nodes = self.nodes.drop(columns="Package")

        self.nodes.insert(loc=len(self.nodes.columns),
                          column='Package', value=packages)
        self.nodes.to_csv(self.node_csv, mode="w")
        self.logger.debug(f"packages updated")


def parse_arg_merge(_args: list):
    parser = argparse.ArgumentParser(description="Merge UI-Code edges to the graph")
    parser.add_argument("path", help="path to input KGs' root", type=str)
    parser.add_argument("--thread", "-t", type=int, default=4,
                        help="threads to use (default: 4)")
    parser.add_argument("--update_package", "-up", action="store_true",
                        help="only update package info")
    parser.add_argument("--update_edge", "-ue", action="store_true",
                        help="update edges")
    parser.add_argument("--debug", "-d", action="store_true",
                        help="verbose output")
    return parser.parse_args(_args)


def merge(_debug: bool, _path: str,
          _folder: str, update_pkg_only: bool,
          update_edge: bool):
    level = logging.INFO
    if _debug:
        level = logging.DEBUG
    merger = InfoMerger(level)
    if merger.load_data(join(_path, _folder)):
        merger.update_package()
        if not update_pkg_only:
            return merger.add_edge(update_edge)
        else:
            return -2, -2
    return -1, -1


if __name__ == '__main__':
    args = parse_arg_merge(sys.argv[1:])
    path = args.path
    debug = args.debug
    folders = [i for i in os.listdir(path) if os.path.isdir(join(path, i))]
    p = multiprocessing.Pool(args.thread)
    with tqdm(total=len(folders), desc="merge edge") as pbar:
        def print_err(err): print(f"error: {err}")


        def update(v):
            num_use, num_event = v
            pbar.update()
            pbar.set_postfix_str(f"event:{num_event}")


        for folder in folders:
            p.apply_async(merge, (debug, path, folder,
                                  args.update_package, args.update_edge),
                          callback=update, error_callback=print_err)

        p.close()
        # noinspection PyTestUnpassedFixture
        p.join()
