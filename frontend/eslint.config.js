import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default [
  {
    ignores: ['dist', 'coverage', 'android', 'src/__tests__', 'cypress.config.js', 'cypress/e2e/*', 'cypress/replicas/*', 'cypress/warmup/*', 'cypress/warmup.config.js', 'cypress/support/*', 'cypress/seed-test-account.js'],
  },  
  {
    files: ['**/*.{js,jsx,ts,tsx}'],
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
      'react-hooks/rules-of-hooks': reactHooks.configs.recommended.rules['react-hooks/rules-of-hooks'],
      'react-hooks/exhaustive-deps': reactHooks.configs.recommended.rules['react-hooks/exhaustive-deps'],
      'no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]' }],
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
    },
  },
  ...tseslint.configs.recommended.map((config) => ({ ...config, files: ['**/*.{ts,tsx}'] })),
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { varsIgnorePattern: '^[A-Z_]' }],
    },
  },
  {
    files: ['cypress/shard.js', 'cypress/shardPlan.js', 'cypress/browserConnect.js', 'cypress/retry-browser-connect.js'],
    languageOptions: { globals: globals.node },
  },
]
