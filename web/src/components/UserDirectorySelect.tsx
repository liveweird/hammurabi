import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { searchUserDirectory } from "../api/users";
import SearchPicker, { type PickedOption } from "./SearchPicker";

/** The server's page cap for the directory (its own, lower than the shared 100) and the shortest term it accepts. */
const DIRECTORY_PAGE_SIZE = 20;
const DIRECTORY_MIN_TERM = 2;
const DIRECTORY_MAX_TERM = 100;

/**
 * A searchable, clearable picker over the user directory (`GET /users/directory`, open to every signed-in user — the ADMIN-only
 * users list is not). The directory REQUIRES a term (2 to 100 characters), so nothing is searched until the trimmed input has two characters and a
 * hint stands in below that. Options read "Name (email)": the email tells namesakes apart; the caller holds the pick.
 */
export default function UserDirectorySelect({
  label,
  description,
  picked,
  onPick,
  disabled,
  error,
  exclude,
}: {
  /** Replaces the default "User" field label where the control means something more specific. */
  label?: string;
  description?: string;
  picked: PickedOption | null;
  onPick: (picked: PickedOption | null) => void;
  disabled?: boolean;
  /** A field error (a validation or a server answer the form maps onto this control). */
  error?: ReactNode;
  /** User ids (as option values) not offered, e.g. the current roster. */
  exclude?: ReadonlySet<string>;
}) {
  const { t } = useTranslation();
  return (
    <SearchPicker
      label={label ?? t("common.field.user")}
      description={description}
      placeholder={t("common.picker.user.placeholder")}
      clearLabel={t("common.picker.user.clear")}
      noMatchLabel={t("common.picker.user.noMatch")}
      minTermLength={DIRECTORY_MIN_TERM}
      maxLength={DIRECTORY_MAX_TERM}
      exclude={exclude}
      minTermHint={t("common.picker.user.minChars", { count: DIRECTORY_MIN_TERM })}
      queryKey={["users", "directory"]}
      search={async (term) => {
        const page = await searchUserDirectory({ q: term, page: 1, pageSize: DIRECTORY_PAGE_SIZE, sort: "name" });
        return page.items.map((user) => ({ value: String(user.id), label: `${user.name} (${user.email})` }));
      }}
      picked={picked}
      onPick={onPick}
      disabled={disabled}
      error={error}
    />
  );
}
