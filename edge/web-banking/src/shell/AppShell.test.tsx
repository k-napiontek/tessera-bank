import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AppShellProps } from './AppShell';
import { AppShell } from './AppShell';

function renderShell(props: AppShellProps): void {
  render(
    <MemoryRouter>
      <AppShell {...props} />
    </MemoryRouter>,
  );
}

describe('the application shell', () => {
  it('names the bank in a heading', () => {
    renderShell({ children: <p>anything</p> });
    expect(screen.getByRole('heading', { name: 'Tessera Bank' })).toBeInTheDocument();
  });

  it('offers one navigation and not two', async () => {
    // The bottom tab bar and the desktop rail are the same element moved by a media query. Two
    // elements would mean two tab orders and every destination announced twice, and a media query
    // is not something a screen reader consults.
    renderShell({ children: <p>anything</p>, onSignOut: vi.fn() });

    const navigations = await screen.findAllByRole('navigation');
    expect(navigations).toHaveLength(1);

    const links = within(navigations[0] as HTMLElement).getAllByRole('link');
    expect(links.map((link) => link.textContent)).toEqual(['Accounts', 'Transfer']);
  });

  it('shows no navigation before there is a session, because there is nowhere to go', () => {
    renderShell({ children: <p>anything</p> });
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /sign out/i })).not.toBeInTheDocument();
  });

  it('ends the session when asked', async () => {
    const onSignOut = vi.fn();
    const user = userEvent.setup();
    renderShell({ children: <p>anything</p>, onSignOut });

    await user.click(screen.getByRole('button', { name: /sign out/i }));

    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('puts the screen in a main landmark', () => {
    renderShell({ children: <p>the screen</p> });
    expect(within(screen.getByRole('main')).getByText('the screen')).toBeInTheDocument();
  });
});
