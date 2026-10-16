/*
 * Firestore documents as the dashboard sees them (docs/API.md). Timestamps are
 * converted to epoch milliseconds by the data layer so screens never touch SDK types.
 */
import type { AppCategory, DeviceConfig, EventType, Severity } from './model';

export type Millis = number;

export type Plan = 'free' | 'family';

export interface Family {
  id: string;
  name: string;
  plan: Plan;
  ownerUid: string;
  memberUids: string[];
  createdAt: Millis | null;
}

export interface Member {
  uid: string;
  role: 'owner' | 'helper';
  displayName: string;
  email: string;
  photoUrl: string | null;
  phone: string;
  pushTokens: string[];
  notify: { urgent: 'push'; notice: 'push' | 'off' };
  /** Helpers other than the family's creator wait for the parent's approval on the phone. */
  status: 'pending' | 'active';
  joinedAt: Millis | null;
}

export interface InstalledApp {
  packageName: string;
  label: string;
}

export interface DeviceStatus {
  /** null until the phone first reports. */
  protected: boolean | null;
  missing: string[];
  lastHeartbeatAt: Millis | null;
  lastSeenAt: Millis | null;
}

export interface Device {
  id: string;
  parentName: string;
  deviceUid: string | null;
  model: string;
  androidVersion: string;
  appVersion: string;
  status: DeviceStatus | null;
  installedApps: InstalledApp[];
  checkIn: { enabled: boolean; deadline: string; timezone: string } | null;
  consent: { version: number; acceptedAt: Millis | null; features: Record<string, boolean> } | null;
  createdAt: Millis | null;
}

export interface UpdatedBy {
  uid: string;
  name: string;
  kind: 'helper' | 'device';
}

export interface ConfigDoc {
  config: DeviceConfig;
  updatedBy: UpdatedBy | null;
  updatedAt: Millis | null;
  exists: boolean;
}

export interface EventDoc {
  id: string;
  type: EventType;
  severity: Severity;
  /** Phone clock, ms. */
  at: Millis;
  data: Record<string, string>;
  createdAt: Millis | null;
  source: 'device' | 'server';
  deliveredTo: string[];
}

export interface PhotoDoc {
  id: string;
  storagePath: string;
  fromUid: string;
  fromName: string;
  caption: string;
  createdAt: Millis | null;
}

export interface ChangeDoc {
  id: string;
  byUid: string;
  byName: string;
  summary: string;
  at: Millis | null;
}

export interface Invite {
  code: string;
  expiresAt: Millis;
}

export type { AppCategory };
