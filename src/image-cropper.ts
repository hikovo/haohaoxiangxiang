export type CropKind = "avatar" | "background" | "keepsake" | "note";

export async function cropImage(file: File, kind: CropKind): Promise<string | null> {
  const objectUrl = URL.createObjectURL(file);
  const source = new Image();
  source.src = objectUrl;
  try {
    await source.decode();
  } catch {
    URL.revokeObjectURL(objectUrl);
    throw new Error("图片读取失败");
  }

  const dialog = document.createElement("dialog");
  dialog.className = `image-cropper ${kind}`;
  dialog.innerHTML = `
    <div class="cropper-heading"><div><small>${kind === "avatar" ? "头像" : kind === "keepsake" ? "纪念照片" : kind === "note" ? "便签图片" : "聊天背景"}</small><h2>裁剪与调整位置</h2></div><button type="button" data-crop="cancel" aria-label="关闭">×</button></div>
    ${kind === "note" ? '<div class="cropper-ratios" aria-label="裁切比例"><button type="button" data-ratio="original" aria-pressed="true">原比例</button><button type="button" data-ratio="1">1:1</button><button type="button" data-ratio="1.333333">4:3</button><button type="button" data-ratio="0.75">3:4</button><button type="button" data-ratio="1.777778">16:9</button></div>' : ''}
    <div class="cropper-viewport"><img alt="裁剪预览" draggable="false" /></div>
    <p class="cropper-help">拖动图片调整位置，使用滑块放大或缩小。</p>
    <label class="cropper-zoom"><span>缩放</span><input type="range" min="1" max="3" step="0.01" value="1" /></label>
    <div class="cropper-actions"><button type="button" data-crop="reset">居中</button><button type="button" data-crop="confirm">使用图片</button></div>`;
  document.querySelector("#mobile-app")!.append(dialog);

  const viewport = dialog.querySelector<HTMLElement>(".cropper-viewport")!;
  const preview = dialog.querySelector<HTMLImageElement>("img")!;
  const slider = dialog.querySelector<HTMLInputElement>("input[type=range]")!;
  preview.src = objectUrl;
  let zoom = 1;
  let offsetX = 0;
  let offsetY = 0;
  let baseScale = 1;
  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  let aspect = Math.max(0.4, Math.min(2.5, source.naturalWidth / source.naturalHeight));

  const dimensions = () => ({ width: viewport.clientWidth, height: viewport.clientHeight });
  const clampOffset = () => {
    const { width, height } = dimensions();
    const renderedWidth = source.naturalWidth * baseScale * zoom;
    const renderedHeight = source.naturalHeight * baseScale * zoom;
    offsetX = Math.max((width - renderedWidth) / 2, Math.min((renderedWidth - width) / 2, offsetX));
    offsetY = Math.max((height - renderedHeight) / 2, Math.min((renderedHeight - height) / 2, offsetY));
  };
  const render = () => {
    const { width, height } = dimensions();
    baseScale = Math.max(width / source.naturalWidth, height / source.naturalHeight);
    clampOffset();
    preview.style.width = `${source.naturalWidth * baseScale * zoom}px`;
    preview.style.height = `${source.naturalHeight * baseScale * zoom}px`;
    preview.style.transform = `translate(calc(-50% + ${offsetX}px), calc(-50% + ${offsetY}px))`;
  };

  const result = new Promise<string | null>((resolve) => {
    let settled = false;
    const finish = (value: string | null) => {
      if (settled) return;
      settled = true;
      dialog.close();
      dialog.remove();
      URL.revokeObjectURL(objectUrl);
      resolve(value);
    };
    dialog.addEventListener("cancel", (event) => { event.preventDefault(); finish(null); });
    dialog.querySelector('[data-crop="cancel"]')!.addEventListener("click", () => finish(null));
    dialog.querySelector('[data-crop="reset"]')!.addEventListener("click", () => {
      zoom = 1; offsetX = 0; offsetY = 0; slider.value = "1"; render();
    });
    dialog.querySelector('[data-crop="confirm"]')!.addEventListener("click", () => {
      const { width } = dimensions();
      const outputWidth = kind === "note" ? Math.round(1600 * Math.min(1, aspect)) : kind === "avatar" ? 512 : 900;
      const outputHeight = kind === "note" ? Math.round(outputWidth / aspect) : kind === "avatar" ? 512 : kind === "keepsake" ? 700 : 1600;
      const factor = outputWidth / width;
      const canvas = document.createElement("canvas");
      canvas.width = outputWidth;
      canvas.height = outputHeight;
      const context = canvas.getContext("2d")!;
      context.fillStyle = "#FFF5F6";
      context.fillRect(0, 0, outputWidth, outputHeight);
      const drawWidth = source.naturalWidth * baseScale * zoom * factor;
      const drawHeight = source.naturalHeight * baseScale * zoom * factor;
      context.drawImage(
        source,
        outputWidth / 2 - drawWidth / 2 + offsetX * factor,
        outputHeight / 2 - drawHeight / 2 + offsetY * factor,
        drawWidth,
        drawHeight,
      );
      finish(canvas.toDataURL("image/webp", kind === "avatar" ? .9 : .86));
    });
  });

  slider.addEventListener("input", () => { zoom = Number(slider.value); render(); });
  viewport.addEventListener("pointerdown", (event) => {
    dragging = true;
    lastX = event.clientX;
    lastY = event.clientY;
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener("pointermove", (event) => {
    if (!dragging || !viewport.hasPointerCapture(event.pointerId)) return;
    offsetX += event.clientX - lastX;
    offsetY += event.clientY - lastY;
    lastX = event.clientX;
    lastY = event.clientY;
    render();
  });
  const endDrag = (event: PointerEvent) => {
    dragging = false;
    if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
  };
  viewport.addEventListener("pointerup", endDrag);
  viewport.addEventListener("pointercancel", endDrag);

  dialog.showModal();
  const resizeNoteViewport = () => {
    viewport.style.aspectRatio = String(aspect);
    viewport.style.width = `${Math.min(dialog.clientWidth - 40, window.innerHeight * 0.38 * aspect)}px`;
    viewport.style.height = "auto";
    viewport.style.marginInline = "auto";
    zoom = 1; offsetX = 0; offsetY = 0; slider.value = "1";
    render();
  };
  if (kind === "note") {
    resizeNoteViewport();
    dialog.querySelectorAll<HTMLButtonElement>("[data-ratio]").forEach(button => button.addEventListener("click", () => {
      aspect = button.dataset.ratio === "original" ? Math.max(0.4, Math.min(2.5, source.naturalWidth / source.naturalHeight)) : Number(button.dataset.ratio);
      dialog.querySelectorAll("[data-ratio]").forEach(item => item.setAttribute("aria-pressed", String(item === button)));
      resizeNoteViewport();
    }));
  }
  requestAnimationFrame(render);
  return result;
}
