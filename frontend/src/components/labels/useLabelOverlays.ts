import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import { formPositionFor, pickerPositionFor, type Position } from './labelLayout';
import { useOutsideMouseDown } from './useOutsideMouseDown';

const READY_DELAY_MS = 50;

export function useLabelOverlays(buttonRef: RefObject<HTMLButtonElement | null>) {
  const [showPicker, setShowPicker] = useState(false);
  const [pickerReady, setPickerReady] = useState(false);
  const [pickerPosition, setPickerPosition] = useState<Position>({ top: 0, left: 0 });
  const [showForm, setShowForm] = useState(false);
  const [formReady, setFormReady] = useState(false);
  const [formPosition, setFormPosition] = useState<Position>({ top: 0, left: 0 });
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  const closePicker = useCallback(() => setShowPicker(false), []);
  const closeForm = useCallback(() => setShowForm(false), []);

  const togglePicker = () => {
    if (!showPicker) {
      setPickerReady(false);
      if (buttonRef.current) {
        const viewportWidth = document.documentElement.clientWidth || window.innerWidth;
        setPickerPosition(pickerPositionFor(
          buttonRef.current.getBoundingClientRect(),
          { x: window.scrollX, y: window.scrollY },
          viewportWidth
        ));
        timers.current.push(setTimeout(() => setPickerReady(true), READY_DELAY_MS));
      }
    }
    setShowPicker(!showPicker);
  };

  const openForm = () => {
    setFormReady(false);
    setShowPicker(false);

    const viewportWidth = Math.max(document.documentElement.clientWidth || 0, window.innerWidth || 0);
    const viewportHeight = Math.max(document.documentElement.clientHeight || 0, window.innerHeight || 0);
    setFormPosition(formPositionFor(viewportWidth, viewportHeight));

    timers.current.push(setTimeout(() => {
      setShowForm(true);
      setFormReady(true);
    }, READY_DELAY_MS));
  };

  const ignoreButton = useCallback(() => buttonRef.current, [buttonRef]);
  useOutsideMouseDown(showPicker, '.label-picker', closePicker, ignoreButton);
  useOutsideMouseDown(showForm, '.custom-label-form-portal', closeForm);

  return {
    picker: { visible: showPicker && pickerReady, position: pickerPosition },
    form: { visible: showForm && formReady, position: formPosition },
    togglePicker, closePicker, openForm, closeForm,
  };
}
