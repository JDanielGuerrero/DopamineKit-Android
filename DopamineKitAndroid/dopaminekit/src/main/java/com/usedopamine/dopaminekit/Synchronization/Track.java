package com.usedopamine.dopaminekit.Synchronization;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.Nullable;

import com.usedopamine.dopaminekit.DataStore.Contracts.TrackedActionContract;
import com.usedopamine.dopaminekit.DataStore.SQLTrackedActionDataHelper;
import com.usedopamine.dopaminekit.DataStore.SQLiteDataStore;
import com.usedopamine.dopaminekit.DopamineKit;
import com.usedopamine.dopaminekit.RESTfulAPI.DopamineAPI;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.Callable;

/**
 * Created by cuddergambino on 9/4/16.
 */

class Track extends ContextWrapper implements Callable<Integer> {

    private static Track sharedInstance;

    private DopamineAPI dopamineAPI;
    private SQLiteDatabase sqlDB;
    private SharedPreferences preferences;
    private final String preferencesName = "com.usedopamine.synchronization.track";
    private final String sizeKey = "size";
    private final String sizeToSyncKey = "sizeToSync";
    private final String timerStartsAtKey = "timerStartsAt";
    private final String timerExpiresInKey = "timerExpiresIn";

    private int sizeToSync;
    private long timerStartsAt;
    private long timerExpiresIn;

    private final Object apiSyncLock = new Object();
    private Boolean syncInProgress = false;

    static Track getSharedInstance(Context base) {
        if (sharedInstance == null) {
            sharedInstance = new Track(base);
        }
        return sharedInstance;
    }

    private Track(Context base) {
        super(base);
        sqlDB = SQLiteDataStore.getInstance(base).getWritableDatabase();
        preferences = getSharedPreferences(preferencesName, 0);
        sizeToSync = preferences.getInt(sizeToSyncKey, 15);
        timerStartsAt = preferences.getLong(timerStartsAtKey, System.currentTimeMillis());
        timerExpiresIn = preferences.getLong(timerExpiresInKey, 172800000);
    }

    /**
     * @return Whether a sync should be started
     */
    boolean isTriggered() {
        return timerDidExpire() || isSizeToSync();
    }

    /**
     * Updates the sync triggers.
     *
     * @param size      The number of tracked actions to trigger a sync
     * @param startTime The start time for a sync timer
     * @param expiresIn The timer length, in ms, for a sync timer
     */
    void updateTriggers(@Nullable Integer size, @Nullable Long startTime, @Nullable Long expiresIn) {

        if (size != null) {
            sizeToSync = size;
        }
        if (startTime != null) {
            timerStartsAt = startTime;
        }
        if (expiresIn != null) {
            timerExpiresIn = expiresIn;
        }

        preferences.edit()
                .putInt(sizeToSyncKey, sizeToSync)
                .putLong(timerStartsAtKey, timerStartsAt)
                .putLong(timerExpiresInKey, timerExpiresIn)
                .apply();
    }

    /**
     * Clears the saved track sync triggers.
     */
    void removeTriggers() {
        sizeToSync = 15;
        timerStartsAt = System.currentTimeMillis();
        timerExpiresIn = 172800000;
        preferences.edit().clear().apply();
    }

    /**
     * This function returns a snapshot of this instance as a JSONObject.
     *
     * @return A JSONObject containing the size and sync triggers
     */
    JSONObject jsonForTriggers() {
        JSONObject json = new JSONObject();
        try {
            json.put(sizeKey, SQLTrackedActionDataHelper.count(sqlDB));
            json.put(sizeToSyncKey, sizeToSync);
            json.put(timerStartsAtKey, timerStartsAt);
            json.put(timerExpiresInKey, timerExpiresIn);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    private boolean timerDidExpire() {
        long currentTime = System.currentTimeMillis();
        boolean isExpired = currentTime >= (timerStartsAt + timerExpiresIn);
        return isExpired;
    }

    private boolean isSizeToSync() {
        int count = SQLTrackedActionDataHelper.count(sqlDB);
        boolean isSize = count >= sizeToSync;
        DopamineKit.debugLog("Track", "Track has batched " + count + "/" + sizeToSync + " actions" + (isSize ? " so needs to sync..." : "."));
        return isSize;
    }

    /**
     * Stores a tracked action to be synced over the DopamineAPI at a later time.
     *
     * @param action The action to be stored
     */
    void store(DopeAction action) {
        String metaData = (action.metaData == null) ? null : action.metaData.toString();
        long rowId = SQLTrackedActionDataHelper.insert(sqlDB, new TrackedActionContract(
                0, action.actionID, metaData, action.utc, action.timezoneOffset
        ));
//        DopamineKit.debugLog("SQL Tracked Actions", "Inserted into row " + rowId);

    }

    @Override
    public Integer call() throws Exception {
        if (syncInProgress) {
            DopamineKit.debugLog("TrackSyncer", "Track sync already happening");
            return 0;
        } else {
            synchronized (apiSyncLock) {
                if (syncInProgress) {
                    DopamineKit.debugLog("TrackSyncer", "Track sync already happening");
                    return 0;
                } else {
                    try {
                        syncInProgress = true;
                        DopamineKit.debugLog("TrackSyncer", "Beginning tracker sync!");

                        final ArrayList<TrackedActionContract> sqlActions = SQLTrackedActionDataHelper.findAll(sqlDB);
                        if (sqlActions.size() == 0) {
                            DopamineKit.debugLog("TrackSyncer", "No tracked actions to be synced.");
                            updateTriggers(null, System.currentTimeMillis(), null);
                            return 0;
                        } else {
                            DopamineKit.debugLog("TrackSyncer", sqlActions.size() + " tracked actions to be synced.");
                            JSONObject apiResponse = DopamineAPI.track(this, sqlActions);
                            if (apiResponse != null) {
                                int statusCode = apiResponse.optInt("status", -2);
                                if (statusCode == 200) {
                                    for (int i = 0; i < sqlActions.size(); i++) {
                                        SQLTrackedActionDataHelper.delete(sqlDB, sqlActions.get(i));
                                    }
                                    updateTriggers(null, System.currentTimeMillis(), null);
                                }
                                return statusCode;
                            } else {
                                DopamineKit.debugLog("TrackSyncer", "Something went wrong making the call...");
                                return -1;
                            }
                        }
                    } finally {
                        syncInProgress = false;
                    }
                }
            }
        }
    }

}
