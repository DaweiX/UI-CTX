### External Tools

This dir holds some (optional) external tools. For example, you may want to try Gator for widget-event association and LibRadar for third-party library detection.

#### 1. LibRadar
A tool for get third-party library (TPL) list for Android apks (original source: https://github.com/pkumza/LiteRadar). Based on the original version, we have made some adaption to make it easier to be used for large-scale app analysis. To get the adapted version, in this folder, run:
```commandline
git clone https://github.com/DaweiX/LibRadar.git
```
Note that we can also move to more recent tools to detect in-app third-party libraries.

#### 2. Gator (optional)
A tool for get event handlers of UI controls (original source: https://github.com/cce13st/Gator). Based on the original version, we have fixed some bugs and removed some unused components. To get the adapted version, in this folder, run: 
```commandline
git clone https://github.com/DaweiX/GatorLite.git
```