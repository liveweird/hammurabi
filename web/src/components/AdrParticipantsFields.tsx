import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import { takenIds, type ParticipantsState } from "../utils/adrParticipants";
import UserDirectorySelect from "./UserDirectorySelect";
import UserMultiPicker from "./UserMultiPicker";

/**
 * The DACI seats as pickers over the user directory: the Driver (editor only — on create the caller is the Driver), the single Approver,
 * and the Contributors and Informed lists. Nobody is offered twice: a person holds one role per ADR, so every pick leaves the other
 * pickers' options. `reserved` holds people who may not be offered at all (the caller on create: never their own extra participant).
 */
export default function AdrParticipantsFields({
  value,
  onChange,
  withDriver,
  reserved = [],
  driverError,
}: {
  value: ParticipantsState;
  onChange: (next: ParticipantsState) => void;
  withDriver: boolean;
  reserved?: readonly string[];
  driverError?: string;
}) {
  const { t } = useTranslation();
  const exclude = new Set([...takenIds(value), ...reserved]);
  return (
    <Stack gap="sm">
      {withDriver && (
        <UserDirectorySelect
          label={t("adrs.role.DRIVER")}
          description={t("adrs.daci.driverHint")}
          picked={value.driver}
          onPick={(driver) => onChange({ ...value, driver })}
          exclude={exclude}
          error={driverError}
        />
      )}
      <UserDirectorySelect
        label={t("adrs.role.APPROVER")}
        description={t("adrs.daci.approverHint")}
        picked={value.approver}
        onPick={(approver) => onChange({ ...value, approver })}
        exclude={exclude}
      />
      <UserMultiPicker
        label={t("adrs.roleGroup.CONTRIBUTOR")}
        description={t("adrs.daci.contributorHint")}
        picks={value.contributors}
        onChange={(contributors) => onChange({ ...value, contributors })}
        exclude={exclude}
      />
      <UserMultiPicker
        label={t("adrs.roleGroup.INFORMED")}
        description={t("adrs.daci.informedHint")}
        picks={value.informed}
        onChange={(informed) => onChange({ ...value, informed })}
        exclude={exclude}
      />
    </Stack>
  );
}
