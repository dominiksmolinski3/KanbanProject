import { defineConfig } from "cypress";

export default defineConfig({
  component: {
    devServer: {
      framework: "react",
      bundler: "vite",
    },
  },

  e2e: {
    baseUrl: 'http://localhost:5173',

    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',

    experimentalCspAllowList: [
      'script-src',
      'script-src-elem',
      'default-src',
      'form-action',
      'child-src',
      'frame-src',
    ],

    setupNodeEvents(on, config) {
      // implement node event listeners here
    },
  },
});
