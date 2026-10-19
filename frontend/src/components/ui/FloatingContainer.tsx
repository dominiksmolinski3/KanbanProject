import { createContext, useContext } from 'react';

const FloatingContainer = createContext<HTMLElement | null>(null);

export const FloatingContainerProvider = FloatingContainer.Provider;

export const useFloatingContainer = (): HTMLElement => useContext(FloatingContainer) ?? document.body;
