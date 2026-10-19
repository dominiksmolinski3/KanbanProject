import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.kanbanproject.app',
  appName: 'Kanban',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
  },
};

export default config;
