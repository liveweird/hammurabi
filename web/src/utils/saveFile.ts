import type { Download } from "../api/download";

/**
 * Hands a downloaded file to the browser's save flow: an object URL on a throwaway `<a download>`, clicked and released. The object URL
 * is revoked on the next tick (the click starts the save synchronously, but revoking inside it can cancel it in some browsers).
 */
export function saveFile({ blob, fileName }: Download): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName;
  link.style.display = "none";
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
