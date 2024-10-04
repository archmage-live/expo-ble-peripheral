package live.archmage.bleperipheral

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.PeriodicAdvertisingParameters
import android.bluetooth.le.TransportBlock
import android.bluetooth.le.TransportDiscoveryData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.LruCache
import androidx.annotation.RequiresApi
import expo.modules.interfaces.permissions.Permissions
import expo.modules.interfaces.permissions.PermissionsResponse
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.types.Enumerable
import java.io.ByteArrayOutputStream
import java.util.UUID

enum class BleState(val value: String) : Enumerable {
  Unknown("Unknown"),
  Resetting("Resetting"),
  Unsupported("Unsupported"),
  Unauthorized("Unauthorized"),
  Off("Off"),
  On("On"),
}

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
data class TransportDiscoveryDataArgs(
  val transportDataType: Int = 0,
  val transportBlocks: List<TransportBlockArgs> = ArrayList(),
)

@JvmRecord
data class TransportBlockArgs(
  val orgId: Int = 0,
  val tdsFlags: Int = 0,
  val transportDataLength: Int = 0,
  val transportData: ByteArray? = null,
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

const val STATE_CHANGED_EVENT_NAME = "onStateChanged"
const val NOTIFICATION_READY_EVENT_NAME = "onNotificationReady"
const val CHAR_WRITTEN_EVENT_NAME = "onCharacteristicWritten"

class ExpoBlePeripheralModule : Module() {
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

    // Defines event names that the module can send to JavaScript.
    Events(
      STATE_CHANGED_EVENT_NAME,
      NOTIFICATION_READY_EVENT_NAME,
      CHAR_WRITTEN_EVENT_NAME
    )

    Property("name") {
      name
    }

    Function("setName") { name: String ->
      this@ExpoBlePeripheralModule.name = name
    }

    Function("hasPermission") {
      hasPermission()
    }

    AsyncFunction("requestPermission") { promise: Promise ->
      if (hasPermission()) {
        promise.resolve(true)
      } else {
        requestPermission(promise)
      }
    }

    AsyncFunction("enable") { promise: Promise ->
      if (bluetoothManager.adapter.isEnabled) {
        promise.resolve(true)
      } else {
        enablePromise = promise

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
          @Suppress("MissingPermission")
          if (!bluetoothManager.adapter.enable()) {
            enablePromise = null
            promise.resolve(bluetoothManager.adapter.isEnabled)
          }
        } else {
          @Suppress("MissingPermission")
          currentActivity.startActivityForResult(
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
            0,
          )
        }
      }
    }

    Property("state") {
      state
    }

    Function("isAdvertising") {
      isAdvertising
    }

    Function("getServices") {
      servicesMap.values.map { service ->
        mapOf(
          "uuid" to service.uuid.toString(),
          "isPrimary" to (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY),
          "characteristics" to service.characteristics.map { char ->
            mapOf(
              "uuid" to char.uuid.toString(),
              "properties" to char.properties,
              "permissions" to char.permissions,
              "value" to char.value,
              "descriptors" to char.descriptors.map { descriptor ->
                mapOf(
                  "uuid" to descriptor.uuid.toString(),
                  "permissions" to descriptor.permissions,
                  "value" to descriptor.value,
                )
              },
            )
          },
        )
      }
    }

    Function("addService") { uuid: String, primary: Boolean ->
      if (servicesMap.contains(uuid)) {
        throw CodedException("Service already added")
      }

      val service = BluetoothGattService(
        UUID.fromString(uuid),
        if (primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY
      )
      servicesMap.put(uuid, service)
      @Suppress("MissingPermission")
      gattServer?.addService(service)
    }

    Function("removeService") { uuid: String ->
      val service = servicesMap.remove(uuid)
      requireNotNull(service) { "Service not found" }
      @Suppress("MissingPermission")
      gattServer?.removeService(service)
    }

    Function("removeAllServices") {
      servicesMap.clear()
      @Suppress("MissingPermission")
      gattServer?.clearServices()
    }

    Function("getCharacteristics") { serviceUuid: String? ->
    }

    Function("addCharacteristic") { args: AddCharacteristicArgs, serviceUuid: String ->
      val service = servicesMap.get(serviceUuid)
      requireNotNull(service) { "Service not found" }

      val characteristic =
        BluetoothGattCharacteristic(UUID.fromString(args.uuid), args.properties, args.permissions)
      args.value?.let {
        characteristic.setValue(it)
      }
      service.addCharacteristic(characteristic)
    }

    Function("updateCharacteristic") { args: UpdateCharacteristicArgs, serviceUuid: String ->
      val service = servicesMap.get(serviceUuid)
      requireNotNull(service) { "Service not found" }

      val characteristic = service.characteristics.find { it.uuid == UUID.fromString(args.uuid) }
      requireNotNull(characteristic) { "Characteristic not found" }
      characteristic.setValue(args.value)

      val indicate =
        ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE)

      if (!appContext.permissions!!.hasGrantedPermissions(Manifest.permission.BLUETOOTH_CONNECT)) {
        throw Exceptions.MissingPermissions(Manifest.permission.BLUETOOTH_CONNECT)
      }

      for (device in devices) {
        @Suppress("MissingPermission", "NewApi")
        val success = gattServer?.notifyCharacteristicChanged(
          device, characteristic, indicate, args.value
        ) == BluetoothStatusCodes.SUCCESS

        if (!success) {
          return@Function false
        }
      }

      return@Function true
    }

    AsyncFunction("start") { args: StartArgs, promise: Promise ->
      if (state != BleState.On) {
        promise.reject(CodedException("Peripheral is not powered on, but in state `$state`"))
        return@AsyncFunction
      }

      if (isAdvertising) {
        promise.reject(CodedException("Peripheral is currently advertising"))
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
                isAdvertising = true
                promise.resolve("startAdvertisingSet succeeded")
              }

              else -> {
                isAdvertising = false
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
                isAdvertising = true
                promise.resolve("startAdvertisingSet succeeded")
              }

              else -> {
                isAdvertising = false
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
            isAdvertising = true
            promise.resolve("startAdvertising succeeded")

          }

          override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            isAdvertising = false
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
      isAdvertising = false

      devices.clear()

      promise.resolve()
    }

    OnCreate {
      state = getState()

      val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

      @Suppress("NewApi")
      context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    OnDestroy {
      context.unregisterReceiver(broadcastReceiver)
    }

    OnActivityResult { activity, onActivityResultPayload ->
      if (onActivityResultPayload.requestCode == 0) {
        if (onActivityResultPayload.resultCode == Activity.RESULT_OK) {
          enablePromise?.resolve(bluetoothManager.adapter.isEnabled)
        } else {
          enablePromise?.resolve(false)
        }

        if (enablePromise != null) {
          enablePromise = null
        }
      }
    }
  }

  private val currentActivity
    get() = appContext.activityProvider?.currentActivity ?: throw Exceptions.MissingActivity()

  private lateinit var context: Context

  private lateinit var bluetoothManager: BluetoothManager

  private var name: String = "BlePeripheral"
  private var servicesMap = HashMap<String, BluetoothGattService>()

  private var state: BleState = BleState.Unknown

  private var gattServer: BluetoothGattServer? = null

  private var isAdvertising = false
  private var advertisingCallback: AdvertiseCallback? = null
  private var advertisingSetCallback: AdvertisingSetCallback? = null

  private var devices = HashSet<BluetoothDevice>()

  private var enablePromise: Promise? = null

  private val broadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val newState = getState()
      if (state != newState) {
        state = newState
        sendEvent(
          STATE_CHANGED_EVENT_NAME, mapOf(
            "state" to state
          )
        )
      }

      enablePromise?.resolve(bluetoothManager.adapter.isEnabled)
      if (enablePromise != null) {
        enablePromise = null
      }
    }
  }

  private fun getState(): BleState {
    if (!context.packageManager!!.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
      return BleState.Unsupported
    }
    if (bluetoothManager.adapter == null) {
      return BleState.Unsupported
    }
    if (!hasPermission()) {
      return BleState.Unauthorized
    }
    when (bluetoothManager.adapter.state) {
      BluetoothAdapter.STATE_OFF -> return BleState.Off
      BluetoothAdapter.STATE_ON -> return BleState.On
      BluetoothAdapter.STATE_TURNING_OFF -> return BleState.Resetting
      BluetoothAdapter.STATE_TURNING_ON -> return BleState.Resetting
    }
    return BleState.Unknown
  }

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

      val status = if (preparedWrite) {
        val pair = preparedCharacteristicWrites.get(requestId) ?: Pair(
          characteristic,
          ByteArrayOutputStream(value.size)
        )
        val char = pair.first
        val buffer = pair.second
        if (characteristic.uuid == char.uuid && offset == buffer.size()) {
          @Suppress("NewApi")
          buffer.writeBytes(value)
          preparedCharacteristicWrites.put(requestId, pair)
          BluetoothGatt.GATT_SUCCESS
        } else {
          BluetoothGatt.GATT_INVALID_OFFSET
        }
      } else {
        if (offset <= characteristic.value.size) {
          characteristic.setValue(
            characteristic.value.sliceArray(0 until offset) + value
          )
          BluetoothGatt.GATT_SUCCESS
        } else {
          BluetoothGatt.GATT_INVALID_OFFSET
        }
      }

      if (responseNeeded) {
        @Suppress("MissingPermission")
        gattServer?.sendResponse(
          device, requestId, status, offset, value
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

      val status = if (preparedWrite) {
        val pair = preparedDescriptorWrites.get(requestId) ?: Pair(
          descriptor,
          ByteArrayOutputStream(value.size)
        )
        val desc = pair.first
        val buffer = pair.second
        if (descriptor.uuid == desc.uuid && offset == buffer.size()) {
          @Suppress("NewApi")
          buffer.writeBytes(value)
          preparedDescriptorWrites.put(requestId, pair)
          BluetoothGatt.GATT_SUCCESS
        } else {
          BluetoothGatt.GATT_INVALID_OFFSET
        }
      } else {
        if (offset <= descriptor.value.size) {
          descriptor.setValue(
            descriptor.value.sliceArray(0 until offset) + value
          )
          BluetoothGatt.GATT_SUCCESS
        } else {
          BluetoothGatt.GATT_INVALID_OFFSET
        }
      }

      if (responseNeeded) {
        @Suppress("MissingPermission")
        gattServer?.sendResponse(
          device, requestId, status, offset, value
        );
      }
    }

    val preparedCharacteristicWrites =
      LruCache<Int, Pair<BluetoothGattCharacteristic, ByteArrayOutputStream>>(16)
    val preparedDescriptorWrites =
      LruCache<Int, Pair<BluetoothGattDescriptor, ByteArrayOutputStream>>(16)

    override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) {
      super.onExecuteWrite(device, requestId, execute)

      preparedCharacteristicWrites.get(requestId)?.let {
        if (execute) {
          val char = it.first
          val buffer = it.second
          char.setValue(buffer.toByteArray())
        }
        preparedCharacteristicWrites.remove(requestId)
      }

      preparedDescriptorWrites.get(requestId)?.let {
        if (execute) {
          val desc = it.first
          val buffer = it.second
          desc.setValue(buffer.toByteArray())
        }
        preparedDescriptorWrites.remove(requestId)
      }
    }
  }

  private fun hasPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      hasBluetoothAdvertisePermission(context) && hasBluetoothConnectPermission(context)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      hasLocationCoarsePermission(context) && hasLocationFinePermission(context)
    } else {
      hasLocationCoarsePermission(context)
    }
  }

  private fun requestPermission(promise: Promise) {
    val promise = object : Promise {
      override fun resolve(value: Any?) {
        if (value !is Bundle) {
          promise.reject(CodedException("askForPermissionsWithPermissionsManager returned unexpected result: $value"))
          return
        }
        promise.resolve(value.getBoolean(PermissionsResponse.GRANTED_KEY))
      }

      override fun reject(code: String, message: String?, cause: Throwable?) {
        promise.reject(code, message, cause)
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (!hasBluetoothAdvertisePermission(context) || !hasBluetoothConnectPermission(context)) {
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.BLUETOOTH_ADVERTISE,
          Manifest.permission.BLUETOOTH_CONNECT,
        )
      }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      if (!hasLocationCoarsePermission(context) || !hasLocationFinePermission(context)) {
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      }
    } else {
      if (!hasLocationCoarsePermission(context)) {
        Permissions.askForPermissionsWithPermissionsManager(
          appContext.permissions,
          promise,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
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
