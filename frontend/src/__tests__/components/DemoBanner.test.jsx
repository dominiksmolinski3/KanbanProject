import React from 'react';
import { render, screen } from '@testing-library/react';
import '@testing-library/jest-dom';
import DemoBanner from '../../components/DemoBanner';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

describe('DemoBanner off the public demo host', () => {
  test('renders nothing', () => {
    expect(window.location.hostname).not.toMatch(/kanbanproject/);
    const { container } = render(<DemoBanner />);
    expect(container).toBeEmptyDOMElement();
  });
});
