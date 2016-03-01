package com.polidea.rxandroidble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import com.polidea.rxandroidble.exceptions.BleCannotSetCharacteristicNotificationException;
import com.polidea.rxandroidble.internal.ObservableUtil;
import com.polidea.rxandroidble.internal.RxBleConnectibleConnection;
import com.polidea.rxandroidble.internal.RxBleGattCallback;
import com.polidea.rxandroidble.internal.RxBleRadio;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationCharacteristicRead;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationCharacteristicWrite;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationConnect;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationDescriptorRead;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationDescriptorWrite;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationDisconnect;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationReadRssi;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationServicesDiscover;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import rx.Observable;

public class RxBleConnectionImpl implements RxBleConnectibleConnection {

    private final BluetoothDevice bluetoothDevice;

    private final RxBleRadio rxBleRadio;

    private final RxBleGattCallback gattCallback = new RxBleGattCallback();

    private final AtomicReference<BluetoothGatt> bluetoothGattAtomicReference = new AtomicReference<>();

    private final AtomicReference<Observable<RxBleDeviceServices>> discoveredServicesCache = new AtomicReference<>();

    private AtomicBoolean isDisconnectEnqueued = new AtomicBoolean(false);

    private final HashMap<UUID, Observable<Observable<byte[]>>> notificationObservableMap = new HashMap<>();

    public RxBleConnectionImpl(BluetoothDevice bluetoothDevice, RxBleRadio rxBleRadio) {

        this.bluetoothDevice = bluetoothDevice;
        this.rxBleRadio = rxBleRadio;
    }

    @Override
    public Observable<RxBleConnection> connect(Context context, boolean autoConnect) {
        final RxBleRadioOperationConnect operationConnect = new RxBleRadioOperationConnect(context, bluetoothDevice, gattCallback, this, autoConnect);
        final RxBleRadioOperationDisconnect operationDisconnect = new RxBleRadioOperationDisconnect(
                gattCallback,
                bluetoothGattAtomicReference,
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)
        );
        
