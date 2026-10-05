import "./compat";
import { failStartup, finishStartup } from "./startup-state";

const isAndroid =
  /Android/i.test(navigator.userAgent) ||
  new URLSearchParams(window.location.search).get("mobile") === "1";

document.documentElement.classList.add(isAndroid ? "android-app" : "desktop-app");

if (isAndroid) {
  void import("./mobile").catch(failStartup);
} else {
  void import("./desktop").then(finishStartup, failStartup);
}
