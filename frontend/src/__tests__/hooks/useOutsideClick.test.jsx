import React, { useRef } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { useOutsideClick } from '../../hooks/useOutsideClick';

function Probe({ active = true, onOutside }) {
  const ref = useRef(null);
  useOutsideClick(ref, active, onOutside);
  return (
    <div>
      <div ref={ref} data-testid="inside"><span>child</span></div>
      <button type="button">elsewhere</button>
    </div>
  );
}

describe('useOutsideClick', () => {
  test('calls back for a press outside the element', () => {
    const onOutside = jest.fn();
    render(<Probe onOutside={onOutside} />);

    fireEvent.mouseDown(screen.getByText('elsewhere'));

    expect(onOutside).toHaveBeenCalledTimes(1);
  });

  test('ignores a press on the element or inside it', () => {
    const onOutside = jest.fn();
    render(<Probe onOutside={onOutside} />);

    fireEvent.mouseDown(screen.getByTestId('inside'));
    fireEvent.mouseDown(screen.getByText('child'));

    expect(onOutside).not.toHaveBeenCalled();
  });

  test('listens only while it is active', () => {
    const onOutside = jest.fn();
    const { rerender } = render(<Probe active={false} onOutside={onOutside} />);

    fireEvent.mouseDown(screen.getByText('elsewhere'));
    expect(onOutside).not.toHaveBeenCalled();

    rerender(<Probe active onOutside={onOutside} />);
    fireEvent.mouseDown(screen.getByText('elsewhere'));
    expect(onOutside).toHaveBeenCalledTimes(1);

    rerender(<Probe active={false} onOutside={onOutside} />);
    fireEvent.mouseDown(screen.getByText('elsewhere'));
    expect(onOutside).toHaveBeenCalledTimes(1);
  });

  test('calls the newest callback without listening again', () => {
    const first = jest.fn();
    const second = jest.fn();
    const add = jest.spyOn(document, 'addEventListener');
    const { rerender } = render(<Probe onOutside={first} />);
    const registered = add.mock.calls.filter(([type]) => type === 'mousedown').length;

    rerender(<Probe onOutside={second} />);
    fireEvent.mouseDown(screen.getByText('elsewhere'));

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
    expect(add.mock.calls.filter(([type]) => type === 'mousedown')).toHaveLength(registered);
    add.mockRestore();
  });

  test('stops listening when the component goes away', () => {
    const onOutside = jest.fn();
    const { unmount } = render(<Probe onOutside={onOutside} />);

    unmount();
    fireEvent.mouseDown(document.body);

    expect(onOutside).not.toHaveBeenCalled();
  });
});