        operationConnect.getBluetoothGatt().subscribe(bluetoothGattAtomicReference::set);
        return rxBleRadio.queue(operationConnect)
                .doOnError(throwable -> enqueueDisconnect(operationDisconnect))
                .doOnUnsubscribe(() -> enqueueDisconnect(operationDisconnect));
    }

    @Override
    public Observable<RxBleConnectionState> getConnectionState() {
        return gattCallback.getOnConnectionStateChange();
    }

    @Override
    public Observable<RxBleDeviceServices> discoverServices() {
        synchronized (discoveredServicesCache) {
            // checking if there are already cached services
            final Observable<RxBleDeviceServices> sharedObservable = this.discoveredServicesCache.get();
            if (sharedObservable != null) {
                return sharedObservable;
            }

            final BluetoothGatt bluetoothGatt = bluetoothGattAtomicReference.get();
            final List<BluetoothGattService> services = bluetoothGatt.getServices();
            final Observable<RxBleDeviceServices> newObservable;
            if (services.size() > 0) { // checking if bluetoothGatt has already discovered services (internal cache?)
                newObservable = Observable.just(new RxBleDeviceServices(services));
            } else { // performing actual discovery
                newObservable = rxBleRadio.queue(new RxBleRadioOperationServicesDiscover(gattCallback, bluetoothGatt)).cache(1);
            }

            discoveredServicesCache.set(newObservable);
            return newObservable;
        }
    }

    private Observable<BluetoothGattCharacteristic> getCharacteristic(UUID characteristicUuid) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getCharacteristic(characteristicUuid));
    }

    @Override
    public Observable<Observable<byte[]>> getNotification(UUID characteristicUuid) {
        synchronized (notificationObservableMap) {
            final Observable<Observable<byte[]>> availableObservable = notificationObservableMap.get(characteristicUuid);

            if (availableObservable != null) {
                return availableObservable;
            }

            final Observable<Observable<byte[]>> newObservable = createCharacteristicNotificationObservable(characteristicUuid);
            notificationObservableMap.put(characteristicUuid, newObservable);

            return newObservable;
        }
    }

    private Observable<Observable<byte[]>> createCharacteristicNotificationObservable(UUID characteristicUuid) {
        return getClientConfigurationDescriptor(characteristicUuid)
                .flatMap(bluetoothGattDescriptor -> {
                    final BluetoothGattCharacteristic bluetoothGattCharacteristic = bluetoothGattDescriptor.getCharacteristic();
                    final boolean success = bluetoothGattAtomicReference.get().setCharacteristicNotification(bluetoothGattCharacteristic, true);
                    if (!success) {
                        return Observable.error(new BleCannotSetCharacteristicNotificationException(bluetoothGattCharacteristic));
                    }
                    return writeDescriptor(bluetoothGattDescriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                })
                .flatMap(bluetoothGattDescriptorPair -> ObservableUtil.justOnNext(bluetoothGattDescriptorPair))
                .map(bluetoothGattDescriptorPair ->
                                gattCallback.getOnCharacteristicChanged()
                                        .filter(uuidPair -> uuidPair.first.equals(characteristicUuid))
                                        .map(uuidPair -> uuidPair.second)
                )
                .doOnUnsubscribe(() -> {
                                synchronized (notificationObservableMap) {
                                    notificationObservableMap.remove(characteristicUuid);
                                }
                                getClientConfigurationDescriptor(characteristicUuid)
                                        .flatMap(bluetoothGattDescriptor -> {
                                            bluetoothGattAtomicReference.get()
                                                    .setCharacteristicNotification(bluetoothGattDescriptor.getCharacteristic(), false);
                                            return writeDescriptor(bluetoothGattDescriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                                        })
                                        .subscribe(
                                                ignored -> {},
                                                ignored -> {}
                                        );
                        }
                )
                .cache(1)
                .share();
    }

    @NonNull
    private Observable<BluetoothGattDescriptor> getClientConfigurationDescriptor(UUID characteristicUuid) {
        return getCharacteristic(characteristicUuid)
                .map(bluetoothGattCharacteristic -> bluetoothGattCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")));
    }

    @Override
    public Observable<byte[]> readCharacteristic(UUID characteristicUuid) {
        return getCharacteristic(characteristicUuid)
                .flatMap(bluetoothGattCharacteristic -> {
                    final RxBleRadioOperationCharacteristicRead operationCharacteristicRead =
                            new RxBleRadioOperationCharacteristicRead(
                                    gattCallback,
                                    bluetoothGattAtomicReference.get(),
                                    bluetoothGattCharacteristic
                            );
                    return rxBleRadio.queue(operationCharacteristicRead);
                });
    }

    @Override
    public Observable<byte[]> writeCharacteristic(UUID characteristicUuid, byte[] data) {
        return getCharacteristic(characteristicUuid)
                .flatMap(bluetoothGattCharacteristic -> {
                    final RxBleRadioOperationCharacteristicWrite operationCharacteristicWrite = new RxBleRadioOperationCharacteristicWrite(
                            gattCallback,
                            bluetoothGattAtomicReference.get(),
                            bluetoothGattCharacteristic,
                            data
                    );

                    return rxBleRadio.queue(operationCharacteristicWrite);
                });
    }

    @Override
    public Observable<byte[]> readDescriptor(UUID serviceUuid, UUID characteristicUuid, UUID descriptorUuid) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid))
                .flatMap(this::readDescriptor)
                .map(bluetoothGattDescriptorPair -> bluetoothGattDescriptorPair.second);
    }

    private Observable<Pair<BluetoothGattDescriptor, byte[]>> readDescriptor(BluetoothGattDescriptor bluetoothGattDescriptor) {
        return rxBleRadio.queue(
                new RxBleRadioOperationDescriptorRead(gattCallback, bluetoothGattAtomicReference.get(), bluetoothGattDescriptor)
        );
    }

    @Override
    public Observable<byte[]> writeDescriptor(UUID serviceUuid, UUID characteristicUuid, UUID descriptorUuid, byte[] data) {
        return discoverServices()
                .flatMap(rxBleDeviceServices -> rxBleDeviceServices.getDescriptor(serviceUuid, characteristicUuid, descriptorUuid))
                .flatMap(bluetoothGattDescriptor -> writeDescriptor(bluetoothGattDescriptor, data))
                .map(bluetoothGattDescriptorPair -> bluetoothGattDescriptorPair.second);
    }

    private Observable<Pair<BluetoothGattDescriptor, byte[]>> writeDescriptor(BluetoothGattDescriptor bluetoothGattDescriptor, byte[] data) {
        return rxBleRadio.queue(
                new RxBleRadioOperationDescriptorWrite(gattCallback, bluetoothGattAtomicReference.get(), bluetoothGattDescriptor, data)
        );
    }

    @Override
    public Observable<Integer> readRssi() {
        return rxBleRadio.queue(new RxBleRadioOperationReadRssi(gattCallback, bluetoothGattAtomicReference.get()));
    }

    private void enqueueDisconnect(RxBleRadioOperationDisconnect operationDisconnect) {

        if (isDisconnectEnqueued.compareAndSet(false, true)) {
            rxBleRadio.queue(operationDisconnect)
                    .subscribe(ignored -> {
                    }, ignored -> {
                    });
        }
    }
}
