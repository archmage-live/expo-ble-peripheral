import ExpoModulesCore
import Foundation
import CoreBluetooth

enum BleState: String, Enumerable {
  case unknown
  case unsupported
  case unauthorized
  case off
  case on
}

struct AddCharacteristicArgs: Record {
  @Field
  var uuid: String

  @Field
  var properties: Int

  @Field
  var permissions: Int

  /**
   * If a characteristic value needs to be writeable, or may change during the lifetime of the
   * published service, it is considered a dynamic value and will be requested on-demand.
   * Dynamic values are identified by a value of nil.
   */
  @Field
  var value: Data?
}

struct UpdateCharacteristicArgs: Record {
  @Field
  var uuid: String

  @Field
  var value: Data = Data()
}

final class GenericException: Exception {
  let _reason: String

  init(_ reason: String) {
    self._reason = reason
    super.init()
  }

  override var reason: String {
    _reason
  }
}

final class WrongBleStateException: Exception {
  let actual: BleState
  let expected: BleState

  init(actual: BleState, expected: BleState) {
    self.actual = actual
    self.expected = expected
    super.init()
  }

  override var reason: String {
    "Expect BLE state `\(expected)`, but got `\(actual)`"
  }
}

let STATE_CHANGED_EVENT_NAME = "onStateChanged"
let NOTIFICATION_READY_EVENT_NAME = "onNotificationReady"
let CHAR_WRITTEN_EVENT_NAME = "onCharacteristicWritten"

