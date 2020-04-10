/*
 *  Copyright 2013 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package com.chukimmuoi.googlewebrtcdemo.client;

import org.webrtc.IceCandidate;
import org.webrtc.PeerConnection;
import org.webrtc.SessionDescription;

import java.util.List;

/**
 * AppRTCClient là giao diện đại diện cho ứng dụng khách AppRTC.
 * AppRTCClient is the interface representing an AppRTC client.
 */
public interface AppRTCClient {
    /**
     * Struct giữ các tham số kết nối của phòng AppRTC.
     * Struct holding the connection parameters of an AppRTC room.
     */
    class RoomConnectionParameters {
        public final String roomUrl;
        public final String roomId;
        public final boolean loopback;
        public final String urlParameters;

        public RoomConnectionParameters(
                String roomUrl, String roomId, boolean loopback, String urlParameters) {
            this.roomUrl = roomUrl;
            this.roomId = roomId;
            this.loopback = loopback;
            this.urlParameters = urlParameters;
        }

        public RoomConnectionParameters(String roomUrl, String roomId, boolean loopback) {
            this(roomUrl, roomId, loopback, null /* urlParameters */);
        }
    }

    /**
     * Kết nối không đồng bộ với URL phòng AppRTC bằng các tham số kết nối được cung cấp.
     * Khi kết nối được thiết lập, gọi lại onConnectedToRoom() với các tham số phòng được gọi.
     * Asynchronously connect to an AppRTC room URL using supplied connection
     * parameters. Once connection is established onConnectedToRoom()
     * callback with room parameters is invoked.
     */
    void connectToRoom(RoomConnectionParameters connectionParameters);

    /**
     * Gửi đề nghị SDP cho người tham gia khác.
     * Send offer SDP to the other participant.
     */
    void sendOfferSdp(final SessionDescription sdp);

    /**
     * Gửi câu trả lời SDP cho người tham gia khác.
     * Send answer SDP to the other participant.
     */
    void sendAnswerSdp(final SessionDescription sdp);

    /**
     * Gửi ứng viên Ice cho người tham gia khác.
     * Send Ice candidate to the other participant.
     */
    void sendLocalIceCandidate(final IceCandidate candidate);

    /**
     * Gửi các ứng cử viên ICE bị loại bỏ cho người tham gia khác.
     * Send removed ICE candidates to the other participant.
     */
    void sendLocalIceCandidateRemovals(final IceCandidate[] candidates);

    /**
     * Ngắt kết nối phòng.
     * Disconnect from room.
     */
    void disconnectFromRoom();

    /**
     * Struct giữ các thông số báo hiệu của một phòng AppRTC.
     * Struct holding the signaling parameters of an AppRTC room.
     */
    class SignalingParameters {
        public final List<PeerConnection.IceServer> iceServers;
        public final boolean initiator;
        public final String clientId;
        public final String wssUrl;
        public final String wssPostUrl;
        public final SessionDescription offerSdp;
        public final List<IceCandidate> iceCandidates;

        public SignalingParameters(List<PeerConnection.IceServer> iceServers, boolean initiator,
                                   String clientId, String wssUrl, String wssPostUrl, SessionDescription offerSdp,
                                   List<IceCandidate> iceCandidates) {
            this.iceServers = iceServers;
            this.initiator = initiator;
            this.clientId = clientId;
            this.wssUrl = wssUrl;
            this.wssPostUrl = wssPostUrl;
            this.offerSdp = offerSdp;
            this.iceCandidates = iceCandidates;
        }
    }

    /**
     * Giao diện gọi lại cho các tin nhắn được gửi trên kênh báo hiệu.
     *
     * <p>Các phương thức được đảm bảo được gọi trên luồng UI của |hoạt động|.
     * Callback interface for messages delivered on signaling channel.
     *
     * <p>Methods are guaranteed to be invoked on the UI thread of |activity|.
     */
    interface SignalingEvents {
        /**
         * Gọi lại khi có thông số báo hiệu của phòng
         * SignalingParameter được trích xuất.
         * Callback fired once the room's signaling parameters
         * SignalingParameters are extracted.
         */
        void onConnectedToRoom(final SignalingParameters params);

        /**
         * Gọi lại khi đã nhận được SDP từ xa.
         * Callback fired once remote SDP is received.
         */
        void onRemoteDescription(final SessionDescription sdp);

        /**
         * Gọi lại khi một ứng cử viên Ice từ xa được nhận.
         * Callback fired once remote Ice candidate is received.
         */
        void onRemoteIceCandidate(final IceCandidate candidate);

        /**
         * Gọi lại bị đuổi khi nhận được ứng dụng xóa băng từ xa.
         * Callback fired once remote Ice candidate removals are received.
         */
        void onRemoteIceCandidatesRemoved(final IceCandidate[] candidates);

        /**
         * Gọi lại khi một kênh được đóng lại.
         * Callback fired once channel is closed.
         */
        void onChannelClose();

        /**
         * Gọi lại bị bắn một khi lỗi kênh xảy ra.
         * Callback fired once channel error happened.
         */
        void onChannelError(final String description);
    }
}
