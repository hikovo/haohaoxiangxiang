# THIS FILE IS AUTO-GENERATED. DO NOT MODIFY!!

# Copyright 2020-2023 Tauri Programme within The Commons Conservancy
# SPDX-License-Identifier: Apache-2.0
# SPDX-License-Identifier: MIT

-keep class com.hikovo.sanhaotu.* {
  native <methods>;
}

-keep class com.hikovo.sanhaotu.WryActivity {
  public <init>(...);

  void setWebView(com.hikovo.sanhaotu.RustWebView);
  java.lang.Class getAppClass(...);
  int getId();
  java.lang.String getVersion();
  int startActivity(...);
}

-keep class com.hikovo.sanhaotu.Ipc {
  public <init>(...);

  @android.webkit.JavascriptInterface public <methods>;
}

-keep class com.hikovo.sanhaotu.RustWebView {
  public <init>(...);

  void loadUrlMainThread(...);
  void loadHTMLMainThread(...);
  void evalScript(...);
}

-keep class com.hikovo.sanhaotu.RustWebChromeClient,com.hikovo.sanhaotu.RustWebViewClient {
  public <init>(...);
}
