/**
 * The mark: four tesserae, the last one still being set.
 *
 * A *tessera* is one tile of a Roman mosaic, and *tesserae nummulariae* were the tokens bankers
 * used to certify coin - which is where this bank got its name. The estate behind this screen is
 * the same shape: five strata, thirty years apart, one picture. Three tiles square to the grid and
 * one turned is the whole of that idea, and it is small enough to survive a 16-pixel favicon.
 *
 * Drawn in `currentColor` so one mark serves both the navy bar and the white page, and hidden from
 * the accessible tree because the name is beside it in text.
 */
export function Wordmark({ className }: { className?: string }): React.JSX.Element {
  return (
    <svg
      className={className}
      viewBox="0 0 24 24"
      width="24"
      height="24"
      fill="currentColor"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="2" y="2" width="9" height="9" rx="1.5" />
      <rect x="13" y="2" width="9" height="9" rx="1.5" />
      <rect x="2" y="13" width="9" height="9" rx="1.5" />
      <rect x="13" y="13" width="9" height="9" rx="1.5" opacity="0.55" transform="rotate(14 17.5 17.5)" />
    </svg>
  );
}
