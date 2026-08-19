import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Steps } from './Steps';

describe('the transfer progress', () => {
  it('names the three stages in the order they happen', () => {
    render(<Steps stage="details" />);

    const stages = screen.getAllByRole('listitem');
    expect(stages.map((stage) => stage.textContent)).toEqual(['1Details', '2Confirm', '3Result']);
  });

  it('marks where the customer is now, and only there', () => {
    render(<Steps stage="confirm" />);

    const current = screen
      .getAllByRole('listitem')
      .filter((stage) => stage.getAttribute('aria-current') === 'step');
    expect(current).toHaveLength(1);
    expect(within(current[0] as HTMLElement).getByText('Confirm')).toBeInTheDocument();
  });

  it('marks the stages already passed, so the indicator says what happened as well as where', () => {
    render(<Steps stage="result" />);

    const done = screen
      .getAllByRole('listitem')
      .filter((stage) => stage.dataset['state'] === 'done');
    expect(done.map((stage) => stage.textContent)).toEqual(['1Details', '2Confirm']);
  });

  it('carries its own name, because a bare list of three words explains nothing', () => {
    render(<Steps stage="details" />);
    expect(screen.getByRole('list', { name: /transfer progress/i })).toBeInTheDocument();
  });
});
