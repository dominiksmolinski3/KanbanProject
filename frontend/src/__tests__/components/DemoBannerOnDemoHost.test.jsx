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

  test('publishes its height so the language switcher can sit below it, and withdraws it on dismiss', () => {
    const height = jest.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(58);
    const root = document.documentElement;

    render(<DemoBanner />);
    expect(root.style.getPropertyValue('--demo-banner-height')).toBe('58px');

    fireEvent.click(screen.getByLabelText('demo.dismiss'));
    expect(root.style.getPropertyValue('--demo-banner-height')).toBe('');
    height.mockRestore();
  });
});
