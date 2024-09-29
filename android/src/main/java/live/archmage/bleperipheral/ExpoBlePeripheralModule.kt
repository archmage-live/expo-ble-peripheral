package live.archmage.bleperipheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_SECONDARY
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.bluetooth.le.TransportBlock
import android.bluetooth.le.TransportDiscoveryData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import expo.modules.interfaces.permissions.Permissions.askForPermissionsWithPermissionsManager
import expo.modules.interfaces.permissions.Permissions.getPermissionsWithPermissionsManager
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.UUID

@JvmRecord
data class AddCharacteristicArgs(
  val uuid: String,
  val properties: Int,
  val permissions: Int,
  val value: ByteArray? = null,
)

@JvmRecord
data class UpdateCharacteristicArgs(
  val uuid: String,
  val value: ByteArray,
)

@JvmRecord
data class AdvertiseDataArgs(
  // val serviceUuids: List<String>? = null,
  val serviceSolicitationUuids: List<String>? = null,
  val transportDiscoveryData: List<TransportDiscoveryDataArgs>? = null,
  val manufacturerSpecificData: Map<Int, ByteArray>? = null,
  val serviceData: Map<String, ByteArray>? = null,
  val includeTxPowerLevel: Boolean? = null,
  val includeDeviceName: Boolean? = null,
)

@JvmRecord
data class TransportBlockArgs(
  val orgId: Int = 0,
  val tdsFlags: Int = 0,
  val transportDataLength: Int = 0,
  val transportData: ByteArray? = null,
)

@JvmRecord
data class TransportDiscoveryDataArgs(
  val transportDataType: Int = 0,
  val transportBlocks: List<TransportBlockArgs> = ArrayList<TransportBlockArgs>(),
)


@JvmRecord
data class PeriodicAdvertisingParametersArgs(
  val includeTxPower: Boolean? = null,
  val interval: Int? = null,
)

@JvmRecord
data class AdvertisingSetParametersArgs(
  val isLegacy: Boolean? = null,
  val isAnonymous: Boolean? = null,
  val includeTxPower: Boolean? = null,
  val primaryPhy: Int? = null,
  val secondaryPhy: Int? = null,
  val connectable: Boolean? = null,
  val discoverable: Boolean? = null,
  val scannable: Boolean? = null,
  val interval: Int? = null,
  val txPowerLevel: Int? = null,
  // val ownAddressType: Int? = null,
)

@JvmRecord
data class AdvertiseSettingsArgs(
  val advertiseMode: Int? = null,
  val advertiseTxPowerLevel: Int? = null,
  val advertiseTimeoutMillis: Int? = null,
  val advertiseConnectable: Boolean? = null,
  val advertiseDiscoverable: Boolean? = null,
  // val ownAddressType: Int? = null,
)

@JvmRecord
data class StartArgs(
  val preferAdvertisingSet: Boolean = true,
  // startAdvertising & startAdvertisingSet
  val advertiseData: AdvertiseDataArgs? = null,
  // startAdvertising
  val settings: AdvertiseSettingsArgs? = null,
  // startAdvertisingSet
  val parameters: AdvertisingSetParametersArgs? = null,
  val periodicParameters: PeriodicAdvertisingParametersArgs? = null,
  val duration: Int? = null,
  val maxExtendedAdvertisingEvents: Int? = null,
)

class ExpoBlePeripheralModule : Module() {
  private lateinit var context: Context
  private val currentActivity
    get() = appContext.activityProvider?.currentActivity ?: throw Exceptions.MissingActivity()

