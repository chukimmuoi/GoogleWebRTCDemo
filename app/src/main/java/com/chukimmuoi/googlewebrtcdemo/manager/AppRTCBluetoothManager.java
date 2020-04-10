/*
 *  Copyright 2016 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.chukimmuoi.googlewebrtcdemo.manager;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.List;
import java.util.Set;

import com.chukimmuoi.googlewebrtcdemo.util.AppRTCUtils;

import org.webrtc.ThreadUtils;

/**
 * AppRTCProximitySensor manages functions related to Bluetoth devices in the AppRTC demo.
 * AppRTCProximitySensor manages functions related to Bluetoth devices in the
 * AppRTC demo.
 */
public class AppRTCBluetoothManager {
    private static final String TAG = "AppRTCBluetoothManager";

    // Khoảng thời gian chờ để bắt đầu hoặc dừng âm thanh với thiết bị SCO Bluetooth.
    // Timeout interval for starting or stopping audio to a Bluetooth SCO device.
    private static final int BLUETOOTH_SCO_TIMEOUT_MS = 4000;
    // Số lần thử kết nối SCO tối đa.
    // Maximum number of SCO connection attempts.
    private static final int MAX_SCO_CONNECTION_ATTEMPTS = 2;

    // Trạng thái kết nối Bluetooth.
    // Bluetooth connection state.
    public enum State {
        // Bluetooth không khả dụng; không có bộ chuyển đổi hoặc Bluetooth bị tắt.
        // Bluetooth is not available; no adapter or Bluetooth is off.
        UNINITIALIZED,
        // Lỗi Bluetooth đã xảy ra khi cố gắng khởi động Bluetooth.
        // Bluetooth error happened when trying to start Bluetooth.
        ERROR,
        // Đối tượng proxy Bluetooth cho cấu hình Tai nghe tồn tại,
        // nhưng không có thiết bị tai nghe được kết nối, SCO không được khởi động hoặc ngắt kết nối.
        // Bluetooth proxy object for the Headset profile exists, but no connected headset devices,
        // SCO is not started or disconnected.
        HEADSET_UNAVAILABLE,
        // Đối tượng proxy Bluetooth cho cấu hình Tai nghe được kết nối,
        // có tai nghe Bluetooth được kết nối, nhưng SCO không được khởi động hoặc ngắt kết nối.
        // Bluetooth proxy object for the Headset profile connected, connected Bluetooth headset
        // present, but SCO is not started or disconnected.
        HEADSET_AVAILABLE,
        // Kết nối SCO âm thanh Bluetooth với thiết bị từ xa đang đóng.
        // Bluetooth audio SCO connection with remote device is closing.
        SCO_DISCONNECTING,
        // Kết nối SCO âm thanh Bluetooth với thiết bị từ xa được bắt đầu.
        // Bluetooth audio SCO connection with remote device is initiated.
        SCO_CONNECTING,
        // Kết nối SCO âm thanh Bluetooth với thiết bị từ xa được thiết lập.
        // Bluetooth audio SCO connection with remote device is established.
        SCO_CONNECTED
    }

    private final Context apprtcContext;
    private final AppRTCAudioManager apprtcAudioManager;
    @Nullable
    private final AudioManager audioManager;
    private final Handler handler;

    int scoConnectionAttempts;
    private State bluetoothState;
    private final BluetoothProfile.ServiceListener bluetoothServiceListener;
    @Nullable
    private BluetoothAdapter bluetoothAdapter;
    @Nullable
    private BluetoothHeadset bluetoothHeadset;
    @Nullable
    private BluetoothDevice bluetoothDevice;
    private final BroadcastReceiver bluetoothHeadsetReceiver;

