import json
import logging
from os.path import join
from typing import List
from xml.dom.minidom import Element, parseString, Document


def get_logger(name: str, stream_handler: bool = False,
               level: int = logging.INFO) -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(level)
    if stream_handler:
        logger.addHandler(logging.StreamHandler())
    # noinspection SpellCheckingInspection
    logging.basicConfig(format='%(asctime)s %(levelname)s %(message)s')
    return logger


def xml_init(node):
    if node.childNodes:
        for child in node.childNodes[:]:
            if child.nodeType == child.TEXT_NODE or \
                    child.nodeType == child.COMMENT_NODE:
                node.removeChild(child)
                continue
            xml_init(child)


def data_flow_xml2json(work_path: str):
    def _get_fun_name_parameter(i: str) -> str:
        _s, _e = i.index('<'), i.rindex('>(')
        _name, para = i[_s: _e + 1], i[_e + 2: -1]
        return f"{_name}=={para}" if len(para) else _name

    with open(join(work_path, "data_flow.xml"), mode="r", encoding="utf-8") as f:
        xml_text = f.read()
        dom: Document = parseString(xml_text)
        xml_init(dom)
        root: Element = dom.getElementsByTagName("DataFlowResults")[0]
        results: Element = root.getElementsByTagName("Results")[0]
        results: List[Element] = results.childNodes

        json_object = {}
        json_results = []
        for result in results:
            sink_element: Element = result.getElementsByTagName("Sink")[0]
            sink: str = sink_element.getAttribute("Statement")
            jo = {"sink": _get_fun_name_parameter(sink)}
            jo_sources = jo["sources"] = []
            sources_element: Element = result.getElementsByTagName("Sources")[0]
            for source_element in sources_element.childNodes:
                source: str = source_element.getAttribute("Statement")
                jo_source = {"source": _get_fun_name_parameter(source)}
                path_element: Element = source_element.getElementsByTagName("TaintPath")[0]
                path_nodes: List[Element] = path_element.childNodes
                path = [i.getAttribute("Statement") for i in path_nodes]
                path = [i for i in path if "invoke" in i]
                path = [_get_fun_name_parameter(i) for i in path]
                jo_source["path"] = path
                jo_sources.append(jo_source)

            json_results.append(jo)

        json_object["results"] = json_results
        perform: Element = root.getElementsByTagName("PerformanceData")[0]
        perform: List[Element] = perform.getElementsByTagName("PerformanceEntry")
        jo_cost = json_object["performance"] = {}
        for entry in perform:
            name = entry.getAttribute("Name")
            value = entry.getAttribute("Value")
            jo_cost[name] = value

    with open(join(work_path, "data_flow.json"), mode="w+", encoding="utf-8") as f:
        json_txt = json.dumps(json_object, indent=4)
        f.write(json_txt)
