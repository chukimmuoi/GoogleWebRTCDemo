/*
 *  Copyright 2014 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.chukimmuoi.googlewebrtcdemo.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.chukimmuoi.googlewebrtcdemo.R;
import com.chukimmuoi.googlewebrtcdemo.util.AppRTCUtils;

import org.webrtc.ThreadUtils;

/**
 * AppRTCAudioManager quản lý tất cả các phần liên quan đến âm thanh của bản demo AppRTC.
 * AppRTCAudioManager manages all audio related parts of the AppRTC demo.
 */
public class AppRTCAudioManager {
    private static final String TAG = "AppRTCAudioManager";
    private static final String SPEAKERPHONE_AUTO = "auto";
    private static final String SPEAKERPHONE_TRUE = "true";
    private static final String SPEAKERPHONE_FALSE = "false";

    /**
     * AudioDevice là tên của các thiết bị âm thanh có thể có mà chúng tôi hiện đang hỗ trợ.
     * AudioDevice is the names of possible audio devices that we currently support.
     */
    public enum AudioDevice {SPEAKER_PHONE, WIRED_HEADSET, EARPIECE, BLUETOOTH, NONE}

    /**
     * Trạng thái Trình quản lý âm thanh.
     * AudioManager state.
     */
    public enum AudioManagerState {
        UNINITIALIZED,  // KHÔNG GIỚI HẠN
        PREINITIALIZED, // CHUẨN BỊ
        RUNNING,        // ĐANG CHẠY
    }

    /**
     * Sự kiện thay đổi thiết bị âm thanh được chọn.
     * Selected audio device change event.
     */
    public interface AudioManagerEvents {
        // Gọi lại được kích hoạt khi thiết bị âm thanh được thay đổi hoặc danh sách các thiết bị âm thanh có sẵn đã thay đổi.
        // Callback fired once audio device is changed or list of available audio devices changed.
        void onAudioDeviceChanged(
                AudioDevice selectedAudioDevice, Set<AudioDevice> availableAudioDevices);
    }

    private final Context apprtcContext;
    @Nullable
    private AudioManager audioManager;

    @Nullable
    private AudioManagerEvents audioManagerEvents;
    private AudioManagerState amState;
    private int savedAudioMode = AudioManager.MODE_INVALID;
    private boolean savedIsSpeakerPhoneOn;
    private boolean savedIsMicrophoneMute;
    private boolean hasWiredHeadset;

    // Thiết bị âm thanh mặc định;
    // loa điện thoại cho các cuộc gọi video hoặc tai nghe cho các cuộc gọi chỉ âm thanh.
    // Default audio device;
    // speaker phone for video calls or earpiece for audio only calls.
    private AudioDevice defaultAudioDevice;

    // Chứa thiết bị âm thanh hiện được chọn.
    // Thiết bị này được thay đổi tự động bằng cách sử dụng một sơ đồ nhất định, ví dụ:
    // tai nghe có dây "thắng" qua loa điện thoại. Nó cũng có thể cho một
    // người dùng chọn rõ ràng một thiết bị (và ghi đè bất kỳ lược đồ được xác định trước nào).
    // Xem | userSelectedAudioDevice | để biết chi tiết.
    // Contains the currently selected audio device.
    // This device is changed automatically using a certain scheme where e.g.
    // a wired headset "wins" over speaker phone. It is also possible for a
    // user to explicitly select a device (and overrid any predefined scheme).
    // See |userSelectedAudioDevice| for details.
    private AudioDevice selectedAudioDevice;

    // Chứa thiết bị âm thanh do người dùng chọn sẽ ghi đè lên được xác định trước
    // sơ đồ lựa chọn.
    // TODO (henrika): luôn được đặt thành AudioDevice. NGAY hôm nay. Thêm hỗ trợ cho
    // lựa chọn rõ ràng dựa trên sự lựa chọn của userSelectedAudioDevice.
    // Contains the user-selected audio device which overrides the predefined
    // selection scheme.
    // TODO(henrika): always set to AudioDevice.NONE today. Add support for
    // explicit selection based on choice by userSelectedAudioDevice.
    private AudioDevice userSelectedAudioDevice;

