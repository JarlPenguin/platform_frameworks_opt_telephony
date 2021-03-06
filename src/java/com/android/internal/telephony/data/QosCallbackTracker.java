/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.NetworkAgent;
import android.net.QosFilter;
import android.net.QosSession;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.telephony.data.EpsQos;
import android.telephony.data.NrQos;
import android.telephony.data.NrQosSessionAttributes;
import android.telephony.data.QosBearerFilter;
import android.telephony.data.QosBearerSession;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.metrics.RcsStats;
import com.android.telephony.Rlog;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches filters with qos sessions and send corresponding available and lost events.
 */
public class QosCallbackTracker extends Handler {
    private static final int DEDICATED_BEARER_EVENT_STATE_NONE = 0;
    private static final int DEDICATED_BEARER_EVENT_STATE_ADDED = 1;
    private static final int DEDICATED_BEARER_EVENT_STATE_MODIFIED = 2;
    private static final int DEDICATED_BEARER_EVENT_STATE_DELETED = 3;

    private final @NonNull String mLogTag;
    // TODO: Change this to TelephonyNetworkAgent
    private final @NonNull NotifyQosSessionInterface mNetworkAgent;
    private final @NonNull Map<Integer, QosBearerSession> mQosBearerSessions;
    private final @NonNull RcsStats mRcsStats;

    // We perform an exact match on the address
    private final @NonNull Map<Integer, IFilter> mCallbacksToFilter;

    private final int mPhoneId;

    /**
     * QOS sessions filter interface
     */
    public interface IFilter {
        /**
         * Filter using the local address.
         *
         * @param address The local address.
         * @param startPort Starting port.
         * @param endPort Ending port.
         * @return {@code true} if matches, {@code false} otherwise.
         */
        boolean matchesLocalAddress(InetAddress address, int startPort, int endPort);

        /**
         * Filter using the remote address.
         *
         * @param address The local address.
         * @param startPort Starting port.
         * @param endPort Ending port.
         * @return {@code true} if matches, {@code false} otherwise.
         */
        boolean matchesRemoteAddress(InetAddress address, int startPort, int endPort);
    }

    /**
     * Constructor
     *
     * @param networkAgent The network agent to send events to.
     * @param phone The phone instance.
     */
    public QosCallbackTracker(@NonNull NotifyQosSessionInterface networkAgent,
            @NonNull Phone phone) {
        mQosBearerSessions = new HashMap<>();
        mCallbacksToFilter = new HashMap<>();
        mNetworkAgent = networkAgent;
        mPhoneId = phone.getPhoneId();
        mRcsStats = RcsStats.getInstance();
        mLogTag = "QOSCT" + "-" + ((NetworkAgent) mNetworkAgent).getNetwork().getNetId();

        if (phone.isUsingNewDataStack()) {
            //TODO: Replace the NetworkAgent in the constructor with TelephonyNetworkAgent
            //  after mPhone.isUsingNewDataStack() check is removed.
            ((TelephonyNetworkAgent) networkAgent).registerCallback(
                    new TelephonyNetworkAgent.TelephonyNetworkAgentCallback(this::post) {
                        @Override
                        public void onQosCallbackRegistered(int qosCallbackId,
                                @NonNull QosFilter filter) {
                            addFilter(qosCallbackId,
                                    new QosCallbackTracker.IFilter() {
                                        @Override
                                        public boolean matchesLocalAddress(
                                                @NonNull InetAddress address, int startPort,
                                                int endPort) {
                                            return filter.matchesLocalAddress(address, startPort,
                                                    endPort);
                                        }

                                        @Override
                                        public boolean matchesRemoteAddress(
                                                @NonNull InetAddress address, int startPort,
                                                int endPort) {
                                            return filter.matchesRemoteAddress(address, startPort,
                                                    endPort);
                                        }
                                    });
                        }

                        @Override
                        public void onQosCallbackUnregistered(int qosCallbackId) {

                        }
                    });
        }
    }

    /**
     * Add new filter that is to receive events.
     *
     * @param callbackId the associated callback id.
     * @param filter provides the matching logic.
     */
    public void addFilter(final int callbackId, final IFilter filter) {
        post(() -> {
            log("addFilter: callbackId=" + callbackId);
            // Called from mDcNetworkAgent
            mCallbacksToFilter.put(callbackId, filter);

            //On first change. Check all sessions and send.
            for (final QosBearerSession session : mQosBearerSessions.values()) {
                if (doFiltersMatch(session, filter)) {
                    sendSessionAvailable(callbackId, session, filter);

                    notifyMetricDedicatedBearerListenerAdded(callbackId, session);
                }
            }
        });
    }

