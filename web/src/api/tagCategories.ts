// Tag-registry API (`/api/v1/tag-categories`, 0.4.0) — the ADMIN-curated categories of tags an ADR may carry. Any authenticated user
// reads them (the ADR tag editor's picker, the list's tag filter, the Tags page); ADMIN writes them.
// Thin endpoint wrappers: transport in ./http, types from ./schema.

import { jsonRequest, REVISION_HEADER, voidRequest } from "./http";
import type { components } from "./schema";

export type TagCategory = components["schemas"]["TagCategory"];
export type TagCategoryBody = components["schemas"]["TagCategoryRequest"];

export async function getTagCategories(): Promise<TagCategory[]> {
  return (await jsonRequest<components["schemas"]["TagCategoryList"]>("/api/v1/tag-categories")).items;
}

export async function createTagCategory(body: TagCategoryBody): Promise<TagCategory> {
  return jsonRequest<TagCategory>("/api/v1/tag-categories", { method: "POST", body: JSON.stringify(body) });
}

/** A full replace of the category under the revision the admin loaded it at (a stale one is the typed revision-conflict 409). */
export async function updateTagCategory(id: number, revision: number, body: TagCategoryBody): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, {
    method: "PUT",
    headers: { [REVISION_HEADER]: String(revision) },
    body: JSON.stringify(body),
  });
}

/** The soft delete, under the revision the admin saw when confirming it. */
export async function deleteTagCategory(id: number, revision: number): Promise<void> {
  await voidRequest(`/api/v1/tag-categories/${id}`, { method: "DELETE", headers: { [REVISION_HEADER]: String(revision) } });
}
