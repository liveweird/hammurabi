// The authenticated file download (0.8.0, the ADR Markdown export): a GET through `authedFetch` (so the bearer token and the silent refresh
// apply - a plain `<a href>` could not send the header) whose body is read as a Blob. The browser is handed the file by `utils/saveFile.ts`.

import { ApiError, authedFetch, safeJson } from "./http";

/** A downloaded file: the bytes and the name the server gave it. */
export type Download = { blob: Blob; fileName: string };

/** The server only ever names a download with a slug (`ADR-0042-use-postgresql.md`): anything else in the header is not trusted as a file name. */
const SAFE_FILE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

/** The `filename` of a `Content-Disposition: attachment; filename="..."` header, or [fallback] when it is absent or not a plain name. */
export function fileNameFrom(disposition: string | null, fallback: string): string {
  const quoted = /filename="([^"]*)"/i.exec(disposition ?? "")?.[1];
  return quoted && SAFE_FILE_NAME.test(quoted) ? quoted : fallback;
}

/** GETs [path] as a file; a non-2xx answer throws an [ApiError] with its problem body, like every other wrapper. */
export async function downloadRequest(path: string, fallbackName: string): Promise<Download> {
  const res = await authedFetch(path);
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  return { blob: await res.blob(), fileName: fileNameFrom(res.headers.get("Content-Disposition"), fallbackName) };
}
