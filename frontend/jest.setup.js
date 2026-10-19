/* eslint-disable */
require('@testing-library/jest-dom');
const { configure } = require('@testing-library/react');
const { notifyManager } = require('@tanstack/react-query');

// Query's default scheduler is setTimeout(0), which a test using fake timers never reaches.
notifyManager.setScheduler((callback) => Promise.resolve().then(callback));

// waitFor's 1 s default fails the first test of a suite when a local build starves the CPU; a passing wait still returns at once.
configure({ asyncUtilTimeout: 3000 });
jest.setTimeout(15000);
const { TextEncoder, TextDecoder } = require('util');
global.TextEncoder = TextEncoder;
global.TextDecoder = TextDecoder;