    // Chạy khi hết thời gian Bluetooth.
    // Chúng tôi sử dụng thời gian chờ đó sau khi gọi startScoAudio() hoặc stopScoAudio()
    // vì chúng tôi không đảm bảo nhận được cuộc gọi lại sau những cuộc gọi đó.
    // Runs when the Bluetooth timeout expires. We use that timeout after calling
    // startScoAudio() or stopScoAudio() because we're not guaranteed to get a
    // callback after those calls.
    private final Runnable bluetoothTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            bluetoothTimeout();
        }
    };

    /**
     * Triển khai giao diện thông báo cho các máy khách BluetoothProfile IPC
     * khi chúng được kết nối hoặc ngắt kết nối với dịch vụ.
     * Implementation of an interface that notifies BluetoothProfile IPC clients when they have been
     * connected to or disconnected from the service.
     */
    private class BluetoothServiceListener implements BluetoothProfile.ServiceListener {
        @Override
        // Được gọi để thông báo cho khách hàng khi đối tượng proxy đã được kết nối với dịch vụ.
        // Khi chúng ta có đối tượng proxy hồ sơ, chúng ta có thể sử dụng nó để theo dõi trạng thái của
        // kết nối và thực hiện các hoạt động khác có liên quan đến cấu hình tai nghe.
        // Called to notify the client when the proxy object has been connected to the service.
        // Once we have the profile proxy object, we can use it to monitor the state of the
        // connection and perform other operations that are relevant to the headset profile.
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }
            Log.d(TAG, "BluetoothServiceListener.onServiceConnected: BT state=" + bluetoothState);
            // Android chỉ hỗ trợ một Tai nghe Bluetooth được kết nối tại một thời điểm.
            // Android only supports one connected Bluetooth Headset at a time.
            bluetoothHeadset = (BluetoothHeadset) proxy;
            updateAudioDeviceState();
            Log.d(TAG, "onServiceConnected done: BT state=" + bluetoothState);
        }

        @Override
        /**
         * Thông báo cho khách hàng khi đối tượng proxy đã bị ngắt kết nối khỏi dịch vụ.
         * Notifies the client when the proxy object has been disconnected from the service.
         * */
        public void onServiceDisconnected(int profile) {
            if (profile != BluetoothProfile.HEADSET || bluetoothState == State.UNINITIALIZED) {
                return;
            }
            Log.d(TAG, "BluetoothServiceListener.onServiceDisconnected: BT state=" + bluetoothState);
            stopScoAudio();
            bluetoothHeadset = null;
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
            updateAudioDeviceState();
            Log.d(TAG, "onServiceDisconnected done: BT state=" + bluetoothState);
        }
    }

    // Bộ thu phát sóng có ý định xử lý các thay đổi về tính khả dụng của thiết bị Bluetooth.
    // Phát hiện thay đổi tai nghe và thay đổi trạng thái Bluetooth SCO.
    // Intent broadcast receiver which handles changes in Bluetooth device availability.
    // Detects headset changes and Bluetooth SCO state changes.
    private class BluetoothHeadsetBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (bluetoothState == State.UNINITIALIZED) {
                return;
            }
            final String action = intent.getAction();
            // Thay đổi trạng thái kết nối của cấu hình Tai nghe. Lưu ý rằng
            // thay đổi không cho chúng tôi biết bất cứ điều gì về việc chúng tôi đang phát trực tuyến
            // âm thanh để BT qua SCO. Thường nhận được khi người dùng bật BT
            // tai nghe trong khi âm thanh được kích hoạt bằng thiết bị âm thanh khác.
            // Change in connection state of the Headset profile. Note that the
            // change does not tell us anything about whether we're streaming
            // audio to BT over SCO. Typically received when user turns on a BT
            // headset while audio is active using another audio device.
            if (action.equals(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)) {
                final int state =
                        intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED);
                Log.d(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: "
                        + "a=ACTION_CONNECTION_STATE_CHANGED, "
                        + "s=" + stateToString(state) + ", "
                        + "sb=" + isInitialStickyBroadcast() + ", "
                        + "BT state: " + bluetoothState);
                if (state == BluetoothHeadset.STATE_CONNECTED) {
                    scoConnectionAttempts = 0;
                    updateAudioDeviceState();
                } else if (state == BluetoothHeadset.STATE_CONNECTING) {
                    // Không cần làm gì cả.
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTING) {
                    // Không cần làm gì cả.
                    // No action needed.
                } else if (state == BluetoothHeadset.STATE_DISCONNECTED) {
                    // Bluetooth có thể bị tắt trong khi gọi.
                    // Bluetooth is probably powered off during the call.
                    stopScoAudio();
                    updateAudioDeviceState();
                }
                // Thay đổi trạng thái kết nối âm thanh (SCO) của cấu hình Tai nghe.
                // Thường nhận được sau khi gọi đến startScoAudio() đã hoàn tất.
                // Change in the audio (SCO) connection state of the Headset profile.
                // Typically received after call to startScoAudio() has finalized.
            } else if (action.equals(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)) {
                final int state = intent.getIntExtra(
                        BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                Log.d(TAG, "BluetoothHeadsetBroadcastReceiver.onReceive: "
                        + "a=ACTION_AUDIO_STATE_CHANGED, "
                        + "s=" + stateToString(state) + ", "
                        + "sb=" + isInitialStickyBroadcast() + ", "
                        + "BT state: " + bluetoothState);
                if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                    cancelTimer();
                    if (bluetoothState == State.SCO_CONNECTING) {
                        Log.d(TAG, "+++ Bluetooth audio SCO is now connected");
                        bluetoothState = State.SCO_CONNECTED;
                        scoConnectionAttempts = 0;
                        updateAudioDeviceState();
                    } else {
                        Log.w(TAG, "Unexpected state BluetoothHeadset.STATE_AUDIO_CONNECTED");
                    }
                } else if (state == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                    Log.d(TAG, "+++ Bluetooth audio SCO is now connecting...");
                } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    Log.d(TAG, "+++ Bluetooth audio SCO is now disconnected");
                    if (isInitialStickyBroadcast()) {
                        Log.d(TAG, "Ignore STATE_AUDIO_DISCONNECTED initial sticky broadcast.");
                        return;
                    }
                    updateAudioDeviceState();
                }
            }
            Log.d(TAG, "onReceive done: BT state=" + bluetoothState);
        }
    }

    /**
     * Construction.
     */
    static AppRTCBluetoothManager create(Context context, AppRTCAudioManager audioManager) {
        Log.d(TAG, "create" + AppRTCUtils.getThreadInfo());
        return new AppRTCBluetoothManager(context, audioManager);
    }

    protected AppRTCBluetoothManager(Context context, AppRTCAudioManager audioManager) {
        Log.d(TAG, "ctor");
        ThreadUtils.checkIsOnMainThread();
        apprtcContext = context;
        apprtcAudioManager = audioManager;
        this.audioManager = getAudioManager(context);
        bluetoothState = State.UNINITIALIZED;
        bluetoothServiceListener = new BluetoothServiceListener();
        bluetoothHeadsetReceiver = new BluetoothHeadsetBroadcastReceiver();
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Trả về trạng thái bên trong.
     * Returns the internal state.
     */
    public State getState() {
        ThreadUtils.checkIsOnMainThread();
        return bluetoothState;
    }

    /**
     * Kích hoạt các thành phần cần thiết để phát hiện thiết bị Bluetooth và bật
     * BT SCO (âm thanh được định tuyến qua BT SCO) cho cấu hình tai nghe. Kết thúc
     * trạng thái sẽ là HEADSET_UNAVAILABLE nhưng một máy trạng thái đã bắt đầu
     * sẽ bắt đầu một chuỗi thay đổi trạng thái trong đó kết quả cuối cùng phụ thuộc vào
     * nếu / khi tai nghe BT được bật.
     * Ví dụ về chuỗi thay đổi trạng thái khi start() được gọi trong khi thiết bị BT
     * được kết nối và kích hoạt:
     * KHÔNG GIỚI HẠN -> HEADSET_UNAVAILABLE -> HEADSET_AVAILABLE ->
     * SCO_CONNECTING -> SCO_CONNECTED <==> âm thanh hiện được định tuyến qua BT SCO.
     * Lưu ý rằng AppRTCAudioManager cũng tham gia vào việc điều khiển trạng thái này
     * thay đổi.
     * Activates components required to detect Bluetooth devices and to enable
     * BT SCO (audio is routed via BT SCO) for the headset profile. The end
     * state will be HEADSET_UNAVAILABLE but a state machine has started which
     * will start a state change sequence where the final outcome depends on
     * if/when the BT headset is enabled.
     * Example of state change sequence when start() is called while BT device
     * is connected and enabled:
     * UNINITIALIZED --> HEADSET_UNAVAILABLE --> HEADSET_AVAILABLE -->
     * SCO_CONNECTING --> SCO_CONNECTED <==> audio is now routed via BT SCO.
     * Note that the AppRTCAudioManager is also involved in driving this state
     * change.
     */
    public void start() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "start");
        if (!hasPermission(apprtcContext, android.Manifest.permission.BLUETOOTH)) {
            Log.w(TAG, "Process (pid=" + Process.myPid() + ") lacks BLUETOOTH permission");
            return;
        }
        if (bluetoothState != State.UNINITIALIZED) {
            Log.w(TAG, "Invalid BT state");
            return;
        }
        bluetoothHeadset = null;
        bluetoothDevice = null;
        scoConnectionAttempts = 0;
        // Nhận một tay cầm cho bộ điều hợp Bluetooth cục bộ mặc định.
        // Get a handle to the default local Bluetooth adapter.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Device does not support Bluetooth");
            return;
        }
        // Đảm bảo rằng thiết bị hỗ trợ sử dụng âm thanh BT SCO cho các trường hợp sử dụng cuộc gọi tắt.
        // Ensure that the device supports use of BT SCO audio for off call use cases.
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            Log.e(TAG, "Bluetooth SCO audio is not available off call");
            return;
        }
        logBluetoothAdapterInfo(bluetoothAdapter);
        // Thiết lập kết nối đến cấu hình HEADSET
        // (bao gồm cả đối tượng proxy Tai nghe Bluetooth và Rảnh tay) và cài đặt trình nghe.
        // Establish a connection to the HEADSET profile (includes both Bluetooth Headset and
        // Hands-Free) proxy object and install a listener.
        if (!getBluetoothProfileProxy(
                apprtcContext, bluetoothServiceListener, BluetoothProfile.HEADSET)) {
            Log.e(TAG, "BluetoothAdapter.getProfileProxy(HEADSET) failed");
            return;
        }
        // Đăng ký người nhận cho thông báo thay đổi BluetoothHeadset.
        // Register receivers for BluetoothHeadset change notifications.
        IntentFilter bluetoothHeadsetFilter = new IntentFilter();
        // Đăng ký người nhận để thay đổi trạng thái kết nối của cấu hình Tai nghe.
        // Register receiver for change in connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        // Đăng ký người nhận để thay đổi trạng thái kết nối âm thanh của cấu hình Tai nghe.
        // Register receiver for change in audio connection state of the Headset profile.
        bluetoothHeadsetFilter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        registerReceiver(bluetoothHeadsetReceiver, bluetoothHeadsetFilter);
        Log.d(TAG, "HEADSET profile state: "
                + stateToString(bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)));
        Log.d(TAG, "Bluetooth proxy for headset profile has started");
        bluetoothState = State.HEADSET_UNAVAILABLE;
        Log.d(TAG, "start done: BT state=" + bluetoothState);
    }

    /**
     * Dừng và đóng tất cả các thành phần liên quan đến âm thanh Bluetooth.
     * Stops and closes all components related to Bluetooth audio.
     */
    public void stop() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "stop: BT state=" + bluetoothState);
        if (bluetoothAdapter == null) {
            return;
        }
        // Dừng kết nối BT SCO với thiết bị từ xa nếu cần.
        // Stop BT SCO connection with remote device if needed.
        stopScoAudio();
        // Đóng các tài nguyên BT còn lại.
        // Close down remaining BT resources.
        if (bluetoothState == State.UNINITIALIZED) {
            return;
        }
        unregisterReceiver(bluetoothHeadsetReceiver);
        cancelTimer();
        if (bluetoothHeadset != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            bluetoothHeadset = null;
        }
        bluetoothAdapter = null;
        bluetoothDevice = null;
        bluetoothState = State.UNINITIALIZED;
        Log.d(TAG, "stop done: BT state=" + bluetoothState);
    }

    /**
     * Bắt đầu kết nối Bluetooth SCO với thiết bị từ xa.
     * Lưu ý rằng ứng dụng điện thoại luôn được ưu tiên sử dụng kết nối SCO
     * cho điện thoại. Nếu phương thức này được gọi trong khi điện thoại đang gọi thì nó sẽ bị bỏ qua.
     * Tương tự, nếu một cuộc gọi được nhận hoặc gửi trong khi một ứng dụng đang sử dụng kết nối SCO,
     * kết nối sẽ bị mất cho ứng dụng và KHÔNG được trả lại tự động khi có cuộc gọi
     * kết thúc. Cũng lưu ý rằng: tối đa và bao gồm phiên bản API JELLY_BESE_MR1, phương thức này khởi tạo một
     * cuộc gọi thoại ảo đến tai nghe Bluetooth. Sau phiên bản API JELLY_BESE_MR2 chỉ còn một SCO thô
     * kết nối âm thanh được thiết lập.
     * TODO (henrika): chúng ta có nên thêm hỗ trợ cho cuộc gọi thoại ảo vào tai nghe BT cho JBMR2 và
     * cao hơn. Có thể phải bắt đầu một cuộc gọi thoại ảo vì nhiều thiết bị không
     * chấp nhận âm thanh SCO mà không có "cuộc gọi".
     * Starts Bluetooth SCO connection with remote device.
     * Note that the phone application always has the priority on the usage of the SCO connection
     * for telephony. If this method is called while the phone is in call it will be ignored.
     * Similarly, if a call is received or sent while an application is using the SCO connection,
     * the connection will be lost for the application and NOT returned automatically when the call
     * ends. Also note that: up to and including API version JELLY_BEAN_MR1, this method initiates a
     * virtual voice call to the Bluetooth headset. After API version JELLY_BEAN_MR2 only a raw SCO
     * audio connection is established.
     * TODO(henrika): should we add support for virtual voice call to BT headset also for JBMR2 and
     * higher. It might be required to initiates a virtual voice call since many devices do not
     * accept SCO audio without a "call".
     */
    public boolean startScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "startSco: BT state=" + bluetoothState + ", "
                + "attempts: " + scoConnectionAttempts + ", "
                + "SCO is on: " + isScoOn());
        if (scoConnectionAttempts >= MAX_SCO_CONNECTION_ATTEMPTS) {
            Log.e(TAG, "BT SCO connection fails - no more attempts");
            return false;
        }
        if (bluetoothState != State.HEADSET_AVAILABLE) {
            Log.e(TAG, "BT SCO connection fails - no headset available");
            return false;
        }
        // Bắt đầu kênh BT SCO và đợi ACTION_AUDIO_STATE_CHANGED.
        // Start BT SCO channel and wait for ACTION_AUDIO_STATE_CHANGED.
        Log.d(TAG, "Starting Bluetooth SCO and waits for ACTION_AUDIO_STATE_CHANGED...");
        // Thiết lập kết nối SCO có thể mất vài giây, do đó chúng tôi không thể dựa vào
        // kết nối khả dụng khi phương thức trả về nhưng thay vào đó hãy đăng ký để nhận
        // ý định ACTION_SCO_AUDIO_STATE_UPDATED và chờ trạng thái là SCO_AUDIO_STATE_CONNECTED.
        // The SCO connection establishment can take several seconds, hence we cannot rely on the
        // connection to be available when the method returns but instead register to receive the
        // intent ACTION_SCO_AUDIO_STATE_UPDATED and wait for the state to be SCO_AUDIO_STATE_CONNECTED.
        bluetoothState = State.SCO_CONNECTING;
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);
        scoConnectionAttempts++;
        startTimer();
        Log.d(TAG, "startScoAudio done: BT state=" + bluetoothState + ", "
                + "SCO is on: " + isScoOn());
        return true;
    }

    /**
     * Dừng kết nối Bluetooth SCO với thiết bị từ xa.
     * Stops Bluetooth SCO connection with remote device.
     */
    public void stopScoAudio() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "stopScoAudio: BT state=" + bluetoothState + ", "
                + "SCO is on: " + isScoOn());
        if (bluetoothState != State.SCO_CONNECTING && bluetoothState != State.SCO_CONNECTED) {
            return;
        }
        cancelTimer();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);
        bluetoothState = State.SCO_DISCONNECTING;
        Log.d(TAG, "stopScoAudio done: BT state=" + bluetoothState + ", "
                + "SCO is on: " + isScoOn());
    }

    /**
     * Sử dụng đối tượng proxy BluetoothHeadset (điều khiển Tai nghe Bluetooth
     * Dịch vụ qua IPC) để cập nhật danh sách các thiết bị được kết nối cho HEADSET
     * Hồ sơ. Trạng thái bên trong sẽ thay đổi thành HEADSET_UNAVAILABLE hoặc thành
     * HEADSET_AVAILABLE và | bluetoothDevice | sẽ được ánh xạ tới kết nối
     * thiết bị nếu có.
     * Use the BluetoothHeadset proxy object (controls the Bluetooth Headset
     * Service via IPC) to update the list of connected devices for the HEADSET
     * profile. The internal state will change to HEADSET_UNAVAILABLE or to
     * HEADSET_AVAILABLE and |bluetoothDevice| will be mapped to the connected
     * device if available.
     */
    public void updateDevice() {
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        Log.d(TAG, "updateDevice");
        // Nhận thiết bị được kết nối cho cấu hình tai nghe. Trả về bộ
        // các thiết bị ở trạng thái STATE_CONNECTED. Lớp BluetoothDevice
        // chỉ là một trình bao bọc mỏng cho địa chỉ phần cứng Bluetooth.
        // Get connected devices for the headset profile. Returns the set of
        // devices which are in state STATE_CONNECTED. The BluetoothDevice class
        // is just a thin wrapper for a Bluetooth hardware address.
        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
        if (devices.isEmpty()) {
            bluetoothDevice = null;
            bluetoothState = State.HEADSET_UNAVAILABLE;
            Log.d(TAG, "No connected bluetooth headset");
        } else {
            // Luôn sử dụng thiết bị đầu tiên trong danh sách. Android chỉ hỗ trợ một thiết bị.
            // Always use first device in list. Android only supports one device.
            bluetoothDevice = devices.get(0);
            bluetoothState = State.HEADSET_AVAILABLE;
            Log.d(TAG, "Connected bluetooth headset: "
                    + "name=" + bluetoothDevice.getName() + ", "
                    + "state=" + stateToString(bluetoothHeadset.getConnectionState(bluetoothDevice))
                    + ", SCO audio=" + bluetoothHeadset.isAudioConnected(bluetoothDevice));
        }
        Log.d(TAG, "updateDevice done: BT state=" + bluetoothState);
    }

    /**
     * Stubs for test mocks.
     */
    @Nullable
    protected AudioManager getAudioManager(Context context) {
        return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    protected void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        apprtcContext.registerReceiver(receiver, filter);
    }

    protected void unregisterReceiver(BroadcastReceiver receiver) {
        apprtcContext.unregisterReceiver(receiver);
    }

    protected boolean getBluetoothProfileProxy(
            Context context, BluetoothProfile.ServiceListener listener, int profile) {
        return bluetoothAdapter.getProfileProxy(context, listener, profile);
    }

    protected boolean hasPermission(Context context, String permission) {
        return apprtcContext.checkPermission(permission, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ghi nhật ký trạng thái của bộ điều hợp Bluetooth cục bộ.
     * Logs the state of the local Bluetooth adapter.
     */
    @SuppressLint("HardwareIds")
    protected void logBluetoothAdapterInfo(BluetoothAdapter localAdapter) {
        Log.d(TAG, "BluetoothAdapter: "
                + "enabled=" + localAdapter.isEnabled() + ", "
                + "state=" + stateToString(localAdapter.getState()) + ", "
                + "name=" + localAdapter.getName() + ", "
                + "address=" + localAdapter.getAddress());
        // Ghi nhật ký tập hợp các đối tượng BluetoothDevice được liên kết (ghép nối) với bộ điều hợp cục bộ.
        // Log the set of BluetoothDevice objects that are bonded (paired) to the local adapter.
        Set<BluetoothDevice> pairedDevices = localAdapter.getBondedDevices();
        if (!pairedDevices.isEmpty()) {
            Log.d(TAG, "paired devices:");
            for (BluetoothDevice device : pairedDevices) {
                Log.d(TAG, " name=" + device.getName() + ", address=" + device.getAddress());
            }
        }
    }

    /**
     * Đảm bảo rằng trình quản lý âm thanh cập nhật danh sách các thiết bị âm thanh có sẵn.
     * Ensures that the audio manager updates its list of available audio devices.
     */
    private void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "updateAudioDeviceState");
        apprtcAudioManager.updateAudioDeviceState();
    }

    /**
     * Bắt đầu hẹn giờ hết giờ sau BLUETOOTH_SCO_TIMEOUT_MS mili giây.
     * Starts timer which times out after BLUETOOTH_SCO_TIMEOUT_MS milliseconds.
     */
    private void startTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "startTimer");
        handler.postDelayed(bluetoothTimeoutRunnable, BLUETOOTH_SCO_TIMEOUT_MS);
    }

    /**
     * Hủy bỏ bất kỳ nhiệm vụ hẹn giờ xuất sắc.
     * Cancels any outstanding timer tasks.
     */
    private void cancelTimer() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "cancelTimer");
        handler.removeCallbacks(bluetoothTimeoutRunnable);
    }

    /**
     * Được gọi khi bắt đầu kênh BT SCO mất quá nhiều thời gian.
     * Thường xảy ra khi thiết bị BT đã được bật trong khi cuộc gọi đang diễn ra.
     * Called when start of the BT SCO channel takes too long time. Usually
     * happens when the BT device has been turned on during an ongoing call.
     */
    private void bluetoothTimeout() {
        ThreadUtils.checkIsOnMainThread();
        if (bluetoothState == State.UNINITIALIZED || bluetoothHeadset == null) {
            return;
        }
        Log.d(TAG, "bluetoothTimeout: BT state=" + bluetoothState + ", "
                + "attempts: " + scoConnectionAttempts + ", "
                + "SCO is on: " + isScoOn());
        if (bluetoothState != State.SCO_CONNECTING) {
            return;
        }
        // Bluetooth SCO nên được kết nối; kiểm tra kết quả mới nhất
        // Bluetooth SCO should be connecting; check the latest result.
        boolean scoConnected = false;
        List<BluetoothDevice> devices = bluetoothHeadset.getConnectedDevices();
        if (devices.size() > 0) {
            bluetoothDevice = devices.get(0);
            if (bluetoothHeadset.isAudioConnected(bluetoothDevice)) {
                Log.d(TAG, "SCO connected with " + bluetoothDevice.getName());
                scoConnected = true;
            } else {
                Log.d(TAG, "SCO is not connected with " + bluetoothDevice.getName());
            }
        }
        if (scoConnected) {
            // Chúng tôi nghĩ rằng BT đã hết thời gian, nhưng nó thực sự trên; cập nhật trạng thái.
            // We thought BT had timed out, but it's actually on; updating state.
            bluetoothState = State.SCO_CONNECTED;
            scoConnectionAttempts = 0;
        } else {
            // Từ bỏ và "hủy bỏ" yêu cầu của chúng tôi bằng cách gọi stopBluetoothSco ().
            // Give up and "cancel" our request by calling stopBluetoothSco().
            Log.w(TAG, "BT failed to connect after timeout");
            stopScoAudio();
        }
        updateAudioDeviceState();
        Log.d(TAG, "bluetoothTimeout done: BT state=" + bluetoothState);
    }

    /**
     * Kiểm tra xem âm thanh có sử dụng Bluetooth SCO không.
     * Checks whether audio uses Bluetooth SCO.
     */
    private boolean isScoOn() {
        return audioManager.isBluetoothScoOn();
    }

    /**
     * Chuyển đổi trạng thái BluetoothAdapter thành biểu diễn chuỗi cục bộ.
     * Converts BluetoothAdapter states into local string representations.
     */
    private String stateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_DISCONNECTED:
                return "DISCONNECTED";
            case BluetoothAdapter.STATE_CONNECTED:
                return "CONNECTED";
            case BluetoothAdapter.STATE_CONNECTING:
                return "CONNECTING";
            case BluetoothAdapter.STATE_DISCONNECTING:
                return "DISCONNECTING";
            case BluetoothAdapter.STATE_OFF:
                return "OFF";
            case BluetoothAdapter.STATE_ON:
                return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF:
                // Cho biết bộ điều hợp Bluetooth cục bộ đang tắt.
                // Khách hàng địa phương nên ngay lập tức cố gắng ngắt kết nối duyên dáng của bất kỳ liên kết từ xa.
                // Indicates the local Bluetooth adapter is turning off. Local clients should immediately
                // attempt graceful disconnection of any remote links.
                return "TURNING_OFF";
            case BluetoothAdapter.STATE_TURNING_ON:
                // Cho biết bộ điều hợp Bluetooth cục bộ đang bật.
                // Tuy nhiên, khách hàng địa phương nên chờ cho STATE_ON trước khi thử sử dụng bộ chuyển đổi.
                // Indicates the local Bluetooth adapter is turning on. However local clients should wait
                // for STATE_ON before attempting to use the adapter.
                return "TURNING_ON";
            default:
                return "INVALID";
        }
    }
}
