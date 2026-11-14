import { jest } from '@jest/globals';

export const clientInstances = [];

export class Client {
  constructor(config) {
    Object.assign(this, config);
    this.connected = false;
    this.subscriptions = [];
    this.published = [];
    this.activate = jest.fn();
    this.deactivate = jest.fn(() => {
      this.connected = false;
    });
    this.subscribe = jest.fn((destination, handler) => {
      const subscription = { destination, handler, unsubscribe: jest.fn() };
      this.subscriptions.push(subscription);
      return subscription;
    });
    this.publish = jest.fn((frame) => this.published.push(frame));
    clientInstances.push(this);
  }

  async completeConnect() {
    await this.beforeConnect(this);
    this.connected = true;
    this.onConnect();
  }

  destinations() {
    return this.subscriptions.map((subscription) => subscription.destination);
  }

  subscriptionTo(destination) {
    return this.subscriptions.findLast((subscription) => subscription.destination === destination);
  }
}
