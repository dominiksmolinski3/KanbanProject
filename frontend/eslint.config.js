import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

export default [
  {
    ignores: ['dist', 'coverage', 'src/__tests__', 'cypress.config.js', 'cypress/e2e/*', 'cypress/support/*', 'cypress/seed-test-account.js'],
  },  
  {
    files: ['**/*.{js,jsx}'],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
      parserOptions: {
        ecmaVersion: 'latest',
        ecmaFeatures: { jsx: true },
        sourceType: 'module',
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...js.configs.recommended.rules,
      // v7's `recommended` folded in the React Compiler rule set (immutability, refs,
      // set-state-in-effect, purity, ...) as errors. Adopting that across the app is a
      // deliberate refactor, not a dependency bump, so pin to the rules this project has
      // always enforced and leave the rest for a follow-up.
      'react-hooks/rules-of-hooks': reactHooks.configs.recommended.rules['react-hooks/rules-of-hooks'],
      'react-hooks/exhaustive-deps': reactHooks.configs.recommended.rules['react-hooks/exhaustive-deps'],
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]' }],
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
]
