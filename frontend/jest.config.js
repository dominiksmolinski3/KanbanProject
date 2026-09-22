/* eslint-disable */
export default {
    testEnvironment: 'jsdom',
    transform: {
        '^.+\\.[jt]sx?$': 'babel-jest',
    },
    moduleNameMapper: {
      // This key used to be declared twice, pointing at a mock file (src/__mocks__/styleMock.js)
      // that does not exist in this tree. The later entry silently won every match, so
      // identity-obj-proxy has been the one actually running all along.
      '\\.(css|less|scss|sass)$': 'identity-obj-proxy',
      '\\.(jpg|jpeg|png|gif|eot|otf|webp|svg|ttf|woff|woff2|mp4|webm|wav|mp3|m4a|aac|oga)$': '<rootDir>/src/__mocks__/fileMock.js',
    },
    setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
    testMatch: ['**/__tests__/**/*.[jt]s?(x)', '**/?(*.)+(spec|test).[jt]s?(x)'],
    moduleDirectories: ['node_modules', 'src'],
    // Measured on 2026-09-22 (`npm run test:coverage`) and set a shade under what it reported,
    // the same way the backend's JaCoCo floors are set: a brand-new file with no suite starts at
    // 0% and drags its directory's aggregate below the floor, so it fails the build rather than
    // sitting invisible in a report nothing gates on. main.jsx (the Vite entry point, which only
    // ever renders <App />) and i18n.js (i18next bootstrap - a module-scope side effect on import,
    // not a unit under test) are excluded for the same reason backend guards leave framework glue
    // out: there is nothing here for a unit test to assert against.
    collectCoverageFrom: [
      'src/**/*.{js,jsx}',
      '!src/**/*.test.{js,jsx}',
      '!src/__mocks__/**',
      '!src/main.jsx',
      '!src/i18n.js',
    ],
    // Jest scores a `global` threshold only over whatever no other key already claimed, so a
    // repo-wide bundle rule and per-directory floors cannot both be expressed through `global` -
    // every source directory gets its own key instead, App.jsx included, and there is nothing left
    // for `global` to mean.
    // Directories are large enough now that a couple of points of slack would let a small new
    // file hide inside the average, so the margin below the measured number is deliberately
    // tight - a shade under, not a percentage point under.
    coverageThreshold: {
      './src/App.jsx': { statements: 60, branches: 90, functions: 30, lines: 95 },
      './src/components/': { statements: 80.5, branches: 70, functions: 74, lines: 81.5 },
      './src/context/': { statements: 78.5, branches: 76, functions: 88, lines: 78 },
      './src/services/': { statements: 84.5, branches: 77, functions: 86, lines: 84.5 },
    },
  };