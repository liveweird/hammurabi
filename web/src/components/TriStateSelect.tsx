import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TriState } from "../utils/adrListParams";

/** An any / yes / no filter (the list's yes/no filters: no value is "any" and sends nothing, `false` narrows to the others). */
export default function TriStateSelect({ label, value, onChange }: { label: string; value: TriState; onChange: (value: TriState) => void }) {
  const { t } = useTranslation();
  return (
    <Select
      label={label}
      w={150}
      data={[
        { value: "any", label: t("adrs.filter.any") },
        { value: "true", label: t("adrs.filter.yes") },
        { value: "false", label: t("adrs.filter.no") },
      ]}
      value={value === null ? "any" : String(value)}
      onChange={(next) => onChange(next === "true" ? true : next === "false" ? false : null)}
      allowDeselect={false}
    />
  );
}