  // Each module class must implement the definition function. The definition consists of components
  // that describes the module's functionality and behavior.
  // See https://docs.expo.dev/modules/module-api for more details about available components.
  override fun definition() = ModuleDefinition {
    context = appContext.reactContext ?: throw Exceptions.ReactContextLost()
    bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    // Sets the name of the module that JavaScript code will use to refer to the module. Takes a string as an argument.
    // Can be inferred from module's class name, but it's recommended to set it explicitly for clarity.
    // The module will be accessible from `requireNativeModule('ExpoBlePeripheral')` in JavaScript.
    Name("ExpoBlePeripheral")

    // Sets constant properties on the module. Can take a dictionary or a closure that returns a dictionary.
    Constants(
      "PI" to Math.PI
    )

    // Defines event names that the module can send to JavaScript.
    Events("onChange")

    // Defines a JavaScript synchronous function that runs the native code on the JavaScript thread.
    Function("hello") {
      "Hello world! 👋"
    }

    // Defines a JavaScript function that always returns a Promise and whose native code
    // is by default dispatched on the different thread than the JavaScript runtime runs on.
    AsyncFunction("setValueAsync") { value: String ->
      // Send an event to JavaScript.
      sendEvent(
        "onChange", mapOf(
          "value" to value
        )
      )
    }

    Function("isSupported") {
      context.packageManager!!.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    Function("isAdvertising") {
      advertising
    }

    Function("addService") { uuid: String ->
      val service = BluetoothGattService(UUID.fromString(uuid), SERVICE_TYPE_PRIMARY)
      servicesMap.put(uuid, service)
    }

    Function("addSecondaryService") { uuid: String, parentUuid: String ->
      val parentService = servicesMap.get(parentUuid)
      requireNotNull(parentService) { "Parent service not found" }
      val service = BluetoothGattService(UUID.fromString(uuid), SERVICE_TYPE_SECONDARY)
      parentService.addService(service)
      parentService
    }

    Function("addCharacteristic") { args: AddCharacteristicArgs, serviceUuid: String, parentServiceUuid: String? ->
      val service = if (parentServiceUuid == null) {
        servicesMap.get(serviceUuid)
      } else {
        servicesMap.get(parentServiceUuid)?.includedServices!!.find {
          it.uuid == UUID.fromString(
            serviceUuid
          )
        }
      }
      requireNotNull(service) { "Service not found" }

      val characteristic =
        BluetoothGattCharacteristic(UUID.fromString(args.uuid), args.properties, args.permissions)
      args.value?.let {
        characteristic.setValue(it)
      }
      service.addCharacteristic(characteristic)
    }

    Function("updateCharacteristic") { args: UpdateCharacteristicArgs, serviceUuid: String, parentServiceUuid: String? ->
      val service = if (parentServiceUuid == null) {
        servicesMap.get(serviceUuid)
      } else {
        servicesMap.get(parentServiceUuid)?.includedServices!!.find {
          it.uuid == UUID.fromString(
            serviceUuid
          )
        }
      }
      requireNotNull(service) { "Service not found" }

      val characteristic = service.characteristics.find { it.uuid == UUID.fromString(args.uuid) }
      requireNotNull(characteristic) { "Characteristic not found" }
      characteristic.setValue(args.value)
    }

    Function("notifyCharacteristicChanged") { uuid: String, serviceUuid: String, parentServiceUuid: String? ->
      val service = if (parentServiceUuid == null) {
        servicesMap.get(serviceUuid)
      } else {
        servicesMap.get(parentServiceUuid)?.includedServices!!.find {
          it.uuid == UUID.fromString(
            serviceUuid
          )
        }
      }
      requireNotNull(service) { "Service not found" }

      val characteristic = service.characteristics.find { it.uuid == UUID.fromString(uuid) }
      requireNotNull(characteristic) { "Characteristic not found" }

      val indicate =
        ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE)

      if (!appContext.permissions!!.hasGrantedPermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
        throw Exceptions.MissingPermissions(Manifest.permission.BLUETOOTH_CONNECT)
      }

      for (device in devices) {
        @Suppress("MissingPermission")
        gattServer?.notifyCharacteristicChanged(
          device, characteristic, indicate
        )
      }
    }

    AsyncFunction("start") { name: String, args: StartArgs, promise: Promise ->
      if (!appContext.permissions!!.hasGrantedPermissions(
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.BLUETOOTH_ADVERTISE,
        )
      ) {
        promise.reject(Exceptions.MissingPermissions(Manifest.permission.BLUETOOTH_CONNECT))
        return@AsyncFunction
      }

      @Suppress("MissingPermission")
      bluetoothManager.adapter.setName(name)

      @Suppress("MissingPermission")
      gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
      for (service in servicesMap.values) {
        @Suppress("MissingPermission")
        gattServer!!.addService(service)
      }

      val advertiseDataBuilder = AdvertiseData.Builder()
      for (service in servicesMap.values) {
        advertiseDataBuilder.addServiceUuid(ParcelUuid(service.uuid))
      }
      args.advertiseData?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          it.serviceSolicitationUuids?.forEach { uuid ->
            advertiseDataBuilder.addServiceSolicitationUuid(ParcelUuid(UUID.fromString(uuid)))
          }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          it.serviceData?.forEach { (uuid, data) ->
            advertiseDataBuilder.addServiceData(ParcelUuid(UUID.fromString(uuid)), data)
          }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          it.transportDiscoveryData?.forEach { data ->
            advertiseDataBuilder.addTransportDiscoveryData(
              TransportDiscoveryData(data.transportDataType, data.transportBlocks.map { block ->
                TransportBlock(
                  block.orgId, block.tdsFlags, block.transportDataLength, block.transportData
                )
              })
            )
          }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          it.manufacturerSpecificData?.forEach { (id, data) ->
            advertiseDataBuilder.addManufacturerData(id, data)
          }
        }
        advertiseDataBuilder.setIncludeTxPowerLevel(it.includeTxPowerLevel ?: false)
        advertiseDataBuilder.setIncludeDeviceName(it.includeDeviceName ?: false)
      }
      val advertiseData = advertiseDataBuilder.build()

