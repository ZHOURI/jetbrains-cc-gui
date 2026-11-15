import type { TFunction } from 'i18next';
import type {
  LocalCommandDataKind,
  LocalCommandOutputBlock,
  LocalCommandRow,
  LocalCommandSection,
  StatusInfoFileEntry,
  StatusInfoPayload,
} from '../types/localCommand';

const TITLE_KEYS: Record<LocalCommandDataKind | 'help', string> = {
  status: 'chat.localCommand.status.title',
  doctor: 'chat.localCommand.doctor.title',
  memory: 'chat.localCommand.memory.title',
  hooks: 'chat.localCommand.hooks.title',
  help: 'chat.localCommand.help.title',
};

const SUBTITLE_KEYS: Record<LocalCommandDataKind | 'help', string> = {
  status: 'chat.localCommand.status.subtitle',
  doctor: 'chat.localCommand.doctor.subtitle',
  memory: 'chat.localCommand.memory.subtitle',
  hooks: 'chat.localCommand.hooks.subtitle',
  help: 'chat.localCommand.help.subtitle',
};

const MEMORY_LABEL_KEYS: Record<string, string> = {
  userMemory: 'chat.localCommand.memory.userMemory',
  projectMemory: 'chat.localCommand.memory.projectMemory',
  projectLocalMemory: 'chat.localCommand.memory.projectLocalMemory',
  codexUserMemory: 'chat.localCommand.memory.codexUserMemory',
  codexProjectMemory: 'chat.localCommand.memory.codexProjectMemory',
};

const HOOK_LABEL_KEYS: Record<string, string> = {
  userSettings: 'chat.localCommand.hooks.userSettings',
  projectSettings: 'chat.localCommand.hooks.projectSettings',
  projectLocalSettings: 'chat.localCommand.hooks.projectLocalSettings',
  codexConfig: 'chat.localCommand.hooks.codexConfig',
};

const HELP_ROWS: Array<{ command: string; key: string }> = [
  { command: '/status', key: 'chat.localCommand.help.status' },
  { command: '/doctor', key: 'chat.localCommand.help.doctor' },
  { command: '/memory', key: 'chat.localCommand.help.memory' },
  { command: '/hooks', key: 'chat.localCommand.help.hooks' },
  { command: '/export', key: 'chat.localCommand.help.export' },
  { command: '/config', key: 'chat.localCommand.help.config' },
  { command: '/model', key: 'chat.localCommand.help.model' },
  { command: '/permissions', key: 'chat.localCommand.help.permissions' },
  { command: '/agents', key: 'chat.localCommand.help.agents' },
  { command: '/cost', key: 'chat.localCommand.help.cost' },
];

function dash(value: unknown): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    return '—';
  }
  return value;
}

function localizedLabel(
  t: TFunction,
  keys: Record<string, string>,
  id: string,
  fallback: string,
): string {
  const key = keys[id];
  return key ? t(key) : fallback;
}

function buildFileRows(
  t: TFunction,
  files: StatusInfoFileEntry[],
  labels: Record<string, string>,
): LocalCommandRow[] {
  return files.map((file) => ({
    id: file.id,
    label: localizedLabel(t, labels, file.id, file.id),
    path: file.path,
    exists: file.exists,
    value: file.exists
      ? (file.hasHooks === undefined
        ? t('chat.localCommand.found')
        : file.hasHooks
          ? t('chat.localCommand.hooksConfigured')
          : t('chat.localCommand.hooksEmpty'))
      : t('chat.localCommand.notFound'),
    status: file.exists ? (file.hasHooks === false ? 'warn' : 'ok') : undefined,
  }));
}

export function localCommandTitle(command: LocalCommandDataKind | 'help', t: TFunction): string {
  return t(TITLE_KEYS[command]);
}

export function localCommandSubtitle(command: LocalCommandDataKind | 'help', t: TFunction): string {
  return t(SUBTITLE_KEYS[command]);
}

export function buildLoadingCard(
  command: LocalCommandDataKind | 'help',
  rawCommand: string,
  t: TFunction,
): LocalCommandOutputBlock {
  return {
    type: 'local_command_output',
    command: rawCommand,
    state: 'loading',
    title: localCommandTitle(command, t),
    subtitle: localCommandSubtitle(command, t),
    sections: [],
    note: t('chat.localCommand.loading'),
  };
}

