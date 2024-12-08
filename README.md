## UI-CTX

### 1. Get UI-layer knowledge
Run main of ApkParser with parameters:
```
-i ./Data/DemoApk/demo.apk -o ./Data/DemoApk/demo
```

Detailed usage and help:
```
usage: Main [-h] [-i <arg>] [-if <arg>] [-o <arg>] [-of <arg>] [-r]
Parse and dump app resources
 -h,--help                   Print this help info and exit
 -i,--input <arg>            input apk path
 -if,--input_folder <arg>    input apk folder path
 -o,--output <arg>           output path
 -of,--output_folder <arg>   output folder path
 -r,--rewrite                rewrite existing data
```

### 2. Get code-layer knowledge
Run main of CodeAnalyzer with parameters:
```
-p <path-to-android-platforms> -i ./Data/DemoApk/demo.apk -o ./Data/DemoApk/demo
```

Detailed usage and help:
```
usage: Main [-a <arg>] [-f] [-h] [-i <arg>] [-j] [-jj] [-l <arg>] [-nl]
       [-ns] [-nu] [-o <arg>] [-op <arg>] [-p <arg>] [-sf] [-t <arg>]
       [-tcb <arg>] [-tdf <arg>] [-tpr <arg>]
Build KG for apps or summarize behaviors
 -a,--cg_algo <arg>              Algo used for building cg (cha | spark)
 -f,--force_write                Force write results even when they exist
 -h,--help                       Print this help info and exit
 -i,--input <arg>                Input root path for apks
 -j,--jimple                     Print jimple codes for app codes
 -jj,--all_jimple                Print all jimple codes
 -l,--log_level <arg>            Logging level, default: info
 -nl,--no_log                    Do not write log to file
 -ns,--no_code_str               not save in-code str json
 -nu,--no_ui                     Exclude ui graph
 -o,--output <arg>               Output root path
 -op,--op_path <arg>             Path to save all op codes
 -p,--platforms <arg>            Android platform jars
 -sf,--save control flow         Save control flow information
 -t,--soot_thread <arg>          Threads used for soot
 -tcb,--timeout_callback <arg>   Time out for soot calculate callback
                                 (unit: min, default: 0 - no limit)
 -tdf,--timeout_dataflow <arg>   Time out for soot track dataflow (unit:
                                 min, default: 0 - no limit)
 -tpr,--timeout_path <arg>       Time out for soot reconstruct path (unit:
                                 min, default: 0 - no limit)
```

Note: Android platform files are available on https://github.com/Sable/android-platforms.

### 3. (Optional) Identify TPLs used in app
In the root folder of UI-Code, run:
```
python ./Tools/driver_radar.py ./Data/DemoApk/ ./Data/DemoApk/
```

### 4. Search for behaviors

First, we add additional info to nodes and graph by running:

```
python ./Scripts/merge_info.py ./Data/DemoApk
```

This will load event links to the graph, and parse call information (e.g., package name).

Detailed usage and help:
```
usage: merge_info.py [-h] [--thread THREAD] [--update_package] [--update_edge] [--debug] path

Merge UI-Code edges to the graph

positional arguments:
  path                  path to input KGs' root

options:
  -h, --help            show this help message and exit
  --thread THREAD, -t THREAD
                        threads to use (default: 4)
  --update_package, -up
                        only update package info
  --update_edge, -ue    update edges
  --debug, -d           verbose output
```

Then, we extract UI handler graphs (UHGs) by running:

``` 
python  ./Scripts/extract_uhg.py ./Data/DemoApk/ -t 16
```

Detailed usage and help:
```
usage: extract_uhg.py [-h] [--rewrite_behavior] [--save_behavior] [--rewrite_reduce] [--hop HOP] [--thread THREAD] [--debug] path

Generate sub-graphs from the whole KG, and conduct reduction

positional arguments:
  path                  path to input KGs' root

options:
  -h, --help            show this help message and exit
  --rewrite_behavior, -rb
                        rewrite uhg_full.json
  --save_behavior, -sb  save full uhg
  --rewrite_reduce, -rr
                        rewrite uhg.json
  --hop HOP, -hp HOP    hop for reduction (default: 0)
  --thread THREAD, -t THREAD
                        threads to use (default: 4)
  --debug, -d           verbose output (e.g., time cost)
```

Driver scripts for large-scale analysis can be found in `Scripts/driver`.

We also provide the parsed app data for 10K+ apps on https://figshare.com/articles/dataset/UICTX_Demo_Data/27266934?file=49891650.
