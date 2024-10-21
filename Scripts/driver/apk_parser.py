from subprocess import Popen
from os.path import exists, join, dirname, abspath
from os import listdir
from tqdm import tqdm
from time import time
import sys
import argparse


def parse_arg(_args: list):
    parser = argparse.ArgumentParser(description="Run ApkParser on multiple apps")
    parser.add_argument("apk_path", help="path that holds the apk files", type=str)
    parser.add_argument("out_path", help="output path", type=str)
    parser.add_argument("--timeout", "-t", type=int, default=3,
                        help="timeout for each app (default: 3)")
    return parser.parse_args(_args)


if __name__ == '__main__':
    args = parse_arg(sys.argv[1:])
    timeout = args.timeout
    root = args.apk_path
    apks = listdir(root)
    apks.sort()
    out = args.out_path
    work_path = dirname(dirname(dirname(abspath(__file__))))
    jar = join(work_path, "ApkParser", "target", 
               "ApkParser-1.0-SNAPSHOT-jar-with-dependencies.jar")
    with tqdm(total=len(apks)) as bar:
        for apk in apks:
            bar.set_description_str(apk)
            apk_file = join(root, apk)
            if not exists(apk_file):
                print(f"{apk_file} not found!")
            cmd = ["java",
                "-jar", jar,
                "-i", apk_file,
                "-o", join(out, apk[:-4]), "-r"]
            ran = Popen(cmd)
            start = time()
            while 1:
                check = Popen.poll(ran)
                ctime = time()
                if (ctime - start) >= 60 * timeout:
                    print(f"time out triggered ({timeout} min)")
                    try:
                        ran.kill()
                        ran.terminate()
                        ran.wait()
                    finally:
                        break
                if check is not None:  # check if process is still running
                    break
            bar.update()
