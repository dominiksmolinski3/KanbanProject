/* eslint-disable */
export default {
    testEnvironment: 'jsdom',
    transform: {
        '^.+\\.[jt]sx?$': 'babel-jest',
    },
    moduleNameMapper: {
      '\\.(css|less|scss|sass)$': 'identity-obj-proxy',
      '\\.(jpg|jpeg|png|gif|eot|otf|webp|svg|ttf|woff|woff2|mp4|webm|wav|mp3|m4a|aac|oga)$': '<rootDir>/src/__mocks__/fileMock.js',
    },
    setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
    testMatch: ['**/__tests__/**/*.[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)'],
    moduleDirectories: ['node_modules', 'src'],
    collectCoverageFrom: [
      'src/**/*.{js,jsx,ts,tsx}',
      '!src/**/*.test.{js,jsx,ts,tsx}',
      '!src/**/*.d.ts',
      '!src/test-utils/**',
      '!src/__mocks__/**',
      '!src/main.tsx',
      '!src/i18n.ts',
    ],
    coverageThreshold: {
      './src/App.tsx': { statements: 95, branches: 90, functions: 90, lines: 95 },
      './src/board/': { statements: 97.5, branches: 87.5, functions: 96, lines: 98.5 },
      './src/components/': { statements: 95, branches: 90, functions: 94, lines: 95.5 },
      './src/hooks/': { statements: 95, branches: 95, functions: 95, lines: 95 },
      './src/context/': { statements: 89.5, branches: 81, functions: 95.5, lines: 91 },
      './src/services/': { statements: 95, branches: 88.5, functions: 91, lines: 95.5 },
      './src/theme/': { statements: 95, branches: 99, functions: 99, lines: 94.5 },
    },
  };