public class ExpoBlePeripheralModule: Module {
  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  public func definition() -> ModuleDefinition {
    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('ExpoBlePeripheral')` in JavaScript.
    Name("ExpoBlePeripheral")

    // Defines event names that the module can send to JavaScript.
    Events(
      STATE_CHANGED_EVENT_NAME,
      NOTIFICATION_READY_EVENT_NAME,
      CHAR_WRITTEN_EVENT_NAME
    )

    Property("name") {
      return self.name
    }

    Function("setName") { (name: String) in
      self.name = name
    }

    Property("state") {
      return state
    }

    Function("getServices") { () in
      var services = []
      for (_, service) in servicesMap {
        services.append(service)
      }
      return services
    }

    Function("addService") { (uuid: String, primary: Bool) in
      if let _ = servicesMap[uuid] {
        throw GenericException("Service already added")
      }

      let service = CBMutableService(type: CBUUID(string: uuid), primary: primary)
      servicesMap[uuid] = service
      manager.add(service)
    }

    Function("removeService") { (uuid: String) in
      guard let service = servicesMap[uuid] else {
        throw GenericException("Service not found")
      }

      servicesMap.removeValue(forKey: uuid)
      manager.remove(service)
    }

    Function("removeAllServices") { () in
      servicesMap.removeAll()
      manager.removeAllServices()
    }

    Function("getCharacteristics") { (serviceUuid: String) in
      return try self.getCharacteristics(serviceUuid)
    }

    Function("addCharacteristic") { (args: AddCharacteristicArgs, serviceUuid: String) in
      guard let service = servicesMap[serviceUuid] else {
        throw GenericException("Service not found")
      }

      let characteristic = CBMutableCharacteristic(
        type: CBUUID(string: args.uuid),
        properties: CBCharacteristicProperties(rawValue: UInt(args.properties)),
        value: args.value,
        permissions: CBAttributePermissions(rawValue: UInt(args.permissions))
      )

      service.characteristics?.append(characteristic)
    }

    Function("updateCharacteristic") { (args: UpdateCharacteristicArgs, serviceUuid: String) -> Bool in
      guard let service = servicesMap[serviceUuid] else {
        throw GenericException("Service not found")
      }

      guard let char = service.characteristics?.first(where: { (char: CBCharacteristic) in
        return char.uuid.uuidString == args.uuid
      }) else {
        throw GenericException("Characteristic not found")
      }

      let characteristic = char as! CBMutableCharacteristic
      characteristic.value = args.value

      return manager.updateValue(args.value, for: characteristic, onSubscribedCentrals: nil)
    }

    AsyncFunction("start") { (promise: Promise) in
      if (managerDelegate.startPromise != nil) {
        promise.reject(GenericException("Starting in progress"))
        return
      }

      if (manager.isAdvertising) {
        promise.reject(GenericException("Peripheral is currently advertising data"))
        return
      }

      if (state != BleState.on) {
        promise.reject(WrongBleStateException(actual: state, expected: BleState.on))
        return
      }

      var serviceUuids = [CBUUID]()
      for (_, service) in servicesMap {
        serviceUuids.append(service.uuid)
      }

      let advertisementData = [
        CBAdvertisementDataLocalNameKey: name,
        CBAdvertisementDataServiceUUIDsKey: serviceUuids,
      ] as [String: Any]

      managerDelegate.startPromise = promise
      manager.startAdvertising(advertisementData)
    }

    AsyncFunction("stop") { (promise: Promise) in
      manager.stopAdvertising()
      promise.resolve()
    }

    OnCreate {
      managerDelegate = PeripheralManagerDelegate(module: self)
      manager = CBPeripheralManager(delegate: managerDelegate, queue: nil)
      state = getState()
    }
  }

  var manager: CBPeripheralManager!
  var managerDelegate: PeripheralManagerDelegate!
  
  var state: BleState = BleState.unknown
  
  var name: String = "BlePeripheral"
  var servicesMap = Dictionary<String, CBMutableService>()
  
  func getState() -> BleState {
    switch (manager.state) {
    case CBManagerState.poweredOn:
      return BleState.on
    case CBManagerState.poweredOff:
      return BleState.off
    case CBManagerState.unauthorized:
      return BleState.unauthorized
    case CBManagerState.unsupported:
      return BleState.unsupported
    default:
      return BleState.unknown
    }
  }

  func getCharacteristics(_ serviceUuid: String) throws -> [[String: Any]] {
    guard let service = servicesMap[serviceUuid] else {
      throw GenericException("Service not found")
    }

    return (service.characteristics ?? []).map({ (char: CBCharacteristic) in
      let char = char as! CBMutableCharacteristic
      var c = [
        "uuid": char.uuid.uuidString,
        "properties": char.properties.rawValue,
        "permissions": char.permissions.rawValue
      ]
      if let value = char.value {
        c["value"] = value
      }
      return c
    })
  }

  func getCharacteristic(_ uuid: String) -> CBMutableCharacteristic? {
    for (_, service) in servicesMap {
      for char in service.characteristics ?? [] {
        if (char.uuid.uuidString == uuid) {
          return char as? CBMutableCharacteristic
        }
      }
    }

    return nil
  }
}

class PeripheralManagerDelegate: NSObject, CBPeripheralManagerDelegate {
  private weak var module: ExpoBlePeripheralModule?

  var startPromise: Promise?

  init(module: ExpoBlePeripheralModule) {
    self.module = module
  }

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    module?.state = module!.getState()

    module?.sendEvent(STATE_CHANGED_EVENT_NAME, [
      "state": module?.state
    ])
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
    let services = dict[CBPeripheralManagerRestoredStateServicesKey] as! [CBMutableService]
    let advertisementData = dict[CBPeripheralManagerRestoredStateAdvertisementDataKey] as! [String: Any]
    
    for service in services {
      module?.servicesMap[service.uuid.uuidString] = service
    }
    
    module?.name = advertisementData[CBAdvertisementDataLocalNameKey] as! String
  }

  func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: (any Error)?) {
    if let error = error {
      print("didStartAdvertising: \(error)")
      startPromise?.reject(error)
    } else {
      startPromise?.resolve()
    }
    startPromise = nil
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: (any Error)?) {
    if let error = error {
      print("addService \(service): \(error)")
    } else {
      print("addService \(service)")
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
    let char = characteristic as! CBMutableCharacteristic
    print("central \(central) didSubscribeTo \(char)")
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
    let char = characteristic as! CBMutableCharacteristic
    print("central \(central) didUnsubscribeFrom \(char)")
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
    guard let char = module?.getCharacteristic(request.characteristic.uuid.uuidString) else {
      module?.manager.respond(to: request, withResult: .readNotPermitted)
      return
    }

    request.value = char.value?[request.offset...]
    module?.manager.respond(to: request, withResult: .success)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
    var chars = [(CBMutableCharacteristic, Data)]()
    for request in requests {
      guard let char = module?.getCharacteristic(request.characteristic.uuid.uuidString) else {
        module?.manager.respond(to: request, withResult: .writeNotPermitted)
        return
      }

      var value = char.value != nil ? Data(char.value!) : Data()

      if request.offset > value.count {
        module?.manager.respond(to: request, withResult: .invalidOffset)
        return
      }

      value.replaceSubrange(request.offset..., with: request.value ?? Data())

      chars.append((char, value))
    }

    var characteristics = [[String: Any]]()
    for (char, value) in chars {
      char.value = value

      characteristics.append([
        "uuid": char.uuid.uuidString,
        "properties": char.properties.rawValue,
        "permissions": char.permissions.rawValue,
        "value": value
      ])
    }

    module?.manager.respond(to: requests[0], withResult: .success)

    module?.sendEvent(CHAR_WRITTEN_EVENT_NAME, [
      "characteristics": characteristics
    ])
  }

  func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
    module?.sendEvent(NOTIFICATION_READY_EVENT_NAME)
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: (any Error)?) {
    if let error = error {
      print("didPublishL2CAPChannel: \(error)")
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didUnpublishL2CAPChannel PSM: CBL2CAPPSM, error: (any Error)?) {
    if let error = error {
      print("didUnpublishL2CAPChannel: \(error)")
    }
  }

  func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: (any Error)?) {
    if let error = error {
      print("didOpen: \(error)")
    }
  }
}