    // Chứa cài đặt loa ngoài: tự động, đúng hoặc sai
    // Contains speakerphone setting: auto, true or false
    @Nullable
    private final String useSpeakerphone;

    // Đối tượng cảm biến tiệm cận. Nó đo khoảng cách của một đối tượng tính bằng cm
    // liên quan đến màn hình xem của thiết bị và do đó có thể được sử dụng để
    // hỗ trợ chuyển đổi thiết bị (gần tai <=> sử dụng tai nghe tai nghe nếu
    // khả dụng, cách xa tai <=> sử dụng loa điện thoại).
    // Proximity sensor object. It measures the proximity of an object in cm
    // relative to the view screen of a device and can therefore be used to
    // assist device switching (close to ear <=> use headset earpiece if
    // available, far from ear <=> use speaker phone).
    @Nullable
    private AppRTCProximitySensor proximitySensor;

    // Xử lý tất cả các tác vụ liên quan đến thiết bị tai nghe Bluetooth.
    // Handles all tasks related to Bluetooth headset devices.
    private final AppRTCBluetoothManager bluetoothManager;

    // Chứa danh sách các thiết bị âm thanh có sẵn. Bộ sưu tập Set được sử dụng để
    // tránh các phần tử trùng lặp.
    // Contains a list of available audio devices. A Set collection is used to
    // avoid duplicate elements.
    private Set<AudioDevice> audioDevices = new HashSet<>();

    // Máy thu phát sóng cho mục đích phát sóng tai nghe có dây.
    // Broadcast receiver for wired headset intent broadcasts.
    private BroadcastReceiver wiredHeadsetReceiver;

    // Phương pháp gọi lại để thay đổi trong tập trung âm thanh.
    // Callback method for changes in audio focus.
    @Nullable
    private AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;

    /**
     * Phương pháp này được gọi khi cảm biến tiệm cận báo cáo thay đổi trạng thái,
     * ví dụ. từ "NEAR đến FAR" hoặc từ "FAR đến NEAR".
     * This method is called when the proximity sensor reports a state change,
     * e.g. from "NEAR to FAR" or from "FAR to NEAR".
     */
    private void onProximitySensorChangedState() {
        if (!useSpeakerphone.equals(SPEAKERPHONE_AUTO)) {
            return;
        }

        // Chỉ nên kích hoạt cảm biến tiệm cận khi có chính xác hai
        // thiết bị âm thanh có sẵn.
        // The proximity sensor should only be activated when there are exactly two
        // available audio devices.
        if (audioDevices.size() == 2 && audioDevices.contains(AudioDevice.EARPIECE)
                && audioDevices.contains(AudioDevice.SPEAKER_PHONE)) {
            if (proximitySensor.sensorReportsNearState()) {
                // Cảm biến báo cáo rằng "một chiếc điện thoại đang được giữ đến tai của một người",
                // hoặc "một cái gì đó đang che cảm biến ánh sáng".
                // Sensor reports that a "handset is being held up to a person's ear",
                // or "something is covering the light sensor".
                setAudioDeviceInternal(AudioDevice.EARPIECE);
            } else {
                // Cảm biến báo cáo rằng "điện thoại được lấy ra khỏi tai của một người" hoặc
                // "cảm biến ánh sáng không còn được bảo hiểm".
                // Sensor reports that a "handset is removed from a person's ear", or
                // "the light sensor is no longer covered".
                setAudioDeviceInternal(AudioDevice.SPEAKER_PHONE);
            }
        }
    }

