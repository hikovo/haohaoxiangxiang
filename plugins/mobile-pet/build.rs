const COMMANDS: &[&str] = &[
  "open_external",
  "haptic_tap",
  "start_overlay",
  "stop_overlay",
  "overlay_status",
  "daily_reminder_state",
  "save_daily_reminder",
  "mark_daily_prompt_shown",
  "answer_daily_reminder",
  "sync_memo_reminders",
  "reminder_style",
  "schedule_focus_reminder",
  "cancel_focus_reminder",
  "unified_chat",
  "unified_retract_messages",
  "unified_clear_conversation",
  "unified_memory_state",
  "unified_set_manual_memory",
  "unified_import_legacy",
];

fn main() {
  tauri_plugin::Builder::new(COMMANDS)
    .android_path("android")
    .ios_path("ios")
    .build();
}
