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

  test('locks the page scroll while open and releases it on close', () => {
    const { unmount } = render(<Host onClose={() => {}} />);
    expect(document.body).toHaveAttribute('data-scroll-locked');
    unmount();
    expect(document.body).not.toHaveAttribute('data-scroll-locked');
  });

  test('is named by the label it is given', () => {
    render(
      <FormModal onClose={() => {}} ariaLabel="Add a task">
        <input aria-label="name" />
      </FormModal>
    );
    expect(screen.getByRole('dialog', { name: 'Add a task' })).toBeInTheDocument();
  });

  test('Escape does not reach a listener behind the dialog', () => {
    const behind = jest.fn();
    document.addEventListener('keydown', behind);
    render(<Host onClose={() => {}} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    document.removeEventListener('keydown', behind);
    expect(behind).not.toHaveBeenCalled();
  });

  test('a click on the overlay closes it, a click inside does not', () => {
    const onClose = jest.fn();
    render(<Host onClose={onClose} />);
    act(() => jest.runAllTimers());

    fireEvent.pointerDown(screen.getByLabelText('name'));
    expect(onClose).not.toHaveBeenCalled();

    fireEvent.pointerDown(document.querySelector('.modal-overlay'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