export function buildErrorCard(
  command: LocalCommandDataKind | 'help',
  rawCommand: string,
  t: TFunction,
  error?: string,
): LocalCommandOutputBlock {
  return {
    type: 'local_command_output',
    command: rawCommand,
    state: 'error',
    title: localCommandTitle(command, t),
    subtitle: localCommandSubtitle(command, t),
    sections: [],
    error: error || t('chat.localCommand.failed'),
  };
}

export function buildStatusCard(payload: StatusInfoPayload, t: TFunction): LocalCommandOutputBlock {
  const status = payload.status;
  if (!status) {
    return buildErrorCard('status', '/status', t, payload.error);
  }
  const sdkValue = status.sdkInstalled
    ? dash(status.sdkVersion)
    : t('chat.localCommand.notInstalled');
  const sections: LocalCommandSection[] = [
    {
      id: 'environment',
      title: t('chat.localCommand.status.environment'),
      rows: [
        { id: 'pluginVersion', label: t('chat.localCommand.status.pluginVersion'), value: dash(status.pluginVersion) },
        { id: 'ideVersion', label: t('chat.localCommand.status.ideVersion'), value: dash(status.ideVersion) },
        { id: 'nodeVersion', label: t('chat.localCommand.status.nodeVersion'), value: dash(status.nodeVersion) },
        { id: 'sdkVersion', label: t('chat.localCommand.status.sdkVersion'), value: sdkValue },
        { id: 'javaVersion', label: t('chat.localCommand.status.javaVersion'), value: dash(status.javaVersion) },
        { id: 'os', label: t('chat.localCommand.status.os'), value: dash(status.os) },
      ],
    },
    {
      id: 'session',
      title: t('chat.localCommand.status.session'),
      rows: [
        { id: 'provider', label: t('chat.localCommand.status.provider'), value: dash(status.provider) },
        { id: 'model', label: t('chat.localCommand.status.model'), value: dash(status.model) },
        { id: 'permissionMode', label: t('chat.localCommand.status.permissionMode'), value: dash(status.permissionMode) },
        { id: 'sessionId', label: t('chat.localCommand.status.sessionId'), value: dash(status.sessionId) },
        { id: 'cwd', label: t('chat.localCommand.status.cwd'), value: dash(status.cwd) },
      ],
    },
  ];
  return {
    type: 'local_command_output',
    command: '/status',
    state: 'ready',
    title: localCommandTitle('status', t),
    subtitle: localCommandSubtitle('status', t),
    sections,
  };
}

