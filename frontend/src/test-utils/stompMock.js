import { jest } from '@jest/globals';

export const clientInstances = [];

export class Client {
  constructor(config) {
    Object.assign(this, config);
    this.connected = false;
    this.subscriptions = [];
    this.published = [];
    this.receipts = new Map();
    this.activate = jest.fn();
    this.deactivate = jest.fn(() => {
      this.connected = false;
    });
    this.watchForReceipt = jest.fn((receipt, onReceipt) => this.receipts.set(receipt, onReceipt));
    this.subscribe = jest.fn((destination, handler, headers = {}) => {
      const subscription = { destination, handler, receipt: headers.receipt, unsubscribe: jest.fn() };
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

  confirm(subscription) {
    this.receipts.get(subscription.receipt)?.();
  }

  destinations() {
    return this.subscriptions.map((subscription) => subscription.destination);
  }

  subscriptionTo(destination) {
    return this.subscriptions.findLast((subscription) => subscription.destination === destination);
  }
}
