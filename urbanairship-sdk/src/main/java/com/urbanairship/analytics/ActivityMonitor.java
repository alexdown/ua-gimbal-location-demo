/*
Copyright 2009-2015 Urban Airship Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
this list of conditions and the following disclaimer in the documentation
and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE URBAN AIRSHIP INC ``AS IS'' AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
EVENT SHALL URBAN AIRSHIP INC OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.urbanairship.analytics;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.urbanairship.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * This class monitors all activities
 */
class ActivityMonitor {

    // Brief delay, to give the app a chance to perform screen rotation cleanup
    private final static int BACKGROUND_DELAY_MS = 2000;

    private final Set<Integer> startedActivities = new HashSet<>();
    private final Handler handler;
    private Listener listener;
    private long lastActivityStopTimeStamp;

    private final Runnable notifyBackgroundRunnable = new Runnable() {
        @Override
        public void run() {
            if (startedActivities.isEmpty()) {
                synchronized (this) {
                    if (listener != null) {
                        listener.onBackground(lastActivityStopTimeStamp);
                    }
                }
            }
        }
    };

    ActivityMonitor() {
        handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Sets the listener for activity events.
     *
     * @param listener The activity event listener.
     */
    void setListener(Listener listener) {
        synchronized (this) {
            this.listener = listener;
        }
    }

    /**
     * Tracks when an activity is started
     * @param activity The activity
     * @param timeStamp The time the activity started in milliseconds
     */
    void activityStarted(Activity activity, long timeStamp) {
        if (startedActivities.contains(activity.hashCode())) {
            Logger.warn("Analytics.startActivity was already called for activity: " + activity);
            return;
        }

        handler.removeCallbacks(notifyBackgroundRunnable);

        startedActivities.add(activity.hashCode());

        if (startedActivities.size() == 1) {
            synchronized (this) {
                if (listener != null) {
                    listener.onForeground(timeStamp);
                }
            }
        }
    }

    /**
     * Tracks when an activity is stopped
     * @param activity The activity
     * @param timeStamp The time the activity stopped in milliseconds
     */
    void activityStopped(Activity activity, long timeStamp) {
        if (!startedActivities.contains(activity.hashCode())) {
            Logger.warn("Analytics.stopActivity called for an activity that was not started: " + activity);
            return;
        }

        handler.removeCallbacks(notifyBackgroundRunnable);

        startedActivities.remove(activity.hashCode());
        lastActivityStopTimeStamp = timeStamp;

        if (startedActivities.isEmpty()) {
            handler.postDelayed(notifyBackgroundRunnable, BACKGROUND_DELAY_MS);
        }
    }

    /**
     * The listener for activity events.
     */
    static abstract class Listener {
        /**
         * Called when the app is foregrounded from an activity.
         *
         * @param timeMS The time the app is foregrounded.
         */
        abstract void onForeground(long timeMS);

        /**
         * Called when the app is backgrounded from an activity.
         *
         * @param timeMS The time the app is backgrounded.
         */
        abstract void onBackground(long timeMS);
    }
}
