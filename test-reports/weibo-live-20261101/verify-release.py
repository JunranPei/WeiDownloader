import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent
REPO = ROOT.parent.parent
ADB = r"D:\Android_studio_sdk\platform-tools\adb.exe"
device = [ADB, "-s", "emulator-5554"]
subprocess.run(device + ["install", "-r", str(REPO / "app/build/outputs/apk/release/app-x86_64-release.apk")], check=True)
package = subprocess.check_output(device + ["shell", "dumpsys", "package", "cn.weibo.filedownloader"], text=True)
assert "versionName=0.0.56" in package and "versionCode=10046 " in package
(ROOT / "installed-version.txt").write_text("0.0.56 / 10046\n", encoding="utf-8")
fixture = json.loads((REPO / "app/src/test/resources/weibo/5360196664164603-mobile.json").read_text(encoding="utf-8"))
options = {
    "scenario": "platform-download", "platform": "weibo",
    "url": "https://weibo.com/5886191812/5360196664164603",
    "expectedId": "5360196664164603", "exactMedia": "7",
    "expectedTypeOrder": "IMAGE,IMAGE,IMAGE,LIVE_PHOTO,IMAGE,LIVE_PHOTO,IMAGE",
    "expectedSourceIds": ",".join("picture:" + x for x in fixture["pic_ids"]),
    "mediaIndices": "0,1,2,3,4,5,6",
}
args = device + ["shell", "am", "instrument", "-w"]
for key, value in options.items():
    args += ["-e", key, value]
args += ["cn.weibo.filedownloader.test/cn.weibo.filedownloader.DownloadRefreshInstrumentation"]
result = subprocess.run(args, capture_output=True, text=True, timeout=300)
output = result.stdout + result.stderr
(ROOT / "release-download.log").write_text(output, encoding="utf-8")
print(output)
assert "PASS platform=weibo id=5360196664164603 media=7" in output
assert "cleanup=removed" in output and "FAIL" not in output
