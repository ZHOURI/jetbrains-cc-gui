import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AgentViewSession } from '../../types';
import { UIStateProvider, useUIState } from '../../contexts/UIStateContext';
import { AgentViewPanel } from './AgentViewPanel';

const translationMap: Record<string, string> = {
  'agentView.title': 'Agent View',
  'agentView.empty': '暂无会话',
  'agentView.loading': '加载中',
  'agentView.untitled': '未命名会话',
  'agentView.current': '当前',
  'agentView.attach': '切换',
  'agentView.stop': '停止',
  'agentView.confirmStop': '确认停止',
  'agentView.newSession': '新建会话',
  'agentView.refresh': '刷新',
  'agentView.hint': '↑/↓ 选择',
  'agentView.messageCount_other': '{{count}} 条消息',
  'agentView.status.idle': '空闲',
  'agentView.status.streaming': '运行中',
  'agentView.status.waiting': '等待确认',
  'common.cancel': '取消',
  'common.close': '关闭',
};

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) => {
      if (key === 'agentView.messageCount' && options?.count !== undefined) {
        return `${options.count} 条消息`;
      }
      return translationMap[key] ?? options?.defaultValue ?? key;
    },
    i18n: { language: 'zh' },
  }),
}));

const agent = vi.hoisted(() => ({
  sessions: [] as AgentViewSession[],
  activeKey: 'k1' as string | null,
  loading: false,
  refresh: vi.fn(),
  attach: vi.fn(),
  stop: vi.fn(),
  create: vi.fn(),
}));

vi.mock('../../hooks/useAgentViewSessions', () => ({
  useAgentViewSessions: () => agent,
}));

const session = (overrides: Partial<AgentViewSession> = {}): AgentViewSession => ({
  key: 'k1',
  title: 'Inspect the parser',
  provider: 'claude',
  model: 'claude-sonnet-5',
  status: 'idle',
  busy: false,
  unread: false,
  messageCount: 3,
  lastActivity: Date.UTC(2026, 10, 15, 10, 30),
  isActive: true,
  owned: false,
  ...overrides,
});

const OpenOnMount = () => {
  const { setAgentViewOpen } = useUIState();
  useEffect(() => setAgentViewOpen(true), [setAgentViewOpen]);
  return null;
};

const renderPanel = () => render(
  <UIStateProvider>
    <OpenOnMount />
    <AgentViewPanel onBeginSessionTransition={vi.fn()} />
  </UIStateProvider>,
);

describe('AgentViewPanel', () => {
  beforeEach(() => {
    agent.sessions = [
      session(),
      session({ key: 'k2', title: 'Background run', isActive: false, status: 'streaming', unread: true }),
    ];
    agent.activeKey = 'k1';
    vi.clearAllMocks();
  });

  it('renders each runtime with status, metadata and unread marker', async () => {
    renderPanel();
    const dialog = await screen.findByRole('dialog');

    expect(screen.getByText('Agent View')).toBeTruthy();
    expect(screen.getByText('Inspect the parser')).toBeTruthy();
    expect(screen.getByText('Background run')).toBeTruthy();
    expect(screen.getAllByRole('option')).toHaveLength(2);
    expect(screen.getByText('当前')).toBeTruthy();
    expect(screen.getByText('运行中')).toBeTruthy();
    expect(screen.getByLabelText('agentView.unread')).toBeTruthy();
    expect(dialog).toBeTruthy();
  });

  it('attaches to a background row and never re-attaches the current one', async () => {
    renderPanel();
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByText('切换'));
    expect(agent.attach).toHaveBeenCalledTimes(1);
    expect(agent.attach.mock.calls[0][0].key).toBe('k2');

    fireEvent.click(screen.getByText('Inspect the parser'));
    expect(agent.attach).toHaveBeenCalledTimes(1);
  });

  it('asks for confirmation before stopping', async () => {
    renderPanel();
    await screen.findByRole('dialog');
    const [stopButton] = screen.getAllByText('停止');

    fireEvent.click(stopButton);
    expect(agent.stop).not.toHaveBeenCalled();

    fireEvent.click(screen.getByText('确认停止'));
    expect(agent.stop).toHaveBeenCalledTimes(1);
    expect(agent.stop.mock.calls[0][0].key).toBe('k1');
  });

  it('creates a session and refreshes on demand', async () => {
    renderPanel();
    await screen.findByRole('dialog');

    fireEvent.click(screen.getByText('新建会话'));
    fireEvent.click(screen.getByText('刷新'));

    expect(agent.create).toHaveBeenCalledTimes(1);
    expect(agent.refresh).toHaveBeenCalledTimes(1);
  });

  it('navigates with the keyboard and attaches with Enter', async () => {
    renderPanel();
    const dialog = await screen.findByRole('dialog');

    fireEvent.keyDown(dialog, { key: 'ArrowDown' });
    fireEvent.keyDown(dialog, { key: 'Enter' });

    expect(agent.attach).toHaveBeenCalledTimes(1);
    expect(agent.attach.mock.calls[0][0].key).toBe('k2');
  });

  it('closes on Escape', async () => {
    renderPanel();
    const dialog = await screen.findByRole('dialog');

    fireEvent.keyDown(dialog, { key: 'Escape' });

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
