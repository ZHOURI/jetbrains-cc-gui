import { memo } from 'react';
import type { TFunction } from 'i18next';
import type { LocalCommandOutputBlock as LocalCommandOutput } from '../../../types/localCommand';
import { openFile } from '../../../utils/bridge';

interface LocalCommandOutputBlockProps {
  block: LocalCommandOutput;
  t: TFunction;
  onNavigateToDependencySettings?: () => void;
}

/**
 * Chat-stream card for GUI-local slash command output (/status, /doctor, …).
 * Rendered outside the assistant bubble; every request/state transition just
 * swaps the block payload, so the component stays stateless.
 */
export const LocalCommandOutputBlock = memo(function LocalCommandOutputBlock({
  block,
  t,
  onNavigateToDependencySettings,
}: LocalCommandOutputBlockProps) {
  const isLoading = block.state === 'loading';
  const isError = block.state === 'error';

  return (
    <div
      className={`local-command-card${isLoading ? ' local-command-card-loading' : ''}${isError ? ' local-command-card-error' : ''}`}
    >
      <div className="local-command-header">
        <span className="local-command-chip">{block.command}</span>
        <span className="local-command-title">{block.title}</span>
      </div>
      {block.subtitle && <div className="local-command-subtitle">{block.subtitle}</div>}

      {isLoading && block.note && <div className="local-command-note">{block.note}</div>}
      {isError && (
        <div className="local-command-error">
          {block.error || t('chat.localCommand.failed')}
        </div>
      )}

      {!isLoading && !isError && block.sections.map((section) => (
        <div key={section.id} className="local-command-section">
          {section.title && <div className="local-command-section-title">{section.title}</div>}
          {section.rows.map((row) => (
            <div key={row.id} className="local-command-row">
              <span className="local-command-row-label">{row.label}</span>
              <span className="local-command-row-main">
                {row.path && (
                  <span className="local-command-row-path" title={row.path}>{row.path}</span>
                )}
                {row.value && (
                  <span className={`local-command-row-value${row.status ? ` is-${row.status}` : ''}`}>
                    {row.value}
                  </span>
                )}
              </span>
              {row.path && row.exists && (
                <button
                  type="button"
                  className="local-command-row-open"
                  onClick={() => openFile(row.path)}
                >
                  {t('chat.localCommand.open')}
                </button>
              )}
            </div>
          ))}
        </div>
      ))}

      {!isError && !isLoading && block.actions && block.actions.length > 0 && (
        <div className="local-command-actions">
          {block.actions.map((action) => {
            if (action.kind === 'openDependencySettings') {
              if (!onNavigateToDependencySettings) return null;
              return (
                <button
                  key="open-dependency-settings"
                  type="button"
                  className="local-command-action"
                  onClick={onNavigateToDependencySettings}
                >
                  {action.label}
                </button>
              );
            }
            return (
              <button
                key={`open-file-${action.path}`}
                type="button"
                className="local-command-action"
                onClick={() => openFile(action.path)}
              >
                {action.label}
              </button>
            );
          })}
        </div>
      )}

      {!isLoading && !isError && block.note && (
        <div className="local-command-note">{block.note}</div>
      )}
    </div>
  );
});
