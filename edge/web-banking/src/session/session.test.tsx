import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider, parseAccountRefs, useSession } from './session';
import { SignIn } from './SignIn';

const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJDVTAwMDAwMDAwMDEifQ.c2lnbmF0dXJl';

function SessionUnderTest(): React.JSX.Element {
  const session = useSession();
  if (session.status === 'signed-out') {
    return <SignIn onSignIn={session.signIn} />;
  }
  return (
    <div>
      <p>Signed in with {String(session.accountRefs.length)} account(s).</p>
      <button type="button" onClick={session.signOut}>
        Sign out
      </button>
    </div>
  );
}

async function signIn(user: ReturnType<typeof userEvent.setup>): Promise<void> {
  await user.type(screen.getByLabelText(/access token/i), TOKEN);
  await user.type(screen.getByLabelText(/account references/i), 'TB00000000000001 TB00000000000002');
  await user.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('reading account references the customer was given', () => {
  it('accepts them separated by spaces, commas or newlines', () => {
    expect(parseAccountRefs('TB00000000000001, TB00000000000002\nTB00000000000003')).toEqual([
      'TB00000000000001',
      'TB00000000000002',
      'TB00000000000003',
    ]);
  });

  it('refuses anything that is not the shape the contract declares', () => {
    // The estate has no endpoint that lists a customer's accounts, so these arrive by hand and
    // a typo has to be caught here rather than as a 404 three screens later.
    expect(() => parseAccountRefs('ACC-000001')).toThrow();
    expect(() => parseAccountRefs('TB0000000000000')).toThrow();
    expect(() => parseAccountRefs('')).toThrow();
  });

  it('drops a duplicate rather than fetching the same account twice', () => {
    expect(parseAccountRefs('TB00000000000001 TB00000000000001')).toEqual(['TB00000000000001']);
  });
});

describe('a session', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('starts signed out and shows the sign-in form', () => {
    render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });

  it('holds the token and the account references once signed in', async () => {
    const user = userEvent.setup();
    render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    await signIn(user);

    expect(await screen.findByText('Signed in with 2 account(s).')).toBeInTheDocument();
  });

  it('writes the token to neither localStorage nor sessionStorage', async () => {
    // A bearer token in browser storage is readable by every script the page ever loads, and it
    // outlives the tab. This is the single control that keeps it out of both.
    const user = userEvent.setup();
    render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    await signIn(user);
    await screen.findByText('Signed in with 2 account(s).');

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(JSON.stringify(localStorage)).not.toContain(TOKEN);
    expect(JSON.stringify(sessionStorage)).not.toContain(TOKEN);
  });

  it('never writes the token to the console', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) =>
      vi.spyOn(console, level).mockImplementation(() => undefined),
    );
    const user = userEvent.setup();
    render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    await signIn(user);
    await screen.findByText('Signed in with 2 account(s).');

    for (const spy of spies) {
      for (const call of spy.mock.calls) {
        expect(JSON.stringify(call)).not.toContain(TOKEN);
      }
    }
  });

  it('leaves the token nowhere in the rendered page once signed in', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    await signIn(user);
    await screen.findByText('Signed in with 2 account(s).');

    expect(container.innerHTML).not.toContain(TOKEN);
  });

  it('forgets everything on sign out', async () => {
    const user = userEvent.setup();
    render(
      <SessionProvider>
        <SessionUnderTest />
      </SessionProvider>,
    );

    await signIn(user);
    await user.click(await screen.findByRole('button', { name: /sign out/i }));

    expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument();
    expect(screen.getByLabelText(/access token/i)).toHaveValue('');
  });
});

describe('the sign-in form', () => {
  it('masks the token as it is typed', () => {
    render(<SignIn onSignIn={() => undefined} />);
    expect(screen.getByLabelText(/access token/i)).toHaveAttribute('type', 'password');
  });

  it('says why it is asking for a token rather than a password', () => {
    render(<SignIn onSignIn={() => undefined} />);
    // ADR 0007: nothing in this estate issues one, and pretending otherwise would be a lie on the
    // first screen a customer sees.
    expect(screen.getByText(/no component of this estate issues one/i)).toBeInTheDocument();
  });

  it('refuses to sign in with a malformed account reference and explains which', async () => {
    const user = userEvent.setup();
    const onSignIn = vi.fn();
    render(<SignIn onSignIn={onSignIn} />);

    await user.type(screen.getByLabelText(/access token/i), TOKEN);
    await user.type(screen.getByLabelText(/account references/i), 'nonsense');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(onSignIn).not.toHaveBeenCalled();
    expect(await screen.findByRole('alert')).toHaveTextContent(/nonsense/);
  });

  it('refuses an empty token', async () => {
    const user = userEvent.setup();
    const onSignIn = vi.fn();
    render(<SignIn onSignIn={onSignIn} />);

    await user.type(screen.getByLabelText(/account references/i), 'TB00000000000001');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    expect(onSignIn).not.toHaveBeenCalled();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