    /* Bộ thu xử lý các thay đổi về tính khả dụng của tai nghe có dây. */
    /* Receiver which handles changes in wired headset availability. */
    private class WiredHeadsetReceiver extends BroadcastReceiver {
        private static final int STATE_UNPLUGGED = 0; // KHÔNG GIỚI HẠN
        private static final int STATE_PLUGGED = 1; // cắm
        private static final int HAS_NO_MIC = 0;
        private static final int HAS_MIC = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", STATE_UNPLUGGED);
            int microphone = intent.getIntExtra("microphone", HAS_NO_MIC);
            String name = intent.getStringExtra("name");
            Log.d(TAG, "WiredHeadsetReceiver.onReceive" + AppRTCUtils.getThreadInfo() + ": "
                    + "a=" + intent.getAction() + ", s="
                    + (state == STATE_UNPLUGGED ? "unplugged" : "plugged") + ", m="
                    + (microphone == HAS_MIC ? "mic" : "no mic") + ", n=" + name + ", sb="
                    + isInitialStickyBroadcast());
            hasWiredHeadset = (state == STATE_PLUGGED);
            updateAudioDeviceState();
        }
    }

    /**
     * Construction.
     */
    public static AppRTCAudioManager create(Context context) {
        return new AppRTCAudioManager(context);
    }

    private AppRTCAudioManager(Context context) {
        Log.d(TAG, "ctor");
        ThreadUtils.checkIsOnMainThread();
        apprtcContext = context;
        audioManager = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
        bluetoothManager = AppRTCBluetoothManager.create(context, this);
        wiredHeadsetReceiver = new WiredHeadsetReceiver();
        amState = AudioManagerState.UNINITIALIZED;

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        useSpeakerphone = sharedPreferences.getString(context.getString(R.string.pref_speakerphone_key),
                context.getString(R.string.pref_speakerphone_default));
        Log.d(TAG, "useSpeakerphone: " + useSpeakerphone);
        if (useSpeakerphone.equals(SPEAKERPHONE_FALSE)) {
            defaultAudioDevice = AudioDevice.EARPIECE;
        } else {
            defaultAudioDevice = AudioDevice.SPEAKER_PHONE;
        }

        // Tạo và khởi tạo cảm biến tiệm cận.
        // Thiết bị máy tính bảng (ví dụ: Nexus 7) không hỗ trợ cảm biến tiệm cận.
        // Lưu ý rằng, cảm biến sẽ không hoạt động cho đến khi start () được gọi.
        // Create and initialize the proximity sensor.
        // Tablet devices (e.g. Nexus 7) does not support proximity sensors.
        // Note that, the sensor will not be active until start() has been called.
        proximitySensor = AppRTCProximitySensor.create(context,
                // Phương thức này sẽ được gọi mỗi khi phát hiện thay đổi trạng thái.
                // Ví dụ: người dùng giữ tay trên thiết bị (gần hơn ~ 5 cm),
                // hoặc bỏ tay ra khỏi thiết bị.
                // This method will be called each time a state change is detected.
                // Example: user holds his hand over the device (closer than ~5 cm),
                // or removes his hand from the device.
                this::onProximitySensorChangedState);

        Log.d(TAG, "defaultAudioDevice: " + defaultAudioDevice);
        AppRTCUtils.logDeviceInfo(TAG);
    }

    @SuppressWarnings("deprecation")
    // TODO (henrika): audioManager.requestAudioFocus() không được dùng nữa.
    // TODO(henrika): audioManager.requestAudioFocus() is deprecated.
    public void start(AudioManagerEvents audioManagerEvents) {
        Log.d(TAG, "start");
        ThreadUtils.checkIsOnMainThread();
        if (amState == AudioManagerState.RUNNING) {
            Log.e(TAG, "AudioManager is already active");
            return;
        }
        // TODO (henrika): có thể gọi phương thức mới gọi là preInitAudio () ở đây nếu UNINITIALIZED.
        // TODO(henrika): perhaps call new method called preInitAudio() here if UNINITIALIZED.

        Log.d(TAG, "AudioManager starts...");
        this.audioManagerEvents = audioManagerEvents;
        amState = AudioManagerState.RUNNING;

        // Lưu trữ trạng thái âm thanh hiện tại để chúng tôi có thể khôi phục nó khi stop () được gọi.
        // Store current audio state so we can restore it when stop() is called.
        savedAudioMode = audioManager.getMode();
        savedIsSpeakerPhoneOn = audioManager.isSpeakerphoneOn();
        savedIsMicrophoneMute = audioManager.isMicrophoneMute();
        hasWiredHeadset = hasWiredHeadset();

        // Tạo một cá thể AudioManager.OnAudioF FocusChangeListener.
        // Create an AudioManager.OnAudioFocusChangeListener instance.
        audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            // Được gọi trên trình nghe để thông báo nếu tiêu điểm âm thanh cho trình nghe này đã bị thay đổi.
            // The | FocusChange | giá trị cho biết liệu tiêu điểm đã đạt được, liệu tiêu điểm có bị mất hay không,
            // và liệu sự mất mát đó là nhất thời hay liệu người giữ tiêu điểm mới sẽ giữ nó cho
            // lượng thời gian không xác định.
            // TODO (henrika): có thể mở rộng hỗ trợ xử lý các thay đổi tập trung vào âm thanh. Chỉ chứa
            // đăng nhập ngay bây giờ.
            // Called on the listener to notify if the audio focus for this listener has been changed.
            // The |focusChange| value indicates whether the focus was gained, whether the focus was lost,
            // and whether that loss is transient, or whether the new focus holder will hold it for an
            // unknown amount of time.
            // TODO(henrika): possibly extend support of handling audio-focus changes. Only contains
            // logging for now.
            @Override
            public void onAudioFocusChange(int focusChange) {
                final String typeOfChange;
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_GAIN:
                        typeOfChange = "AUDIOFOCUS_GAIN";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE";
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK:
                        typeOfChange = "AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        typeOfChange = "AUDIOFOCUS_LOSS";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT";
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        typeOfChange = "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
                        break;
                    default:
                        typeOfChange = "AUDIOFOCUS_INVALID";
                        break;
                }
                Log.d(TAG, "onAudioFocusChange: " + typeOfChange);
            }
        };

        // Yêu cầu tiêu điểm phát âm thanh (không có tiếng vịt) và cài đặt trình nghe để thay đổi tiêu điểm.
        // Request audio playout focus (without ducking) and install listener for changes in focus.
        int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "Audio focus request granted for VOICE_CALL streams");
        } else {
            Log.e(TAG, "Audio focus request failed");
        }

        // Bắt đầu bằng cách đặt MODE_IN_COMMUNICATION làm chế độ âm thanh mặc định. Nó là
        // bắt buộc phải ở chế độ này khi phát và / hoặc ghi bắt đầu cho
        // hiệu suất VoIP tốt nhất có thể.
        // Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
        // required to be in this mode when playout and/or recording starts for
        // best possible VoIP performance.
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        // Luôn tắt tiếng micrô trong khi gọi WebRTC.
        // Always disable microphone mute during a WebRTC call.
        setMicrophoneMute(false);

        // Đặt trạng thái thiết bị ban đầu.
        // Set initial device states.
        userSelectedAudioDevice = AudioDevice.NONE;
        selectedAudioDevice = AudioDevice.NONE;
        audioDevices.clear();

        // Khởi tạo và khởi động Bluetooth nếu thiết bị BT khả dụng hoặc bắt đầu
        // phát hiện các thiết bị BT mới (đã bật).
        // Initialize and start Bluetooth if a BT device is available or initiate
        // detection of new (enabled) BT devices.
        bluetoothManager.start();

        // Thực hiện lựa chọn ban đầu của thiết bị âm thanh. Cài đặt này sau đó có thể được thay đổi
        // bằng cách thêm / xóa tai nghe BT hoặc tai nghe có dây hoặc bằng cách che / phát hiện
        // cảm biến tiệm cận.
        // Do initial selection of audio device. This setting can later be changed
        // either by adding/removing a BT or wired headset or by covering/uncovering
        // the proximity sensor.
        updateAudioDeviceState();

        // Đăng ký người nhận cho các ý định phát sóng liên quan đến việc thêm / xóa một
        // tai nghe có dây.
        // Register receiver for broadcast intents related to adding/removing a
        // wired headset.
        registerReceiver(wiredHeadsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        Log.d(TAG, "AudioManager started");
    }

    @SuppressWarnings("deprecation")
    // TODO(henrika): audioManager.abandonAudioFocus() is deprecated.
    public void stop() {
        Log.d(TAG, "stop");
        ThreadUtils.checkIsOnMainThread();
        if (amState != AudioManagerState.RUNNING) {
            Log.e(TAG, "Trying to stop AudioManager in incorrect state: " + amState);
            return;
        }
        amState = AudioManagerState.UNINITIALIZED;

        unregisterReceiver(wiredHeadsetReceiver);

        bluetoothManager.stop();

        // Khôi phục trạng thái âm thanh được lưu trữ trước đó.
        // Restore previously stored audio states.
        setSpeakerphoneOn(savedIsSpeakerPhoneOn);
        setMicrophoneMute(savedIsMicrophoneMute);
        audioManager.setMode(savedAudioMode);

        // Bỏ tập trung âm thanh. Cung cấp cho chủ sở hữu tập trung trước, nếu có, tập trung.
        // Abandon audio focus. Gives the previous focus owner, if any, focus.
        audioManager.abandonAudioFocus(audioFocusChangeListener);
        audioFocusChangeListener = null;
        Log.d(TAG, "Abandoned audio focus for VOICE_CALL streams");

        if (proximitySensor != null) {
            proximitySensor.stop();
            proximitySensor = null;
        }

        audioManagerEvents = null;
        Log.d(TAG, "AudioManager stopped");
    }

    /**
     * Thay đổi lựa chọn của thiết bị âm thanh hiện đang hoạt động.
     * Changes selection of the currently active audio device.
     */
    private void setAudioDeviceInternal(AudioDevice device) {
        Log.d(TAG, "setAudioDeviceInternal(device=" + device + ")");
        AppRTCUtils.assertIsTrue(audioDevices.contains(device));

        switch (device) {
            case SPEAKER_PHONE:
                setSpeakerphoneOn(true);
                break;
            case EARPIECE:
                setSpeakerphoneOn(false);
                break;
            case WIRED_HEADSET:
                setSpeakerphoneOn(false);
                break;
            case BLUETOOTH:
                setSpeakerphoneOn(false);
                break;
            default:
                Log.e(TAG, "Invalid audio device selection"); // Lựa chọn thiết bị âm thanh không hợp lệ
                break;
        }
        selectedAudioDevice = device;
    }

    /**
     * Thay đổi thiết bị âm thanh mặc định.
     * Changes default audio device.
     * TODO(henrika): thêm việc sử dụng phương pháp này trong ứng dụng khách AppRTCMobile.
     * TODO(henrika): add usage of this method in the AppRTCMobile client.
     */
    public void setDefaultAudioDevice(AudioDevice defaultDevice) {
        ThreadUtils.checkIsOnMainThread();
        switch (defaultDevice) {
            case SPEAKER_PHONE:
                defaultAudioDevice = defaultDevice;
                break;
            case EARPIECE:
                if (hasEarpiece()) {
                    defaultAudioDevice = defaultDevice;
                } else {
                    defaultAudioDevice = AudioDevice.SPEAKER_PHONE;
                }
                break;
            default:
                Log.e(TAG, "Invalid default audio device selection");
                break;
        }
        Log.d(TAG, "setDefaultAudioDevice(device=" + defaultAudioDevice + ")");
        updateAudioDeviceState();
    }

    /**
     * Thay đổi lựa chọn của thiết bị âm thanh hiện đang hoạt động.
     * Changes selection of the currently active audio device.
     */
    public void selectAudioDevice(AudioDevice device) {
        ThreadUtils.checkIsOnMainThread();
        if (!audioDevices.contains(device)) {
            Log.e(TAG, "Can not select " + device + " from available " + audioDevices);
        }
        userSelectedAudioDevice = device;
        updateAudioDeviceState();
    }

    /**
     * Trả về bộ hiện tại của các thiết bị âm thanh có sẵn / có thể chọn.
     * Returns current set of available/selectable audio devices.
     */
    public Set<AudioDevice> getAudioDevices() {
        ThreadUtils.checkIsOnMainThread();
        return Collections.unmodifiableSet(new HashSet<>(audioDevices));
    }

    /**
     * Trả về thiết bị âm thanh hiện được chọn.
     * Returns the currently selected audio device.
     */
    public AudioDevice getSelectedAudioDevice() {
        ThreadUtils.checkIsOnMainThread();
        return selectedAudioDevice;
    }

    /**
     * Phương thức trợ giúp đăng ký nhận.
     * Helper method for receiver registration.
     */
    private void registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        apprtcContext.registerReceiver(receiver, filter);
    }

    /**
     * Phương pháp trợ giúp để không đăng ký của một người nhận hiện có.
     * Helper method for unregistration of an existing receiver.
     */
    private void unregisterReceiver(BroadcastReceiver receiver) {
        apprtcContext.unregisterReceiver(receiver);
    }

    /**
     * Đặt chế độ loa điện thoại.
     * Sets the speaker phone mode.
     */
    private void setSpeakerphoneOn(boolean on) {
        boolean wasOn = audioManager.isSpeakerphoneOn();
        if (wasOn == on) {
            return;
        }
        audioManager.setSpeakerphoneOn(on);
    }

    /**
     * Đặt trạng thái tắt tiếng của micrô.
     * Sets the microphone mute state.
     */
    private void setMicrophoneMute(boolean on) {
        boolean wasMuted = audioManager.isMicrophoneMute();
        if (wasMuted == on) {
            return;
        }
        audioManager.setMicrophoneMute(on);
    }

    /**
     * Gets trạng thái tai nghe hiện tại.
     * Gets the current earpiece state.
     */
    private boolean hasEarpiece() {
        return apprtcContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /**
     * Kiểm tra xem tai nghe có dây có được kết nối hay không.
     * Đây không phải là dấu hiệu hợp lệ cho thấy phát lại âm thanh thực sự kết thúc
     * tai nghe có dây khi định tuyến âm thanh phụ thuộc vào các điều kiện khác. Chúng tôi
     * chỉ sử dụng nó như một chỉ báo sớm (trong quá trình khởi tạo) của một tệp đính kèm
     * tai nghe có dây.
     * Checks whether a wired headset is connected or not.
     * This is not a valid indication that audio playback is actually over
     * the wired headset as audio routing depends on other conditions. We
     * only use it as an early indicator (during initialization) of an attached
     * wired headset.
     */
    @Deprecated
    private boolean hasWiredHeadset() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return audioManager.isWiredHeadsetOn();
        } else {
            final AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL);
            for (AudioDeviceInfo device : devices) {
                final int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    Log.d(TAG, "hasWiredHeadset: found wired headset");
                    return true;
                } else if (type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                    Log.d(TAG, "hasWiredHeadset: found USB audio device");
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Cập nhật danh sách các thiết bị âm thanh có thể và thực hiện lựa chọn thiết bị mới.
     * TODO (henrika): thêm kiểm tra đơn vị để xác minh tất cả các chuyển đổi trạng thái.
     * Updates list of possible audio devices and make new device selection.
     * TODO(henrika): add unit test to verify all state transitions.
     */
    public void updateAudioDeviceState() {
        ThreadUtils.checkIsOnMainThread();
        Log.d(TAG, "--- updateAudioDeviceState: "
                + "wired headset=" + hasWiredHeadset + ", "
                + "BT state=" + bluetoothManager.getState());
        Log.d(TAG, "Device status: "
                + "available=" + audioDevices + ", "
                + "selected=" + selectedAudioDevice + ", "
                + "user selected=" + userSelectedAudioDevice);

        // Kiểm tra xem có tai nghe Bluetooth nào được kết nối không. Nhà nước BT nội bộ sẽ
        // thay đổi tương ứng.
        // TODO (henrika): có lẽ bọc trạng thái cần thiết vào trình quản lý BT.
        // Check if any Bluetooth headset is connected. The internal BT state will
        // change accordingly.
        // TODO(henrika): perhaps wrap required state into BT manager.
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_DISCONNECTING) {
            bluetoothManager.updateDevice();
        }

        // Cập nhật bộ thiết bị âm thanh có sẵn.
        // Update the set of available audio devices.
        Set<AudioDevice> newAudioDevices = new HashSet<>();

        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE) {
            newAudioDevices.add(AudioDevice.BLUETOOTH);
        }

        if (hasWiredHeadset) {
            // Nếu tai nghe có dây được kết nối, thì đó là tùy chọn duy nhất có thể.
            // If a wired headset is connected, then it is the only possible option.
            newAudioDevices.add(AudioDevice.WIRED_HEADSET);
        } else {
            // Không có tai nghe có dây, do đó danh sách thiết bị âm thanh có thể chứa loa
            // điện thoại (trên máy tính bảng) hoặc loa điện thoại và tai nghe (trên điện thoại di động).
            // No wired headset, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            newAudioDevices.add(AudioDevice.SPEAKER_PHONE);
            if (hasEarpiece()) {
                newAudioDevices.add(AudioDevice.EARPIECE);
            }
        }
        // Lưu trữ trạng thái được đặt thành true nếu danh sách thiết bị đã thay đổi.
        // Store state which is set to true if the device list has changed.
        boolean audioDeviceSetUpdated = !audioDevices.equals(newAudioDevices);
        // Cập nhật bộ thiết bị âm thanh hiện có.
        // Update the existing audio device set.
        audioDevices = newAudioDevices;
        // Đúng người dùng đã chọn thiết bị âm thanh nếu cần.
        // Correct user selected audio devices if needed.
        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_UNAVAILABLE
                && userSelectedAudioDevice == AudioDevice.BLUETOOTH) {
            // Nếu BT không có sẵn, nó không thể là lựa chọn của người dùng.
            // If BT is not available, it can't be the user selection.
            userSelectedAudioDevice = AudioDevice.NONE;
        }
        if (hasWiredHeadset && userSelectedAudioDevice == AudioDevice.SPEAKER_PHONE) {
            // Nếu người dùng chọn loa điện thoại, nhưng sau đó cắm tai nghe có dây thì hãy thực hiện
            // tai nghe có dây là thiết bị được người dùng chọn.
            // If user selected speaker phone, but then plugged wired headset then make
            // wired headset as user selected device.
            userSelectedAudioDevice = AudioDevice.WIRED_HEADSET;
        }
        if (!hasWiredHeadset && userSelectedAudioDevice == AudioDevice.WIRED_HEADSET) {
            // Nếu người dùng chọn tai nghe có dây, nhưng sau đó rút tai nghe có dây thì hãy thực hiện
            // loa điện thoại là thiết bị được người dùng chọn.
            // If user selected wired headset, but then unplugged wired headset then make
            // speaker phone as user selected device.
            userSelectedAudioDevice = AudioDevice.SPEAKER_PHONE;
        }

        // Cần khởi động Bluetooth nếu có sẵn và người dùng chọn nó một cách rõ ràng hoặc
        // người dùng không chọn bất kỳ thiết bị đầu ra.
        // Need to start Bluetooth if it is available and user either selected it explicitly or
        // user did not select any output device.
        boolean needBluetoothAudioStart =
                bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                        && (userSelectedAudioDevice == AudioDevice.NONE
                        || userSelectedAudioDevice == AudioDevice.BLUETOOTH);

        // Cần dừng âm thanh Bluetooth nếu người dùng chọn thiết bị khác và
        // Kết nối Bluetooth SCO được thiết lập hoặc đang trong quá trình.
        // Need to stop Bluetooth audio if user selected different device and
        // Bluetooth SCO connection is established or in the process.
        boolean needBluetoothAudioStop =
                (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED
                        || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING)
                        && (userSelectedAudioDevice != AudioDevice.NONE
                        && userSelectedAudioDevice != AudioDevice.BLUETOOTH);

        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.HEADSET_AVAILABLE
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTING
                || bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            Log.d(TAG, "Need BT audio: start=" + needBluetoothAudioStart + ", "
                    + "stop=" + needBluetoothAudioStop + ", "
                    + "BT state=" + bluetoothManager.getState());
        }

        // Bắt đầu hoặc dừng kết nối Bluetooth SCO được đưa ra trước đó.
        // Start or stop Bluetooth SCO connection given states set earlier.
        if (needBluetoothAudioStop) {
            bluetoothManager.stopScoAudio();
            bluetoothManager.updateDevice();
        }

        if (needBluetoothAudioStart && !needBluetoothAudioStop) {
            // Cố gắng khởi động âm thanh Bluetooth SCO (mất vài giây để bắt đầu).
            // Attempt to start Bluetooth SCO audio (takes a few second to start).
            if (!bluetoothManager.startScoAudio()) {
                // Xóa BLUETOOTH khỏi danh sách các thiết bị khả dụng do SCO không thành công.
                // Remove BLUETOOTH from list of available devices since SCO failed.
                audioDevices.remove(AudioDevice.BLUETOOTH);
                audioDeviceSetUpdated = true;
            }
        }

        // Cập nhật thiết bị âm thanh được chọn.
        // Update selected audio device.
        final AudioDevice newAudioDevice;

        if (bluetoothManager.getState() == AppRTCBluetoothManager.State.SCO_CONNECTED) {
            // Nếu Bluetooth được kết nối, thì nó nên được sử dụng làm âm thanh đầu ra
            // thiết bị. Lưu ý rằng tai nghe là không đủ;
            // một kênh SCO đang hoạt động cũng phải được chạy và chạy.
            // If a Bluetooth is connected, then it should be used as output audio
            // device. Note that it is not sufficient that a headset is available;
            // an active SCO channel must also be up and running.
            newAudioDevice = AudioDevice.BLUETOOTH;
        } else if (hasWiredHeadset) {
            // Nếu tai nghe có dây được kết nối, nhưng Bluetooth thì không, nhưng tai nghe có dây được sử dụng như
            // thiết bị âm thanh.
            // If a wired headset is connected, but Bluetooth is not, then wired headset is used as
            // audio device.
            newAudioDevice = AudioDevice.WIRED_HEADSET;
        } else {
            // Không có tai nghe có dây và không có Bluetooth, do đó danh sách thiết bị âm thanh có thể chứa loa
            // điện thoại (trên máy tính bảng) hoặc loa điện thoại và tai nghe (trên điện thoại di động).
            // | defaultAudioDevice | chứa AudioDevice.SPEAKER_PHONE hoặc AudioDevice.EARPIECE
            // tùy thuộc vào lựa chọn của người dùng.
            // No wired headset and no Bluetooth, hence the audio-device list can contain speaker
            // phone (on a tablet), or speaker phone and earpiece (on mobile phone).
            // |defaultAudioDevice| contains either AudioDevice.SPEAKER_PHONE or AudioDevice.EARPIECE
            // depending on the user's selection.
            newAudioDevice = defaultAudioDevice;
        }
        // Chuyển sang thiết bị mới nhưng chỉ khi có bất kỳ thay đổi nào.
        // Switch to new device but only if there has been any changes.
        if (newAudioDevice != selectedAudioDevice || audioDeviceSetUpdated) {
            // Thực hiện chuyển đổi thiết bị cần thiết.
            // Do the required device switch.
            setAudioDeviceInternal(newAudioDevice);
            Log.d(TAG, "New device status: "
                    + "available=" + audioDevices + ", "
                    + "selected=" + newAudioDevice);
            if (audioManagerEvents != null) {
                // Thông báo cho khách hàng nghe rằng thiết bị âm thanh đã được thay đổi.
                // Notify a listening client that audio device has been changed.
                audioManagerEvents.onAudioDeviceChanged(selectedAudioDevice, audioDevices);
            }
        }
        Log.d(TAG, "--- updateAudioDeviceState done");
    }
}
