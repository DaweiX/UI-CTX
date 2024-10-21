from subprocess import Popen
from os.path import join, dirname, abspath
from os import listdir
import sys
import argparse
from tqdm import tqdm
from time import time


def parse_arg_soot(input_args: list):
    parser = argparse.ArgumentParser(description="Run customized program analysis")
    parser.add_argument("apk_path", help="path that holds the apk files", type=str)
    parser.add_argument("out_path", help="output path", type=str)
    parser.add_argument("platform_path", help="Android SDK platform path", type=str)
    parser.add_argument("--xms", "-xs", type=str, default="1g",
                        help="xms for java vm. (default: 1g)")
    parser.add_argument("--xmx", "-xx", type=str, default="40g",
                        help="xmx for java vm. (default: 40g)")
    parser.add_argument("--timeout", "-tt", type=int, default="10",
                        help="timeout in minutes for each app. (default: 10)")
    parser.add_argument("--force", "-f", type=bool, default=False,
                        help="overwrite existing data")
    parser.add_argument("--app_range", "-r", type=str, default="",
                        help="app range")
    parser.add_argument("--thread", "-t", type=int, default=8,
                        help="thread to use")
    _args = parser.parse_args(input_args)
    return _args


if __name__ == '__main__':
    args = parse_arg_soot(sys.argv[1:])
    apk_path = args.apk_path
    apks = listdir(apk_path)
    apks.sort()
    out_path = args.out_path
    thread = args.thread
    platform_path = args.platform_path

    if "," in args.app_range:
        s, e = args.app_range.split(',')
        s, e = int(s), int(e)
        apks = apks[s: e]

    work_path = dirname(dirname(dirname(abspath(__file__))))
    jar = join(work_path, "CodeAnalyzer", "target", 
               "CodeAnalyzer-1.0-SNAPSHOT-jar-with-dependencies.jar")
    
    with tqdm(total=len(apks)) as bar:
        for apk in apks:
            bar.set_description_str(apk)
            # noinspection SpellCheckingInspection
            cmd = ["java", f"-Xms{args.xms}", f"-Xmx{args.xmx}",
                "-XX:+UseConcMarkSweepGC",
                "-jar", jar,
                "-i", join(apk_path, apk),
                "-p", platform_path,
                "-o", join(out_path, apk[:-4]),
                "-t", str(thread)]
            if args.force:
                cmd.append("-f")
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