      val advertiser = bluetoothManager.adapter.getBluetoothLeAdvertiser()

      if (args.preferAdvertisingSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val parametersBuilder = AdvertisingSetParameters.Builder()
        args.parameters?.let {
          it.isLegacy?.let {
            parametersBuilder.setLegacyMode(it)
          }
          it.isAnonymous?.let {
            parametersBuilder.setAnonymous(it)
          }
          it.includeTxPower?.let {
            parametersBuilder.setIncludeTxPower(it)
          }
          it.primaryPhy?.let {
            parametersBuilder.setPrimaryPhy(it)
          }
          it.secondaryPhy?.let {
            parametersBuilder.setSecondaryPhy(it)
          }
          it.connectable?.let {
            parametersBuilder.setConnectable(it)
          }
          it.discoverable?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
              parametersBuilder.setDiscoverable(it)
            }
          }
          it.scannable?.let {
            parametersBuilder.setScannable(it)
          }
          it.interval?.let {
            parametersBuilder.setInterval(it)
          }
          it.txPowerLevel?.let {
            parametersBuilder.setTxPowerLevel(it)
          }
        }
        val parameters = parametersBuilder.build()

        val periodicParametersBuilder = PeriodicAdvertisingParameters.Builder()
        args.periodicParameters?.let {
          it.includeTxPower?.let {
            periodicParametersBuilder.setIncludeTxPower(it)
          }
          it.interval?.let {
            periodicParametersBuilder.setInterval(it)
          }
        }
        val periodicParameters = periodicParametersBuilder.build()

        advertisingSetCallback = object : AdvertisingSetCallback() {
          override fun onAdvertisingSetStarted(
            advertisingSet: AdvertisingSet?, txPower: Int, status: Int
          ) {
            super.onAdvertisingSetStarted(advertisingSet, txPower, status)
            when (status) {
              ADVERTISE_SUCCESS -> {
                advertising = true
                promise.resolve("startAdvertisingSet succeeded")
              }

              else -> {
                advertising = false
                promise.reject(CodedException("startAdvertisingSet failed: $status"))
              }
            }
          }

          override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            super.onAdvertisingSetStopped(advertisingSet)
          }

          override fun onAdvertisingEnabled(
            advertisingSet: AdvertisingSet?, enable: Boolean, status: Int
          ) {
            super.onAdvertisingEnabled(advertisingSet, enable, status)
            when (status) {
              ADVERTISE_SUCCESS -> {
                advertising = true
                promise.resolve("startAdvertisingSet succeeded")
              }

              else -> {
                advertising = false
                promise.reject(CodedException("startAdvertisingSet failed: $status"))
              }
            }
          }
        }

        @Suppress("MissingPermission")
        advertiser.startAdvertisingSet(
          parameters,
          advertiseData,
          advertiseData,
          periodicParameters,
          advertiseData,
          args.duration ?: 0,
          args.maxExtendedAdvertisingEvents ?: 0,
          advertisingSetCallback,
        )
      } else {
        val settingsBuilder = AdvertiseSettings.Builder()
        args.settings?.let {
          it.advertiseMode?.let {
            settingsBuilder.setAdvertiseMode(it)
          }
          it.advertiseTxPowerLevel?.let {
            settingsBuilder.setTxPowerLevel(it)
          }
          it.advertiseTimeoutMillis?.let {
            settingsBuilder.setTimeout(it)
          }
          it.advertiseConnectable?.let {
            settingsBuilder.setConnectable(it)
          }
          it.advertiseDiscoverable?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
              settingsBuilder.setDiscoverable(it)
            }
          }
        }
        val settings = settingsBuilder.build()

        advertisingCallback = object : AdvertiseCallback() {
          override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            advertising = true
            promise.resolve("startAdvertising succeeded")

          }

          override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            advertising = false
            promise.reject(CodedException("startAdvertising failed: $errorCode"))
          }
        }

        @Suppress("MissingPermission")
        advertiser.startAdvertising(
          settings,
          advertiseData,
          advertiseData,
          advertisingCallback,
        )
      }
    }

    @Suppress("MissingPermission")
    AsyncFunction("stop") { promise: Promise ->
      gattServer?.close()
      gattServer = null

      if (advertisingCallback != null) {
        val advertiser = bluetoothManager.adapter.getBluetoothLeAdvertiser()
        advertiser.stopAdvertising(advertisingCallback)
        advertisingCallback = null
      }
      if (advertisingSetCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val advertiser = bluetoothManager.adapter.getBluetoothLeAdvertiser()
        advertiser.stopAdvertisingSet(advertisingSetCallback)
        advertisingSetCallback = null
      }
      advertising = false

      promise.resolve()
    }
  }

  private lateinit var bluetoothManager: BluetoothManager
  private var advertising = false
  private var advertisingCallback: AdvertiseCallback? = null
  private var advertisingSetCallback: AdvertisingSetCallback? = null

  private var gattServer: BluetoothGattServer? = null
  private var servicesMap = HashMap<String, BluetoothGattService>()
  private var devices = HashSet<BluetoothDevice>()

  private val gattServerCallback = object : BluetoothGattServerCallback() {
    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
      super.onConnectionStateChange(device, status, newState)
      if (status == BluetoothGatt.GATT_SUCCESS) {
        if (newState == BluetoothGatt.STATE_CONNECTED) {
          devices.add(device)
        } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
          devices.remove(device)
        }
      } else {
        devices.remove(device)
      }
    }

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
      super.onNotificationSent(device, status)
    }

    override fun onCharacteristicReadRequest(
      device: BluetoothDevice,
      requestId: Int,
      offset: Int,
      characteristic: BluetoothGattCharacteristic
    ) {
      super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

      @Suppress("MissingPermission")
      gattServer?.sendResponse(
        device,
        requestId,
        BluetoothGatt.GATT_SUCCESS,
        offset,
        characteristic.getValue().sliceArray(offset until characteristic.getValue().size)
      )
    }

    override fun onCharacteristicWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      characteristic: BluetoothGattCharacteristic,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      super.onCharacteristicWriteRequest(
        device, requestId, characteristic, preparedWrite, responseNeeded, offset, value
      )

      val len = offset + value.size
      val value = if (len > characteristic.value.size) {
        null
      } else {
        characteristic.setValue(value.copyInto(characteristic.value.copyOf(), offset))
        value
      }
      if (responseNeeded) {
        @Suppress("MissingPermission")
        gattServer?.sendResponse(
          device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
        );
      }
    }

    override fun onDescriptorReadRequest(
      device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
    ) {
      super.onDescriptorReadRequest(device, requestId, offset, descriptor)

      @Suppress("MissingPermission")
      gattServer?.sendResponse(
        device,
        requestId,
        BluetoothGatt.GATT_SUCCESS,
        offset,
        descriptor.value.sliceArray(offset until descriptor.value.size)
      )
    }

    override fun onDescriptorWriteRequest(
      device: BluetoothDevice,
      requestId: Int,
      descriptor: BluetoothGattDescriptor,
      preparedWrite: Boolean,
      responseNeeded: Boolean,
      offset: Int,
      value: ByteArray
    ) {
      super.onDescriptorWriteRequest(
        device, requestId, descriptor, preparedWrite, responseNeeded, offset, value
      )

      val len = offset + value.size
      val value = if (len > descriptor.value.size) {
        null
      } else {
        descriptor.setValue(value.copyInto(descriptor.value.copyOf(), offset))
        value
      }
      if (responseNeeded) {
        @Suppress("MissingPermission")
        gattServer?.sendResponse(
          device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
        );
      }
    }
  }

  fun checkAndEnableBluetooth(shouldAsk: Boolean): Boolean {
    return if (bluetoothManager.adapter.isEnabled) {
      true
    } else {
      val hasPermission = requestPermission(object : Promise {
        override fun resolve(value: Any?) {
        }

        override fun reject(code: String, message: String?, cause: Throwable?) {
        }
      })
      if (hasPermission) {
        enableBluetooth(shouldAsk)
      }
      false
    }
  }

  @SuppressLint("MissingPermission")
  fun enableBluetooth(shouldAsk: Boolean) {
    if (shouldAsk) {
      currentActivity.startActivityForResult(
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
        0,
      )
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      bluetoothManager.adapter.enable()
    }
  }

  fun requestPermission(promise: Promise): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (!hasBluetoothAdvertisePermission(context) || !hasBluetoothConnectPermission(context)) {
        askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.BLUETOOTH_ADVERTISE,
          Manifest.permission.BLUETOOTH_CONNECT,
        )
        return false
      } else {
        getPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.BLUETOOTH_ADVERTISE,
          Manifest.permission.BLUETOOTH_CONNECT,
        )
        return true
      }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (!hasLocationCoarsePermission(context) || !hasLocationFinePermission(context)) {
        askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return false
      } else {
        getPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return true
      }
    } else {
      if (!hasLocationCoarsePermission(context)) {
        askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return false
      } else {
        getPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        return true
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun hasBluetoothAdvertisePermission(context: Context): Boolean {
    return context.checkSelfPermission(
      Manifest.permission.BLUETOOTH_ADVERTISE
    ) == PackageManager.PERMISSION_GRANTED
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun hasBluetoothConnectPermission(context: Context): Boolean {
    return context.checkSelfPermission(
      Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun hasLocationFinePermission(context: Context): Boolean {
    return context.checkSelfPermission(
      Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun hasLocationCoarsePermission(context: Context): Boolean {
    return context.checkSelfPermission(
      Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }
}
