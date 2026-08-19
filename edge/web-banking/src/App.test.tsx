import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('the application shell', () => {
  it('renders its name in a heading', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Tessera Bank' })).toBeInTheDocument();
  });
});
