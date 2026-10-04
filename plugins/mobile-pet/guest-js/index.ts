import { invoke } from '@tauri-apps/api/core'

export type OverlayStatus = 'active' | 'inactive' | 'permissionRequired' | 'unsupported'

export async function startOverlay(): Promise<OverlayStatus> {
  return (await invoke<{ status: OverlayStatus }>('plugin:mobile-pet|start_overlay')).status
}

export async function stopOverlay(): Promise<OverlayStatus> {
  return (await invoke<{ status: OverlayStatus }>('plugin:mobile-pet|stop_overlay')).status
}

export async function overlayStatus(): Promise<OverlayStatus> {
  return (await invoke<{ status: OverlayStatus }>('plugin:mobile-pet|overlay_status')).status
}
