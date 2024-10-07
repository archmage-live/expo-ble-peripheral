import {NativeModulesProxy, EventEmitter, Subscription} from 'expo-modules-core';

// Import the native module. On web, it will be resolved to ExpoBlePeripheral.web.ts
// and on native platforms to ExpoBlePeripheral.ts
import ExpoBlePeripheralModule from './ExpoBlePeripheralModule';

enum BleState {
  Unknown = "Unknown",
  Resetting = "Resetting",
  Unsupported = "Unsupported",
  Unauthorized = "Unauthorized",
  Off = "Off",
  On = "On",
}

type Service = {
  uuid: string;
  isPrimary: boolean;
  characteristics: Characteristic[];
}

type Characteristic = {
  uuid: string;
  properties: number;
  permissions: number;
  value?: Uint8Array;
}

type AdvertiseDataArgs = {
  // serviceUuids?: string[];
  serviceSolicitationUuids?: string[];
  transportDiscoveryData?: TransportDiscoveryDataArgs[];
  manufacturerSpecificData?: Map<number, Uint8Array>;
  serviceData?: Map<string, Uint8Array>;
  includeTxPowerLevel?: boolean;
  includeDeviceName?: boolean;
}

type TransportDiscoveryDataArgs = {
  transportDataType?: number;
  transportBlocks?: TransportBlockArgs[];
}

type TransportBlockArgs = {
  orgId?: number;
  tdsFlags?: number;
  transportDataLength?: number;
  transportData?: Uint8Array;
}

type PeriodicAdvertisingParametersArgs = {
  includeTxPower?: boolean;
  interval?: number;
}

type AdvertisingSetParametersArgs = {
  isLegacy?: boolean;
  isAnonymous?: boolean;
  includeTxPower?: boolean;
  primaryPhy?: number;
  secondaryPhy?: number;
  connectable?: boolean;
  discoverable?: boolean;
  scannable?: boolean;
  interval?: number;
  txPowerLevel?: number;
  // ownAddressType?: number;
}

type AdvertiseSettingsArgs = {
  advertiseMode?: number;
  advertiseTxPowerLevel?: number;
  advertiseTimeoutMillis?: number;
  advertiseConnectable?: boolean;
  advertiseDiscoverable?: boolean;
  // ownAddressType?: number;
}

const emitter = new EventEmitter(ExpoBlePeripheralModule ?? NativeModulesProxy.ExpoBlePeripheral);

const STATE_CHANGED_EVENT_NAME = "onStateChanged"
const NOTIFICATION_READY_EVENT_NAME = "onNotificationReady"
const CHAR_WRITTEN_EVENT_NAME = "onCharacteristicWritten"

export type StateChangedEventPayload = {
  state: BleState;
};

export type CharacteristicWrittenEventPayload = {
  characteristics: Characteristic[];
};

export default {
  get name(): string {
    return ExpoBlePeripheralModule.name;
  },

  setName(name: string) {
    ExpoBlePeripheralModule.setName(name);
  },

  hasPermission(): boolean {
    return ExpoBlePeripheralModule.hasPermission();
  },

  requestPermission(): Promise<boolean> {
    return ExpoBlePeripheralModule.requestPermission();
  },

  enable(): Promise<boolean> {
    return ExpoBlePeripheralModule.enable();
  },

  get state(): BleState {
    return ExpoBlePeripheralModule.state;
  },

  get isAdvertising(): boolean {
    return ExpoBlePeripheralModule.isAdvertising;
  },

  getServices(): Service[] {
    return ExpoBlePeripheralModule.getServices();
  },

  addService(uuid: string, primary: boolean) {
    ExpoBlePeripheralModule.addService(uuid, primary);
  },

  removeService(uuid: string) {
    ExpoBlePeripheralModule.removeService(uuid);
  },

  removeAllServices() {
    ExpoBlePeripheralModule.removeAllServices();
  },

  getCharacteristics(serviceUuid?: string): Characteristic[] {
    return ExpoBlePeripheralModule.getCharacteristics(serviceUuid);
  },

  addCharacteristic(args: {
    uuid: string;
    properties: number;
    permissions: number;
    value?: Uint8Array;
  }, serviceUuid: string) {
    ExpoBlePeripheralModule.addCharacteristic(args, serviceUuid);
  },

  updateCharacteristic(args: {
    uuid: string;
    value: Uint8Array;
  }, serviceUuid: string) {
    ExpoBlePeripheralModule.updateCharacteristic(args, serviceUuid);
  },

  start(): Promise<void> {
    return ExpoBlePeripheralModule.start();
  },

  stop(): Promise<void> {
    return ExpoBlePeripheralModule.stop();
  },

  startAdvertising(argsOnlyForAndroid: {
    preferAdvertisingSet?: boolean;
    advertiseData?: AdvertiseDataArgs;
    settings?: AdvertiseSettingsArgs;
    parameters?: AdvertisingSetParametersArgs;
    periodicParameters?: PeriodicAdvertisingParametersArgs;
    duration?: number;
    maxExtendedAdvertisingEvents?: number;
  }): Promise<void> {
    return ExpoBlePeripheralModule.startAdvertising(argsOnlyForAndroid);
  },

  stopAdvertising(): Promise<void> {
    return ExpoBlePeripheralModule.stopAdvertising();
  },

  addStateChangedListener(listener: (event: StateChangedEventPayload) => void): Subscription {
    return emitter.addListener<StateChangedEventPayload>(STATE_CHANGED_EVENT_NAME, listener);
  },

  addNotificationReadyListener(listener: () => void): Subscription {
    return emitter.addListener(NOTIFICATION_READY_EVENT_NAME, listener);
  },

  addCharacteristicWrittenListener(listener: (event: CharacteristicWrittenEventPayload) => void): Subscription {
    return emitter.addListener(CHAR_WRITTEN_EVENT_NAME, listener);
  },
}
