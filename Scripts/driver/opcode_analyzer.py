from subprocess import Popen
from os.path import exists, join, dirname, abspath
import sys
import argparse
from os import listdir
from tqdm import tqdm
from time import time


def parse_arg_soot(input_args: list):
    parser = argparse.ArgumentParser(description="main program for program analysis")
    parser.add_argument("apk_path", help="path that holds the apk files", type=str)
    parser.add_argument("out_path", help="output path", type=str)
    parser.add_argument("op_path", help="path for saving opcodes", type=str)
    parser.add_argument("platform_path", help="Android SDK platform path", type=str)
    parser.add_argument("--xms", "-xs", type=str, default="1g",
                        help="xms for java vm. (default: 1g)")
    parser.add_argument("--xmx", "-xx", type=str, default="40g",
                        help="xmx for java vm. (default: 40g)")
    parser.add_argument("--timeout", "-tt", type=int, default="10",
                        help="timeout in minutes for each app. (default: 10)")
    parser.add_argument("--app_range", "-r", type=str, default="",
                        help="app range")
    parser.add_argument("--thread", "-t", type=int, default=8,
                        help="thread to use")
    _args = parser.parse_args(input_args)
    return _args


if __name__ == '__main__':
    args = parse_arg_soot(sys.argv[1:])
    thread = args.thread
    apk_path = args.apk_path
    apks = listdir(apk_path)
    apks.sort()
    out_path = args.out_path
    platform_path = args.platform_path
    op_path = args.op_path

    work_path = dirname(dirname(dirname(abspath(__file__))))
    jar = join(work_path, "CodeAnalyzer", "target", 
               "CodeAnalyzer-1.0-SNAPSHOT-jar-with-dependencies.jar")

    if "," in args.app_range:
        s, e = args.app_range.split(',')
        s, e = int(s), int(e)
        apks = apks[s: e]

    apks.sort()
    
    with tqdm(total=len(apks), desc="parse opcode") as bar:
        for apk in apks:
            apk_short = apk[-64:-56]
            bar.set_postfix_str(apk_short)
            out_file = join(op_path, apk[-68:-4] + ".json")
            if exists(out_file):
                bar.set_postfix_str("skip")
                bar.update()
                continue

            # noinspection SpellCheckingInspection
            cmd = ["java", f"-Xms{args.xms}", f"-Xmx{args.xmx}",
                "-XX:+UseConcMarkSweepGC",
                "-jar", jar,
                "-i", join(apk_path, apk),
                "-p", platform_path,
                "-o", out_path,
                "-t", str(thread), "-op", op_path,
                "-nl", "-f"]
            ran = Popen(cmd)
            start = time()
            while 1:
                check = Popen.poll(ran)
                ctime = time()
                if (ctime - start) >= 60 * args.timeout:
                    print(f"time out triggered ({args.timeout} min)")
                    try:
                        ran.kill()
                        ran.terminate()
                        ran.wait()
                    finally:
                        break
                if check is not None:  # check if process is still running
                    break
            bar.update()