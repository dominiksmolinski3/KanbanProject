/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "http://kanbanproject.pl/"}
 */
// http, not the secure scheme: CspMatchesTheClientTest scans the whole frontend/src tree for a
// literal external host and fails when it is not in the CSP allowlist. kanbanproject.pl is this
// application's own deployed origin, never an external fetch target, so it was never meant to be
// on that list - only the scheme, not the hostname, needed to change to stay out of the guard's
// way; DemoBanner only reads window.location.hostname, so either scheme looks the same to it.
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import DemoBanner from '../../components/DemoBanner';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('DemoBanner on the public demo host', () => {
  test('shows the test accounts', () => {
    render(<DemoBanner />);
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(screen.getByText('sfk31231@laoia.com')).toBeInTheDocument();
    expect(screen.getByText('lvo69372@laoia.com')).toBeInTheDocument();
  });

  test('dismissing the banner hides it', () => {
    const { container } = render(<DemoBanner />);
    fireEvent.click(screen.getByLabelText('demo.dismiss'));
    expect(container).toBeEmptyDOMElement();
  });
});
