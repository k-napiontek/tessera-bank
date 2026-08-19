/**
 * The frame every screen sits in: a bar, a navigation, and the screen itself.
 *
 * **One navigation, moved rather than duplicated.** Under 1024px it is a bar fixed to the bottom of
 * the viewport, where a thumb reaches; above it, a rail down the left. Both are this one element,
 * repositioned by a media query in `shell.css`. Rendering two would give the page two tab orders
 * and announce every destination twice, and a media query is not something a screen reader
 * consults.
 *
 * The shell renders a navigation only when there is a session, because signed out there is exactly
 * one screen and nowhere to go. The only thing it needs from that session is how to end it, so
 * `onSignOut` is what tells it there is one.
 */

import { NavLink } from 'react-router';
import { ROUTES } from '../routes';
import { Wordmark } from './Wordmark';

export interface AppShellProps {
  readonly children: React.ReactNode;
  readonly onSignOut?: () => void;
}

function AccountsIcon(): React.JSX.Element {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true" focusable="false">
      <rect x="2.5" y="6.5" width="19" height="13" rx="2.5" />
      <path d="M2.5 11h19" strokeLinecap="round" />
      <path d="M6 3.5h12" strokeLinecap="round" />
    </svg>
  );
}

function TransferIcon(): React.JSX.Element {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      <path d="M4 8h13l-3.5-3.5" />
      <path d="M20 16H7l3.5 3.5" />
    </svg>
  );
}

export function AppShell({ children, onSignOut }: AppShellProps): React.JSX.Element {
  return (
    <div className="shell">
      <header className="appbar">
        <div className="appbar-inner">
          <h1 className="brand">
            <Wordmark className="brand-mark" />
            <span className="brand-name">Tessera Bank</span>
          </h1>
          {onSignOut !== undefined && (
            <button type="button" className="appbar-action" onClick={onSignOut}>
              Sign out
            </button>
          )}
        </div>
      </header>

      {onSignOut !== undefined && (
        <nav className="nav" aria-label="Main">
          <NavLink to={ROUTES.dashboard} className="nav-item" end>
            <AccountsIcon />
            <span>Accounts</span>
          </NavLink>
          <NavLink to={ROUTES.transfer} className="nav-item">
            <TransferIcon />
            <span>Transfer</span>
          </NavLink>
        </nav>
      )}

      <main className="content">{children}</main>
    </div>
  );
}
