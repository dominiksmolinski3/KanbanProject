import type { DeviceVault } from './deviceVault';

export type VaultOperation = keyof DeviceVault;

export interface VaultRequest {
  id: number;
  operation: VaultOperation;
  args: unknown[];
}

export type VaultResponse =
  | { id: number; ok: true; value: unknown }
  | { id: number; ok: false; error: { name: string; message: string } };

export async function answer(vault: DeviceVault, request: VaultRequest): Promise<VaultResponse> {
  try {
    const operation = vault[request.operation] as (...args: unknown[]) => Promise<unknown>;
    if (typeof operation !== 'function') {
      throw new Error(`Unknown operation ${String(request.operation)}`);
    }
    return { id: request.id, ok: true, value: await operation(...request.args) };
  } catch (error) {
    const failure = error as Error;
    return { id: request.id, ok: false, error: { name: failure.name, message: failure.message } };
  }
}
