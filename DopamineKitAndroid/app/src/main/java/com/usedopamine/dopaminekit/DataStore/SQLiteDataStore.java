package com.usedopamine.dopaminekit.DataStore;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.usedopamine.dopaminekit.DataStore.Contracts.TrackedActionContract;

/**
 * Created by cuddergambino on 8/4/16.
 */

public class SQLiteDataStore extends SQLiteOpenHelper {

    public static final int DATABASE_VERSION = 2;
    public static final String DATABASE_NAME = "DopamineDB.db";

    private static SQLiteDataStore sharedInstance = null;

    public static SQLiteDataStore getInstance(Context context) {
        if (sharedInstance == null) {
            sharedInstance = new SQLiteDataStore(context);
        }
        return sharedInstance;
    }

    private SQLiteDataStore(Context context) {
        super (context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        SQLTrackedActionDataHelper.createTable(db);
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        SQLTrackedActionDataHelper.dropTable(db);

        onCreate(db);
    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }
}
