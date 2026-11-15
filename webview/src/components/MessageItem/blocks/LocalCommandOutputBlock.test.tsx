import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TFunction } from 'i18next';
import { LocalCommandOutputBlock } from './LocalCommandOutputBlock';
import type { LocalCommandOutputBlock as LocalCommandOutput } from '../../../types/localCommand';

const t = ((key: string) => key) as unknown as TFunction;
const sendToJava = vi.fn();

function readyBlock(): LocalCommandOutput {
  return {
    type: 'local_command_output',
    command: '/memory',
    state: 'ready',
    title: 'Memory files',
    subtitle: 'Instructions loaded for this session',
    sections: [{
      id: 'memoryFiles',
      rows: [
        { id: 'userMemory', label: 'User memory', path: '/home/u/.claude/CLAUDE.md', exists: true, value: 'Found' },
        { id: 'projectMemory', label: 'Project memory', path: '/project/CLAUDE.md', exists: false, value: 'Not found' },
      ],
    }],
    note: 'note text',
  };
}

beforeEach(() => {
  window.sendToJava = sendToJava;
});

afterEach(() => {
  cleanup();
  delete window.sendToJava;
  sendToJava.mockReset();
});

describe('LocalCommandOutputBlock', () => {
  it('renders title, rows and paths', () => {
    render(<LocalCommandOutputBlock block={readyBlock()} t={t} />);
    expect(screen.getByText('Memory files')).toBeTruthy();
    expect(screen.getByText('/home/u/.claude/CLAUDE.md')).toBeTruthy();
    expect(screen.getByText('Found')).toBeTruthy();
    expect(screen.getByText('Not found')).toBeTruthy();
    expect(screen.getByText('note text')).toBeTruthy();
  });

  it('opens existing files and ignores missing ones', () => {
    render(<LocalCommandOutputBlock block={readyBlock()} t={t} />);
    const openButtons = screen.getAllByText('chat.localCommand.open');
    expect(openButtons).toHaveLength(1);
    fireEvent.click(openButtons[0]);
    expect(sendToJava).toHaveBeenCalledWith('open_file:/home/u/.claude/CLAUDE.md');
  });

  it('renders loading and error states', () => {
    const { rerender } = render(<LocalCommandOutputBlock block={{
      type: 'local_command_output',
      command: '/status',
      state: 'loading',
      title: 'Status',
      sections: [],
      note: 'Loading…',
    }} t={t} />);
    expect(screen.getByText('Loading…')).toBeTruthy();

    rerender(<LocalCommandOutputBlock block={{
      type: 'local_command_output',
      command: '/status',
      state: 'error',
      title: 'Status',
      sections: [],
      error: 'boom',
    }} t={t} />);
    expect(screen.getByText('boom')).toBeTruthy();
  });

  it('invokes the dependency settings action', () => {
    const onNavigate = vi.fn();
    render(<LocalCommandOutputBlock block={{
      type: 'local_command_output',
      command: '/doctor',
      state: 'ready',
      title: 'Doctor',
      sections: [],
      actions: [{ kind: 'openDependencySettings', label: 'Open dependency settings' }],
    }} t={t} onNavigateToDependencySettings={onNavigate} />);
    fireEvent.click(screen.getByText('Open dependency settings'));
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });
});
