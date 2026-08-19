import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('the application shell', () => {
  it('renders its name in a heading', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('heading', { name: 'Tessera Bank' })).toBeInTheDocument();
  });

  it('shows the sign-in form before anything else, because there is no client without a token', () => {
    render(
      <MemoryRouter>
        <App />
      </MemoryRouter>,
    );
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });
});