    /**
     * Remove the filter with the associated callback id.
     *
     * @param callbackId the qos callback id.
     */
    public void removeFilter(final int callbackId) {
        post(() -> {
            log("removeFilter: callbackId=" + callbackId);
            mCallbacksToFilter.remove(callbackId);
            notifyMetricDedicatedBearerListenerRemoved(callbackId);
        });
    }

    /**
     * Update the list of qos sessions and send out corresponding events
     *
     * @param sessions the new list of qos sessions
     */
    public void updateSessions(@NonNull final List<QosBearerSession> sessions) {
        post(() -> {
            log("updateSessions: sessions size=" + sessions.size());

            int bearerState = DEDICATED_BEARER_EVENT_STATE_NONE;
            final List<QosBearerSession> sessionsToAdd = new ArrayList<>();
            final Map<Integer, QosBearerSession> incomingSessions = new HashMap<>();
            for (final QosBearerSession incomingSession : sessions) {
                incomingSessions.put(incomingSession.getQosBearerSessionId(), incomingSession);

                final QosBearerSession existingSession = mQosBearerSessions.get(
                        incomingSession.getQosBearerSessionId());
                for (final int callbackId : mCallbacksToFilter.keySet()) {
                    final IFilter filter = mCallbacksToFilter.get(callbackId);

                    final boolean incomingSessionMatch = doFiltersMatch(incomingSession, filter);
                    final boolean existingSessionMatch =
                            existingSession != null && doFiltersMatch(existingSession, filter);

                    if (!existingSessionMatch && incomingSessionMatch) {
                        // The filter matches now and didn't match earlier
                        sendSessionAvailable(callbackId, incomingSession, filter);

                        bearerState = DEDICATED_BEARER_EVENT_STATE_ADDED;
                    }

                    if (existingSessionMatch && incomingSessionMatch) {
                        // The same sessions matches the same filter, but if the qos changed,
                        // the callback still needs to be notified
                        if (!incomingSession.getQos().equals(existingSession.getQos())) {
                            sendSessionAvailable(callbackId, incomingSession, filter);
                            bearerState = DEDICATED_BEARER_EVENT_STATE_MODIFIED;
                        }
                    }

                    notifyMetricDedicatedBearerEvent(incomingSession, filter, bearerState);
                }
                sessionsToAdd.add(incomingSession);
            }

            final List<Integer> sessionsToRemove = new ArrayList<>();
            // Find sessions that no longer exist
            for (final QosBearerSession existingSession : mQosBearerSessions.values()) {
                if (!incomingSessions.containsKey(existingSession.getQosBearerSessionId())) {
                    for (final int callbackId : mCallbacksToFilter.keySet()) {
                        final IFilter filter = mCallbacksToFilter.get(callbackId);
                        // The filter matches which means it was previously available, and now is
                        // lost
                        if (doFiltersMatch(existingSession, filter)) {
                            bearerState = DEDICATED_BEARER_EVENT_STATE_DELETED;
                            sendSessionLost(callbackId, existingSession);
                            notifyMetricDedicatedBearerEvent(existingSession, filter, bearerState);
                        }
                    }
                    sessionsToRemove.add(existingSession.getQosBearerSessionId());
                }
            }

            // Add in the new or existing sessions with updated information
            for (final QosBearerSession sessionToAdd : sessionsToAdd) {
                mQosBearerSessions.put(sessionToAdd.getQosBearerSessionId(), sessionToAdd);
            }

            // Remove any old sessions
            for (final int sessionToRemove : sessionsToRemove) {
                mQosBearerSessions.remove(sessionToRemove);
            }
        });
    }

    private boolean doFiltersMatch(final @NonNull QosBearerSession qosBearerSession,
            final @NonNull IFilter filter) {
        return getMatchingQosBearerFilter(qosBearerSession, filter) != null;
    }

    private boolean matchesByLocalAddress(final @NonNull QosBearerFilter sessionFilter,
            final @NonNull IFilter filter) {
        for (final LinkAddress qosAddress : sessionFilter.getLocalAddresses()) {
            return filter.matchesLocalAddress(qosAddress.getAddress(),
                  sessionFilter.getLocalPortRange().getStart(),
                  sessionFilter.getLocalPortRange().getEnd());
        }
        return false;
    }

