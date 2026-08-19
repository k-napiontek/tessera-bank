/**
 * Where the customer is in a transfer, and what they have already done.
 *
 * Numbered because this is genuinely a sequence and the order carries information: details are
 * entered, then confirmed, and only then is there a result. The confirmation is not ceremony - it
 * is where the request stops changing and the idempotency key is minted, so a customer who does
 * not know a second step is coming will read the first one as the last.
 *
 * Nothing here is focusable. `accessibility.test.tsx` walks the transfer journey by counting tab
 * stops, and an indicator that took one would be a control that does nothing.
 */

export type Stage = 'details' | 'confirm' | 'result';

const STAGES: readonly { readonly stage: Stage; readonly label: string }[] = [
  { stage: 'details', label: 'Details' },
  { stage: 'confirm', label: 'Confirm' },
  { stage: 'result', label: 'Result' },
];

function stateOf(stage: Stage, current: Stage): 'done' | 'current' | 'todo' {
  const at = STAGES.findIndex((entry) => entry.stage === current);
  const here = STAGES.findIndex((entry) => entry.stage === stage);
  if (here < at) {
    return 'done';
  }
  return here === at ? 'current' : 'todo';
}

export function Steps({ stage }: { stage: Stage }): React.JSX.Element {
  return (
    <ol className="steps" aria-label="Transfer progress">
      {STAGES.map((entry, index) => {
        const state = stateOf(entry.stage, stage);
        return (
          <li
            key={entry.stage}
            className="step"
            data-state={state}
            {...(state === 'current' ? { 'aria-current': 'step' as const } : {})}
          >
            <span className="step-index" aria-hidden="true">
              {index + 1}
            </span>
            <span className="step-label">{entry.label}</span>
          </li>
        );
      })}
    </ol>
  );
}
