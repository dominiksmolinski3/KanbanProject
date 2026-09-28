/* eslint-disable */
require('@testing-library/jest-dom');
const { configure } = require('@testing-library/react');

// waitFor's 1 s default fails the first test of a suite when a local build starves the CPU; a passing wait still returns at once.
configure({ asyncUtilTimeout: 3000 });
jest.setTimeout(15000);
const { TextEncoder, TextDecoder } = require('util');
global.TextEncoder = TextEncoder;
global.TextDecoder = TextDecoder;