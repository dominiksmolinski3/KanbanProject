import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import FormModal from '../../components/FormModal';

function Host({ onClose }) {
  return (
    <FormModal onClose={onClose}>
      <input aria-label="name" />
    </FormModal>
  );
}

describe('FormModal', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  test('focuses the dialog when it opens', () => {
    render(<Host onClose={() => {}} />);
    act(() => jest.runAllTimers());
    expect(screen.getByRole('dialog')).toHaveFocus();
  });

  test('a parent re-render with a new onClose leaves focus in the field being typed in', () => {
    const { rerender } = render(<Host onClose={() => {}} />);
    act(() => jest.runAllTimers());

    const field = screen.getByLabelText('name');
    field.focus();
    rerender(<Host onClose={() => {}} />);
    act(() => jest.runAllTimers());

    expect(field).toHaveFocus();
  });

  test('Escape calls the latest onClose', () => {
    const first = jest.fn();
    const latest = jest.fn();
    const { rerender } = render(<Host onClose={first} />);
    rerender(<Host onClose={latest} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(first).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledTimes(1);
  });

  test('restores the page scroll on close', () => {
    document.body.style.overflow = 'auto';
    const { unmount } = render(<Host onClose={() => {}} />);
    expect(document.body.style.overflow).toBe('hidden');
    unmount();
    expect(document.body.style.overflow).toBe('auto');
  });
});