    private boolean matchesByRemoteAddress(@NonNull QosBearerFilter sessionFilter,
            final @NonNull IFilter filter) {
        for (final LinkAddress qosAddress : sessionFilter.getRemoteAddresses()) {
            return filter.matchesRemoteAddress(qosAddress.getAddress(),
                  sessionFilter.getRemotePortRange().getStart(),
                  sessionFilter.getRemotePortRange().getEnd());
        }
        return false;
    }

    private boolean matchesByRemoteAndLocalAddress(@NonNull QosBearerFilter sessionFilter,
            final @NonNull IFilter filter) {
        for (final LinkAddress remoteAddress : sessionFilter.getRemoteAddresses()) {
            for (final LinkAddress localAddress : sessionFilter.getLocalAddresses()) {
                return filter.matchesRemoteAddress(remoteAddress.getAddress(),
                        sessionFilter.getRemotePortRange().getStart(),
                        sessionFilter.getRemotePortRange().getEnd())
                        && filter.matchesLocalAddress(localAddress.getAddress(),
                              sessionFilter.getLocalPortRange().getStart(),
                              sessionFilter.getLocalPortRange().getEnd());
            }
        }
        return false;
    }

    private QosBearerFilter getFilterByPrecedence(
            @Nullable QosBearerFilter qosFilter, QosBearerFilter sessionFilter) {
        // Find for the highest precedence filter, lower the value is the higher the precedence
        return qosFilter == null || sessionFilter.getPrecedence() < qosFilter.getPrecedence()
                ? sessionFilter : qosFilter;
    }

    private @Nullable QosBearerFilter getMatchingQosBearerFilter(
            @NonNull QosBearerSession qosBearerSession, final @NonNull IFilter filter) {
        QosBearerFilter qosFilter = null;

        for (final QosBearerFilter sessionFilter : qosBearerSession.getQosBearerFilterList()) {
            if (!sessionFilter.getLocalAddresses().isEmpty()
                    && !sessionFilter.getRemoteAddresses().isEmpty()
                    && sessionFilter.getLocalPortRange().isValid()
                    && sessionFilter.getRemotePortRange().isValid()) {
                if (matchesByRemoteAndLocalAddress(sessionFilter, filter)) {
                    qosFilter = getFilterByPrecedence(qosFilter, sessionFilter);
                }
            } else if (!sessionFilter.getRemoteAddresses().isEmpty()
                    && sessionFilter.getRemotePortRange().isValid()) {
                if (matchesByRemoteAddress(sessionFilter, filter)) {
                    qosFilter = getFilterByPrecedence(qosFilter, sessionFilter);
                }
            } else if (!sessionFilter.getLocalAddresses().isEmpty()
                    && sessionFilter.getLocalPortRange().isValid()) {
                if (matchesByLocalAddress(sessionFilter, filter)) {
                    qosFilter = getFilterByPrecedence(qosFilter, sessionFilter);
                }
            }
        }
        return qosFilter;
    }

    private void sendSessionAvailable(final int callbackId, final @NonNull QosBearerSession session,
            @NonNull IFilter filter) {
        QosBearerFilter qosBearerFilter = getMatchingQosBearerFilter(session, filter);
        List<InetSocketAddress> remoteAddresses = new ArrayList<>();
        if (qosBearerFilter.getRemoteAddresses().size() > 0) {
            remoteAddresses.add(
                    new InetSocketAddress(qosBearerFilter.getRemoteAddresses().get(0).getAddress(),
                            qosBearerFilter.getRemotePortRange().getStart()));
        }

        if (session.getQos() instanceof EpsQos) {
            EpsQos qos = (EpsQos) session.getQos();
            EpsBearerQosSessionAttributes epsBearerAttr =
                    new EpsBearerQosSessionAttributes(qos.getQci(),
                            qos.getUplinkBandwidth().getMaxBitrateKbps(),
                            qos.getDownlinkBandwidth().getMaxBitrateKbps(),
                            qos.getDownlinkBandwidth().getGuaranteedBitrateKbps(),
                            qos.getUplinkBandwidth().getGuaranteedBitrateKbps(),
                            remoteAddresses);
            mNetworkAgent.notifyQosSessionAvailable(
                    callbackId, session.getQosBearerSessionId(), epsBearerAttr);
        } else {
            NrQos qos = (NrQos) session.getQos();
            NrQosSessionAttributes nrQosAttr =
                    new NrQosSessionAttributes(qos.get5Qi(), qos.getQfi(),
                            qos.getUplinkBandwidth().getMaxBitrateKbps(),
                            qos.getDownlinkBandwidth().getMaxBitrateKbps(),
                            qos.getDownlinkBandwidth().getGuaranteedBitrateKbps(),
                            qos.getUplinkBandwidth().getGuaranteedBitrateKbps(),
                            qos.getAveragingWindow(), remoteAddresses);
            mNetworkAgent.notifyQosSessionAvailable(
                    callbackId, session.getQosBearerSessionId(), nrQosAttr);
        }

        // added to notify to Metric for passing DedicatedBearerEstablished info
        notifyMetricDedicatedBearerListenerBearerUpdateSession(callbackId, session);

        log("sendSessionAvailable, callbackId=" + callbackId);
    }

