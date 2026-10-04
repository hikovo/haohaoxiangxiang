const isAndroid =
  /Android/i.test(navigator.userAgent) ||
  new URLSearchParams(window.location.search).get("mobile") === "1";

document.documentElement.classList.add(isAndroid ? "android-app" : "desktop-app");

if (isAndroid) {
  void import("./mobile");
} else {
  document.querySelector('#app-launch-screen')?.remove();
  void import("./desktop");
}
