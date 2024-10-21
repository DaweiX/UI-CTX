import os
import sys
import json
from tqdm import tqdm

TOOL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.dirname(TOOL_DIR))


def get_tpl(apk_path: str, output_path: str):
    from LibRadar.LiteRadar.literadar import LibRadarLite
    apks = [a for a in os.listdir(apk_path) if a.endswith(".apk")]
    with tqdm(total=len(apks), desc="get tpl") as bar:
        for apk in apks:
            out_json = os.path.join(output_path, apk.replace(".apk", ""), "tpl.json")
            if os.path.exists(out_json):
                bar.update()
                continue
            res = {}
            try:
                lrd = LibRadarLite(os.path.join(apk_path, apk))
                res = lrd.compare()
            except:
                pass
            with open(out_json, mode="w+") as f:
                json.dump(res, f, indent=2)
            bar.update()


if __name__ == "__main__":
    args = sys.argv[1:]
    get_tpl(args[0], args[1])