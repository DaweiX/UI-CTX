import argparse
import os
import sys
import xml.dom.minidom
from os import listdir, remove
from os.path import exists, join, getsize, isdir
from subprocess import Popen
from time import time
import platform
from colorama import Fore, Back, Style, init

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.dirname(SCRIPT_DIR))


def parse_arg_run_gator(input_args: list):
    default_gator_path = join(__file__, "..", "GatorLite")
    parser = argparse.ArgumentParser(description="drive util tools for code analysis")
    parser.add_argument("input_path", help="path to input apps", type=str)
    parser.add_argument("output_path", help="path to output app knowledge",
                        type=str, default="")
    parser.add_argument("--gator", "-g", type=str,
                        help="path to gator root", default=default_gator_path)
    parser.add_argument("--sdk", "-s", type=str,
                        help="path to android sdk folder")
    parser.add_argument("--xms", "-xs", type=str, default="2g",
                        help="xms for java vm. (default: 2g)")
    parser.add_argument("--xmx", "-xx", type=str, default="20g",
                        help="xmx for java vm. (default: 20g)")
    parser.add_argument("--timeout", "-tt", type=int, default="10",
                        help="timeout in minutes for each app. (default: 10)")
    parser.add_argument("--worker", "-w", type=int, default="16",
                        help="cpu workers to run gator. (default: 16)")
    parser.add_argument("--range", "-r", type=str, default="")
    _args = parser.parse_args(input_args)
    return _args


class DriverGator:
    def __init__(self, args):
        from Scripts import util
        self.logger = util.get_logger(str(self.__class__))
        self.args = args
        self.input_dir = args.input_path
        self.output_dir = args.output_path
        print("output dir:", self.output_dir)

    def run(self):
        jars = [j for j in listdir(join(self.args.gator, "lib")) if j.endswith(".jar")]
        jars = [join(self.args.gator, "lib", j) for j in jars]
        jars.append(join(self.args.gator, "out", "production", "GatorLite"))

        sep = ";" if platform.system() == "Windows" else ":"
        jars_string = sep.join(jars)
        apks_data = [a for a in listdir(self.output_dir) if isdir(join(self.output_dir, a))]
        if ',' in self.args.range:
            s, e = self.args.range.split(',')
            apks_data = apks_data[int(s): int(e)]
        for i, apk_data in enumerate(apks_data):
            if i > 0:
                # placeholder for the last failure
                last_xml = join(self.output_dir, apks_data[i - 1], "event.xml")
                if not exists(last_xml):
                    with open(last_xml, mode="w+") as f:
                        f.write("-" * 8 + "error" + "-" * 8)
            self.logger.info(Fore.WHITE + Back.GREEN + Style.BRIGHT + 
                             "-" * 8 + f"{i + 1}/{len(apks_data)}: " + apk_data + "-" * 8)
            event_file = join(self.output_dir, apk_data, "event.xml")
            if exists(event_file):
                if getsize(event_file) > 16:
                    self.logger.info("non-empty event file exists, skip")
                    continue
                remove(event_file)
                self.logger.info("remove existing empty event file")

            # load api info
            soot_log = join(self.output_dir, apk_data, "code.log")
            if exists(soot_log):
                with open(soot_log, encoding="utf-8", mode="r") as f:
                    api = ""
                    for line in f:
                        if "API ver:" not in line:
                            continue
                        api = line.split("ver: ")[1].split(' ')[0]
                    if api == "":
                        continue
            else:
                manifest = join(self.output_dir, apk_data, "manifest.xml")
                if not exists(manifest):
                    self.logger.warning("no manifest file found")
                    continue
                with open(manifest, mode="r", encoding="utf-8") as f:
                    doc = xml.dom.minidom.parse(f)
                    m = doc.getElementsByTagName("manifest")[0]
                    s = m.getElementsByTagName("uses-sdk")[0]
                    api = s.getAttribute("targetSdkVersion")

            api_level = f"android-{api}"
            bench_name = apk_data + ".apk"
            out_root = join(self.output_dir, apk_data)
            apk = join(self.input_dir, bench_name)
            # noinspection SpellCheckingInspection
            cmd = ["java", f"-Xms{self.args.xms}", f"-Xmx{self.args.xmx}",
                   "-XX:+UseConcMarkSweepGC",
                   "-classpath", jars_string,
                   "presto.android.Main",
                   "-apiLevel", api_level,
                   "-android", join(self.args.sdk, 'platforms', api_level, 'android.jar'),
                   "-sdkDir", self.args.sdk,
                   "-listenerSpecFile", join(self.args.gator, 'listeners.xml'),
                   "-wtgSpecFile", join(self.args.gator, 'wtg.xml'),
                   "-project", apk,
                   "-classFiles", apk,
                   "-resourcePath", out_root,
                   "-manifestFile", join(str(out_root), 'manifest.xml'),
                   "-benchmarkName", bench_name,
                   "-out", self.output_dir,
                   "-gatorRoot", self.args.gator,
                   "-worker", str(self.args.worker)
                   ]
            
            ran = Popen(cmd)
            start = time()
            while 1:
                check = Popen.poll(ran)
                if (time() - start) >= 60 * self.args.timeout:
                    self.logger.warning(Back.RED + Fore.WHITE +
                                        f"time out triggered ({self.args.timeout} min)")
                    with open(event_file, mode="w+") as f:
                        f.write("-" * 8 + f"timeout (> {self.args.timeout} min)" + "-" * 8)
                    try:
                        ran.kill()
                        ran.terminate()
                        ran.wait()
                    finally:
                        break
                if check is not None:  # check if process is still running
                    break


if __name__ == '__main__':
    init(autoreset=True)
    driver_gator = DriverGator(parse_arg_run_gator(sys.argv[1:]))
    driver_gator.run()
