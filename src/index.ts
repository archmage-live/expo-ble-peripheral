import { NativeModulesProxy, EventEmitter, Subscription } from 'expo-modules-core'

// Import the native module. On web, it will be resolved to ExpoBlePeripheral.web.ts
// and on native platforms to ExpoBlePeripheral.ts
import ExpoBlePeripheralModule from './ExpoBlePeripheralModule'

export enum BleState {
  Unknown = 'Unknown',
  Resetting = 'Resetting',
  Unsupported = 'Unsupported',
  Unauthorized = 'Unauthorized',
  Off = 'Off',
  On = 'On',
}

export type Service = {
  uuid: string;
  isPrimary: boolean;
  characteristics: Characteristic[];
}

export type Characteristic = {
  uuid: string;
  properties: number;
  permissions: number;
  value?: Uint8Array;
}

export type AdvertiseDataArgs = {
  // serviceUuids?: string[];
  serviceSolicitationUuids?: string[];
  transportDiscoveryData?: TransportDiscoveryDataArgs[];
  manufacturerSpecificData?: Map<number, Uint8Array>;
  serviceData?: Map<string, Uint8Array>;
  includeTxPowerLevel?: boolean;
  includeDeviceName?: boolean;
}

export type TransportDiscoveryDataArgs = {
  transportDataType?: number;
  transportBlocks?: TransportBlockArgs[];
}

export type TransportBlockArgs = {
  orgId?: number;
  tdsFlags?: number;
  transportDataLength?: number;
  transportData?: Uint8Array;
}

export type PeriodicAdvertisingParametersArgs = {
  includeTxPower?: boolean;
  interval?: number;
}

export type AdvertisingSetParametersArgs = {
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

export type AdvertiseSettingsArgs = {
  advertiseMode?: number;
  advertiseTxPowerLevel?: number;
  advertiseTimeoutMillis?: number;
  advertiseConnectable?: boolean;
  advertiseDiscoverable?: boolean;
  // ownAddressType?: number;
}

const emitter = new EventEmitter(ExpoBlePeripheralModule ?? NativeModulesProxy.ExpoBlePeripheral)

export const STATE_CHANGED_EVENT_NAME = 'onStateChanged'
export const NOTIFICATION_READY_EVENT_NAME = 'onNotificationReady'
export const CHAR_WRITTEN_EVENT_NAME = 'onCharacteristicWritten'

export type StateChangedEventPayload = {
  state: BleState;
};

export type CharacteristicWrittenEventPayload = {
  characteristics: Characteristic[];
};

export default {
  get name(): string {
    return ExpoBlePeripheralModule.name
  },

  setName(name: string) {
    ExpoBlePeripheralModule.setName(name)
  },

  hasPermission(): boolean {
    return ExpoBlePeripheralModule.hasPermission()
  },

  requestPermission(): Promise<boolean> {
    return ExpoBlePeripheralModule.requestPermission()
  },

  enable(): Promise<boolean> {
    return ExpoBlePeripheralModule.enable()
  },

  get state(): BleState {
    return ExpoBlePeripheralModule.state
  },

  get isStarted(): boolean {
    return ExpoBlePeripheralModule.isStarted
  },

  get isAdvertising(): boolean {
    return ExpoBlePeripheralModule.isAdvertising
  },

  getServices(): Service[] {
    return ExpoBlePeripheralModule.getServices()
  },

  addService(uuid: string, primary: boolean) {
    ExpoBlePeripheralModule.addService(uuid, primary)
  },

  removeService(uuid: string) {
    ExpoBlePeripheralModule.removeService(uuid)
  },

  removeAllServices() {
    ExpoBlePeripheralModule.removeAllServices()
  },

  getCharacteristics(serviceUuid?: string): Characteristic[] {
    return ExpoBlePeripheralModule.getCharacteristics(serviceUuid)
  },

  addCharacteristic(args: {
    uuid: string;
    properties: number;
    permissions: number;
    value?: Uint8Array;
  }, serviceUuid: string) {
    ExpoBlePeripheralModule.addCharacteristic(args, serviceUuid)
  },

  updateCharacteristic(args: {
    uuid: string;
    value: Uint8Array;
  }, serviceUuid: string): boolean {
    return ExpoBlePeripheralModule.updateCharacteristic(args, serviceUuid)
  },

  start(): Promise<void> {
    return ExpoBlePeripheralModule.start()
  },

  stop(): Promise<void> {
    return ExpoBlePeripheralModule.stop()
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
    return ExpoBlePeripheralModule.startAdvertising(argsOnlyForAndroid)
  },

  stopAdvertising(): Promise<void> {
    return ExpoBlePeripheralModule.stopAdvertising()
  },

  addStateChangedListener(listener: (event: StateChangedEventPayload) => void): Subscription {
    return emitter.addListener<StateChangedEventPayload>(STATE_CHANGED_EVENT_NAME, listener)
  },

  addNotificationReadyListener(listener: () => void): Subscription {
    return emitter.addListener(NOTIFICATION_READY_EVENT_NAME, listener)
  },

  addCharacteristicWrittenListener(listener: (event: CharacteristicWrittenEventPayload) => void): Subscription {
    return emitter.addListener(CHAR_WRITTEN_EVENT_NAME, listener)
  }
}

/**
 * Characteristic proprty: Characteristic is broadcastable.
 */
export const PROPERTY_BROADCAST = 0x01

/**
 * Characteristic property: Characteristic is readable.
 */
export const PROPERTY_READ = 0x02

/**
 * Characteristic property: Characteristic can be written without response.
 */
export const PROPERTY_WRITE_NO_RESPONSE = 0x04

/**
 * Characteristic property: Characteristic can be written.
 */
export const PROPERTY_WRITE = 0x08

/**
 * Characteristic property: Characteristic supports notification
 */
export const PROPERTY_NOTIFY = 0x10

/**
 * Characteristic property: Characteristic supports indication
 */
export const PROPERTY_INDICATE = 0x20

/**
 * Characteristic property: Characteristic supports write with signature
 */
export const PROPERTY_SIGNED_WRITE = 0x40

/**
 * Characteristic property: Characteristic has extended properties
 */
export const PROPERTY_EXTENDED_PROPS = 0x80

/**
 * Characteristic read permission
 */
export const PERMISSION_READ = 0x01

/**
 * Characteristic permission: Allow encrypted read operations
 */
export const PERMISSION_READ_ENCRYPTED = 0x02

/**
 * Characteristic permission: Allow reading with person-in-the-middle protection
 */
export const PERMISSION_READ_ENCRYPTED_MITM = 0x04

/**
 * Characteristic write permission
 */
export const PERMISSION_WRITE = 0x10

/**
 * Characteristic permission: Allow encrypted writes
 */
export const PERMISSION_WRITE_ENCRYPTED = 0x20

/**
 * Characteristic permission: Allow encrypted writes with person-in-the-middle
 * protection
 */
export const PERMISSION_WRITE_ENCRYPTED_MITM = 0x40

/**
 * Characteristic permission: Allow signed write operations
 */
export const PERMISSION_WRITE_SIGNED = 0x80

/**
 * Characteristic permission: Allow signed write operations with
 * person-in-the-middle protection
 */
export const PERMISSION_WRITE_SIGNED_MITM = 0x100
