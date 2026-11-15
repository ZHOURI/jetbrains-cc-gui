/** Async data kinds backed by the Java `get_status_info` handler. */
export type LocalCommandDataKind = 'status' | 'doctor' | 'memory' | 'hooks';

/** Commands handled entirely inside the webview. */
export type LocalCommandId =
  | LocalCommandDataKind
  | 'help'
  | 'export'
  | 'config'
  | 'model'
  | 'permissions'
  | 'agents'
  | 'cost';

export interface LocalCommandRow {
  id: string;
  label: string;
  value?: string;
  /** Absolute path that can be opened in the editor. */
  path?: string;
  exists?: boolean;
  status?: 'ok' | 'warn' | 'error';
}

export interface LocalCommandSection {
  id: string;
  title?: string;
  rows: LocalCommandRow[];
}

export type LocalCommandAction =
  | { kind: 'openFile'; label: string; path: string }
  | { kind: 'openDependencySettings'; label: string };

export interface LocalCommandOutputBlock {
  type: 'local_command_output';
  command: string;
  state: 'loading' | 'ready' | 'error';
  title: string;
  subtitle?: string;
  sections: LocalCommandSection[];
  actions?: LocalCommandAction[];
  note?: string;
  error?: string;
}

export interface StatusInfoFileEntry {
  id: string;
  path: string;
  exists: boolean;
  hasHooks?: boolean;
}

export interface StatusInfoStatus {
  pluginVersion?: string;
  ideVersion?: string;
  nodePath?: string;
  nodeVersion?: string;
  provider?: string;
  model?: string;
  permissionMode?: string;
  sessionId?: string;
  cwd?: string;
  javaVersion?: string;
  os?: string;
  sdkId?: string;
  sdkInstalled?: boolean;
  sdkVersion?: string;
  sdkMinimumVersion?: string;
  sdkMeetsMinimum?: boolean;
}

export interface StatusInfoSdkEntry {
  id?: string;
  name?: string;
  installed?: boolean;
  version?: string;
  minimumVersion?: string;
  meetsMinimumVersion?: boolean;
}

export interface StatusInfoCliEntry {
  id: string;
  name?: string;
  installed?: boolean;
  version?: string;
  path?: string;
  error?: string;
}

export interface StatusInfoPayload {
  requestId?: string;
  kind?: LocalCommandDataKind;
  generatedAt?: number;
  provider?: string;
  error?: string;
  status?: StatusInfoStatus;
  diagnostics?: {
    nodeAvailable?: boolean;
    sdks?: Record<string, StatusInfoSdkEntry>;
    clis?: StatusInfoCliEntry[];
  };
  memoryFiles?: StatusInfoFileEntry[];
  hookFiles?: StatusInfoFileEntry[];
}
