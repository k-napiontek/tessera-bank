import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { money } from '../money';
import { BalanceMeter } from './BalanceMeter';

const pln = (minor: bigint) => money(minor, 'PLN');

describe('the balance meter', () => {
  it('shows nothing when every booked zloty is available', () => {
    const { container } = render(
      <BalanceMeter booked={pln(250000n)} available={pln(250000n)} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('states the held amount in its accessible name, not only in colour', () => {
    // A bar a sighted reader can interpret and a screen-reader user cannot would discharge
    // REQ-UI-003 for some readers and not others.
    render(<BalanceMeter booked={pln(250000n)} available={pln(190000n)} />);

    expect(
      screen.getByRole('img', { name: /600\.00 PLN of 2500\.00 PLN is held/i }),
    ).toBeInTheDocument();
  });

  it('sizes the available share in proportion to the booked balance', () => {
    render(<BalanceMeter booked={pln(250000n)} available={pln(190000n)} />);

    const meter = screen.getByRole('img');
    const available = meter.querySelector('.meter-available');
    expect(available).toHaveStyle({ width: '76%' });
  });

  it('shows nothing when there is no positive balance to take a share of', () => {
    // A proportion of an overdrawn account is not a quantity anyone can read. The card's sentence
    // still says what is held; only the picture is withheld.
    const { container } = render(
      <BalanceMeter booked={pln(-45012n)} available={pln(-50012n)} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('empties the available share rather than going negative when a hold exceeds the balance', () => {
    render(<BalanceMeter booked={pln(10000n)} available={pln(-5000n)} />);

    const available = screen.getByRole('img').querySelector('.meter-available');
    expect(available).toHaveStyle({ width: '0%' });
  });

  it('refuses to compare two currencies', () => {
    expect(() =>
      render(<BalanceMeter booked={money(250000n, 'PLN')} available={money(190000n, 'EUR')} />),
    ).toThrow();
  });
});
