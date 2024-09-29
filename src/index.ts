import { NativeModulesProxy, EventEmitter, Subscription } from 'expo-modules-core';

// Import the native module. On web, it will be resolved to ExpoBlePeripheral.web.ts
// and on native platforms to ExpoBlePeripheral.ts
import ExpoBlePeripheralModule from './ExpoBlePeripheralModule';

// Get the native constant value.
export const PI = ExpoBlePeripheralModule.PI;

export function hello(): string {
  return ExpoBlePeripheralModule.hello();
}

export async function setValueAsync(value: string) {
  return await ExpoBlePeripheralModule.setValueAsync(value);
}

const emitter = new EventEmitter(ExpoBlePeripheralModule ?? NativeModulesProxy.ExpoBlePeripheral);

export function addChangeListener(listener: (event: ChangeEventPayload) => void): Subscription {
  return emitter.addListener<ChangeEventPayload>('onChange', listener);
}

export type ChangeEventPayload = {
  value: string;
};
