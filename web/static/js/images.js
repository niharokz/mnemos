// images.js — adding images to a note and looking at them. Uploads go to the
// server, which saves them in the note's attachments/ folder and answers with the
// markdown to insert. The lightbox shows any image full screen.

import { api } from "./api.js";
import { h, icon, toast } from "./ui.js";

export const IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp", "image/avif"];
const IMAGE_NAME = /\.(png|jpe?g|gif|webp|avif|bmp|svg)$/i;

/** Whether a vault path is an image the browser can show. */
export const isImagePath = (p) => IMAGE_NAME.test(p);

/** The image files among a FileList / DataTransfer's files. */
export function imageFiles(list) {
  return [...(list || [])].filter((f) => IMAGE_TYPES.includes(f.type));
}

/**
 * Uploads images for a note, one after the other (keeps phones responsive).
 * Resolves with the markdown for each image that was saved; failures are toasted.
 * @param {File[]} files
 * @param {string} notePath
 * @param {number} maxBytes the server's limit, checked here first to save the upload
 */
export async function uploadImages(files, notePath, maxBytes) {
  const out = [];
  for (const f of files) {
    if (maxBytes && f.size > maxBytes) {
      toast(`“${f.name}” is bigger than ${Math.round(maxBytes / 1048576)} MB`, true);
      continue;
    }
    try {
      const r = await api.attach(notePath, f);
      out.push(r.markdown);
    } catch (e) {
      if (e.status !== 401) toast(`Couldn't add “${f.name}”: ${e.message}`, true);
    }
  }
  if (out.length) toast(out.length === 1 ? "Image added" : `${out.length} images added`);
  return out;
}

/** Opens a file picker for images; resolves with the chosen files (maybe none). */
export function pickImages() {
  return new Promise((resolve) => {
    const input = h("input", { type: "file", accept: IMAGE_TYPES.join(","), multiple: true, hidden: true });
    input.addEventListener("change", () => { resolve([...input.files]); input.remove(); });
    input.addEventListener("cancel", () => { resolve([]); input.remove(); });
    document.body.append(input);
    input.click();
  });
}

let box;
/** Shows an image full screen; click, Esc or the button closes it. */
export function lightbox(src, alt = "") {
  if (!box) {
    box = h("dialog.lightbox", { "aria-label": "Image" });
    box.addEventListener("click", () => box.close());
    document.body.append(box);
  }
  const img = h("img", { src, alt });
  box.replaceChildren(
    img,
    h("div.lb-bar", { onclick: (e) => e.stopPropagation() },
      h("span.ellipsis", {}, alt || decodeURIComponent(src.split("/").pop().split("?")[0])),
      h("a.lb-btn", { href: src, target: "_blank", rel: "noopener", title: "Open the original", "aria-label": "Open the original" }, icon("raw")),
      h("button.ghost.icon.lb-btn", { onclick: () => box.close(), "aria-label": "Close" }, icon("x"))));
  // a tap on the image switches between fitting the screen and its real size
  img.addEventListener("click", (e) => { e.stopPropagation(); box.classList.toggle("actual"); });
  box.classList.remove("actual");
  box.showModal();
}
