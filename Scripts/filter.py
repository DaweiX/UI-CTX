"""filter behaviors according to api or ui context"""

from os.path import join, dirname
import pandas
import json

from pandas import DataFrame


class Singleton(object):
    def __init__(self, cls):
        self._cls = cls
        self._instance = {}

    def __call__(self):
        if self._cls not in self._instance:
            self._instance[self._cls] = self._cls()
        return self._instance[self._cls]


@Singleton
class Filter:
    def __init__(self):
        self.ui_nodes = None
        self.call_nodes = None
        self.ui_context = None
        self.texts: list = []
        self.key_methods: list = []
        self.empty_input_path = False
        self._load_list("texts")
        self._load_list("key_methods")

    def _load_list(self, name: str) -> None:
        results = []
        with open(join(dirname(__file__), "lists", f"{name}.txt"),
                  encoding="utf-8", mode="r") as f:
            for line in f:
                if line.startswith("//"):
                    continue
                results.append(line.strip())
        self.__setattr__(name, results)

    def load_data(self, input_path: str):
        # keep shared data for each apk instance to avoid repeated data loading
        if len(input_path) == 0:
            self.empty_input_path = True
            return
        context_file = join(input_path, "ui_context.json")
        with open(context_file, encoding="utf-8", mode="r") as f:
            self.ui_context = json.load(f)
        nodes = pandas.read_csv(
            join(input_path, "encoding", "node.csv"),
            usecols=['Name', 'Package', 'XML', 'UId'],
            dtype={"Package": str}, low_memory=False)
        self.call_nodes = nodes
        self.ui_nodes = nodes[nodes["UId"] != -1]
        # use ui label may be faster, but this does not work
        # self.ui_nodes = nodes[nodes["UI"] == 1]
        # self.call_nodes = nodes[nodes["UI"] == 0]

        # print(f"ui: {len(self.ui_nodes)}, call: {len(self.call_nodes)}")

    def should_keep_by_ui(self, ui_id) -> bool:
        # a filter without filter basis is ignored
        if self.empty_input_path:
            # for debug mode
            return True
        if len(self.texts) == 0:
            return True
        # text_re = re.compile("(?=text=)\\S+\\s")
        _ui_nodes: DataFrame = self.ui_nodes.loc[int(ui_id)]
        _ui_info = None
        xml = _ui_nodes["XML"]
        uid = _ui_nodes["UId"]
        try:
            _ui_info = self.ui_context[xml][uid]
        except KeyError:
            for x in self.ui_context:
                _x = self.ui_context[x]
                if uid in _x:
                    _ui_info = _x[uid]
                    break
        if _ui_info is None:
            # warning: no ui info found for this ui
            return False
        if "hint" in _ui_info:
            hint: str = _ui_info["hint"]
            if hint != "NOT_FOUND":
                if any(t in hint for t in self.texts):
                    return True
        if "text" in _ui_info:
            text: str = _ui_info["text"]
            if text != "NOT_FOUND":
                if any(t in text for t in self.texts):
                    return True
        return False

    def should_keep_by_method(self, call_ids: list) -> bool:
        # a filter without filter basis is ignored
        if len(self.key_methods) == 0:
            return True
        call_ids = [int(u) for u in call_ids]
        _call_nodes: DataFrame = self.call_nodes.loc[call_ids]
        # iter by id
        for i in _call_nodes.index:
            package = _call_nodes["Package"][i]
            if package == "-":
                continue
            name = _call_nodes["Name"][i].lower()
            if any(t.lower() in name for t in self.key_methods):
                return True
        return False
