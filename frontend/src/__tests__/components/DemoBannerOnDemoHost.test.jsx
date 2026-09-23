/**
 * @jest-environment jsdom
 * @jest-environment-options {"url": "http://kanbanproject.pl/"}
 */
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