export function buildDoctorCard(payload: StatusInfoPayload, t: TFunction): LocalCommandOutputBlock {
  if (payload.error && !payload.diagnostics) {
    return buildErrorCard('doctor', '/doctor', t, payload.error);
  }
  const diagnostics = payload.diagnostics;
  const nodeAvailable = diagnostics?.nodeAvailable === true;
  const status = payload.status;

  const nodeRows: LocalCommandRow[] = [
    {
      id: 'nodeAvailable',
      label: t('chat.localCommand.doctor.nodeAvailability'),
      value: nodeAvailable
        ? t('chat.localCommand.nodeAvailable')
        : t('chat.localCommand.nodeUnavailable'),
      status: nodeAvailable ? 'ok' : 'error',
    },
    { id: 'nodeVersion', label: t('chat.localCommand.doctor.nodeVersion'), value: dash(status?.nodeVersion) },
    { id: 'nodePath', label: t('chat.localCommand.doctor.nodePath'), value: dash(status?.nodePath) },
  ];

  const sdkRows: LocalCommandRow[] = [];
  const sdks = diagnostics?.sdks ?? {};
  Object.keys(sdks).forEach((id) => {
    const sdk = sdks[id];
    if (!sdk) return;
    const installed = sdk.installed === true;
    const meetsMinimum = sdk.meetsMinimumVersion !== false;
    sdkRows.push({
      id: `sdk-${id}`,
      label: sdk.name || id,
      value: installed
        ? (sdk.version ? `${sdk.version}${sdk.minimumVersion ? ` (min ${sdk.minimumVersion})` : ''}` : t('chat.localCommand.installed'))
        : t('chat.localCommand.notInstalled'),
      status: !installed ? 'error' : meetsMinimum ? 'ok' : 'warn',
    });
  });
  if (sdkRows.length === 0 && status?.sdkId) {
    sdkRows.push({
      id: `sdk-${status.sdkId}`,
      label: t('chat.localCommand.doctor.sdk'),
      value: status.sdkInstalled ? dash(status.sdkVersion) : t('chat.localCommand.notInstalled'),
      status: status.sdkInstalled ? 'ok' : 'error',
    });
  }

  const cliRows: LocalCommandRow[] = (diagnostics?.clis ?? []).map((cli) => ({
    id: `cli-${cli.id}`,
    label: cli.name || cli.id,
    value: cli.installed
      ? (cli.version ? cli.version : t('chat.localCommand.installed'))
      : t('chat.localCommand.notInstalled'),
    status: cli.installed ? 'ok' : 'error',
  }));
  if (cliRows.length === 0) {
    cliRows.push({
      id: 'cli-none',
      label: t('chat.localCommand.doctor.cliTools'),
      value: t('chat.localCommand.doctor.none'),
    });
  }

  return {
    type: 'local_command_output',
    command: '/doctor',
    state: 'ready',
    title: localCommandTitle('doctor', t),
    subtitle: localCommandSubtitle('doctor', t),
    sections: [
      { id: 'node', title: t('chat.localCommand.doctor.node'), rows: nodeRows },
      { id: 'sdk', title: t('chat.localCommand.doctor.sdk'), rows: sdkRows },
      { id: 'cli', title: t('chat.localCommand.doctor.cliTools'), rows: cliRows },
    ],
    actions: [{ kind: 'openDependencySettings', label: t('chat.localCommand.openDependencySettings') }],
    note: t('chat.localCommand.doctor.note'),
  };
}

export function buildMemoryCard(payload: StatusInfoPayload, t: TFunction): LocalCommandOutputBlock {
  const files = payload.memoryFiles ?? [];
  return {
    type: 'local_command_output',
    command: '/memory',
    state: 'ready',
    title: localCommandTitle('memory', t),
    subtitle: localCommandSubtitle('memory', t),
    sections: [{
      id: 'memoryFiles',
      rows: buildFileRows(t, files, MEMORY_LABEL_KEYS),
    }],
    note: files.length === 0 ? t('chat.localCommand.memory.empty') : undefined,
  };
}

export function buildHooksCard(payload: StatusInfoPayload, t: TFunction): LocalCommandOutputBlock {
  const files = payload.hookFiles ?? [];
  return {
    type: 'local_command_output',
    command: '/hooks',
    state: 'ready',
    title: localCommandTitle('hooks', t),
    subtitle: localCommandSubtitle('hooks', t),
    sections: [{
      id: 'hookFiles',
      rows: buildFileRows(t, files, HOOK_LABEL_KEYS),
    }],
    note: files.length === 0 ? t('chat.localCommand.hooks.empty') : undefined,
  };
}

export function buildHelpCard(t: TFunction): LocalCommandOutputBlock {
  return {
    type: 'local_command_output',
    command: '/help',
    state: 'ready',
    title: localCommandTitle('help', t),
    subtitle: localCommandSubtitle('help', t),
    sections: [{
      id: 'commands',
      rows: HELP_ROWS.map(({ command, key }) => ({
        id: command,
        label: command,
        value: t(key),
      })),
    }],
    note: t('chat.localCommand.help.note'),
  };
}

export function buildCardForKind(
  kind: LocalCommandDataKind,
  payload: StatusInfoPayload,
  t: TFunction,
): LocalCommandOutputBlock {
  switch (kind) {
    case 'doctor':
      return buildDoctorCard(payload, t);
    case 'memory':
      return buildMemoryCard(payload, t);
    case 'hooks':
      return buildHooksCard(payload, t);
    case 'status':
    default:
      return buildStatusCard(payload, t);
  }
}
