## Files in this folder

### Benchmark

+ [sdk](Benchmark/sdk): Opcodes for Android APIs in different Android SDK versions.

+ [bench.json](Benchmark/bench.json): A demo bench file, containing 2,000 samples of 10 categories. Each sample is in the `${TIME}-${APP_SHA256}-{UHG_INDEX}` format.

+ [app_data](Benchmark/app_data): Analyzed UI & code data for different apps appear in the bench file. The files (compressed) are avaliable on https://figshare.com/s/63cb18a08416108c7681. Each subfolder is named by the SHA256 value of an apk file. It contains:
  * values/public.xml: parsed contents in the Arsc table.
  * node.csv: all UI/code nodes of the app
  * edge.csv: all edges detected in the app
  * op_code.json: Opcodes for different method calls in the app
  * manifest.xml: app manifest
  * ui_context.json: detailed UI-layer information in the app, including layouts and widget attributes
  * uhg.json: the UHG list
  * add_info.json: additional code-layer semantics extracted by the customized static analysis. The condition and thread blocks is used in UHG pruning.

+ [api_permission.csv](api_permission.csv): The API-permission mapping used by prior works.

+ We also provide the sample embedding data, which can be loaded and used by `Scripts/cluster.py`.

### DemoApk
A demo apk for testing the workflow.