    private void sendSessionLost(int callbackId, @NonNull QosBearerSession session) {
        mNetworkAgent.notifyQosSessionLost(callbackId, session.getQosBearerSessionId(),
                session.getQos() instanceof EpsQos
                        ? QosSession.TYPE_EPS_BEARER : QosSession.TYPE_NR_BEARER);
        log("sendSessionLost, callbackId=" + callbackId);
    }

    private void notifyMetricDedicatedBearerListenerAdded(final int callbackId,
            final @NonNull QosBearerSession session) {

        final int slotId = mPhoneId;
        final int rat = getRatInfoFromSessionInfo(session);
        final int qci = getQCIFromSessionInfo(session);

        mRcsStats.onImsDedicatedBearerListenerAdded(callbackId, slotId, rat, qci);
    }

    private void notifyMetricDedicatedBearerListenerBearerUpdateSession(
            final int callbackId, final @NonNull QosBearerSession session) {
        mRcsStats.onImsDedicatedBearerListenerUpdateSession(callbackId, mPhoneId,
                getRatInfoFromSessionInfo(session), getQCIFromSessionInfo(session), true);
    }

    private void notifyMetricDedicatedBearerListenerRemoved(final int callbackId) {
        mRcsStats.onImsDedicatedBearerListenerRemoved(callbackId);
    }

    private int getQCIFromSessionInfo(final QosBearerSession session) {
        if (session.getQos() instanceof EpsQos) {
            return ((EpsQos) session.getQos()).getQci();
        } else if (session.getQos() instanceof NrQos) {
            return ((NrQos) session.getQos()).get5Qi();
        }

        return 0;
    }

    private int getRatInfoFromSessionInfo(final QosBearerSession session) {
        if (session.getQos() instanceof EpsQos) {
            return TelephonyManager.NETWORK_TYPE_LTE;
        } else if (session.getQos() instanceof NrQos) {
            return TelephonyManager.NETWORK_TYPE_NR;
        }

        return 0;
    }

    private void notifyMetricDedicatedBearerEvent(final QosBearerSession session,
            final IFilter filter, final int bearerState) {

        int ratAtEnd;
        int qci;
        boolean localConnectionInfoReceived = false;
        boolean remoteConnectionInfoReceived = false;

        QosBearerFilter qosBearerFilter = getMatchingQosBearerFilter(session, filter);
        if (session.getQos() instanceof EpsQos) {
            ratAtEnd = TelephonyManager.NETWORK_TYPE_LTE;
            qci = ((EpsQos) session.getQos()).getQci();
        } else if (session.getQos() instanceof NrQos) {
            ratAtEnd = TelephonyManager.NETWORK_TYPE_NR;
            qci = ((NrQos) session.getQos()).get5Qi();
        } else {
            return;
        }

        if (qosBearerFilter != null) {
            if (!qosBearerFilter.getLocalAddresses().isEmpty()
                    && qosBearerFilter.getLocalPortRange().isValid()) {
                localConnectionInfoReceived = true;
            }
            if (!qosBearerFilter.getRemoteAddresses().isEmpty()
                    && qosBearerFilter.getRemotePortRange().isValid()) {
                remoteConnectionInfoReceived = true;
            }
        }

        mRcsStats.onImsDedicatedBearerEvent(mPhoneId, ratAtEnd, qci, bearerState,
                localConnectionInfoReceived, remoteConnectionInfoReceived, true);
    }

    /**
     * Log debug messages.
     * @param s debug messages
     */
    private void log(@NonNull String s) {
        Rlog.d(mLogTag, s);
    }
}
