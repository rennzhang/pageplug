import type { AdminConfigType } from "@appsmith/pages/AdminSettings/config/types";
import {
  CategoryType,
  SettingCategories,
  SettingTypes,
} from "@appsmith/pages/AdminSettings/config/types";
import { AuditLogsUpgradePage } from "../../Upgrade/AuditLogsUpgradePage";

export const config: AdminConfigType = {
  icon: "file-list-2-line",
  type: SettingCategories.AUDIT_LOGS,
  categoryType: CategoryType.OTHER,
  controlType: SettingTypes.PAGE,
  component: AuditLogsUpgradePage,
  title: "审计日志",
  canSave: false,
  needsUpgrade: true,
} as AdminConfigType;
