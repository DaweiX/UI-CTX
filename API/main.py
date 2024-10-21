from os.path import join
import json
from apk import Apk
import sys


def get_entries(bench_file: str, category: str):
    with open(bench_file, "r") as f:
        obj = json.load(f)
    if category not in obj:
        raise ValueError(f"{category} not exists")
    return obj[category]


def parse_item(data_path: str, item: str):
    _, apk, index = item.split('-')
    index = int(index)
    apk_data = join(data_path, apk)
    apk = Apk(apk_data, apk)
    return apk, index


if __name__ == "__main__":
    data_path = sys.argv[1]
    bench_file = sys.argv[2]
    # a. select all UHGs that belongs to "refresh" category
    category = "logout"
    items = get_entries(bench_file, category)

    # b. select a UHG
    item = items[0]
    apk, index = parse_item(data_path, item)
    uhg = apk.uhgs[index]

    # c. now we can query some info

    # some basic info of the UHG
    print(f"apk: {uhg.apk}")
    print(f"# of node: {uhg.size["node"]}")
    print(f"# of edge: {uhg.size["edge"]}")

    # widget
    widget = uhg.widget     
    print(f"widget: {widget.name}")     # name of the widget
    print(f"  - hash: {widget.hash}")   # hash of the widget    
    print(f"  - xml: {widget.xml}")     # layout file name
    print(f"  - wid: {widget.wid}")     # widget id

    # event (code context entry)
    event = uhg.event
    print(f"Event: {event.name}")       # name of the event
    print(f"  - hash: {event.hash}")    # hash of the event    
    print(f"  - class: {event.klass}")  # class of the event

    # edge
    start, end = uhg.get_edge(1)       # here we take the 2nd edge as a demo
    print(f"The Last Edge")
    print(f"  - From: {start.name}") 
    print(f"  - To: {end.name}")

    # opcode for a method
    klass = list(apk.opcode.keys())[-6]
    print("Opcode")
    for method in apk.opcode[klass]:
        print(f"  - {method}: {apk.opcode[klass][method]}")