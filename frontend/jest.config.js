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
      'src/**/*.{js,jsx}',
      '!src/**/*.test.{js,jsx}',
      '!src/__mocks__/**',
      '!src/main.jsx',
      '!src/i18n.js',
    ],
    coverageThreshold: {
      './src/App.jsx': { statements: 95, branches: 90, functions: 90, lines: 95 },
      './src/components/': { statements: 80.5, branches: 70, functions: 74, lines: 81.5 },
      './src/context/': { statements: 78.5, branches: 76, functions: 88, lines: 78 },
      './src/services/': { statements: 84.5, branches: 77, functions: 86, lines: 84.5 },
    },
  };