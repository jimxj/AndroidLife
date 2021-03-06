/*
 * Copyright (C) 2017 The Android Open Source Project
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

package alpha1.android.arch.persistence.room;

import alpha1.android.arch.core.executor.AppToolkitTaskExecutor;
import alpha1.android.arch.core.internal.SafeIterableMap;
import alpha1.android.arch.persistence.db.SupportSQLiteDatabase;
import alpha1.android.arch.persistence.db.SupportSQLiteStatement;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.VisibleForTesting;
import android.support.v4.util.ArrayMap;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * InvalidationTracker keeps a list of tables modified by queries and notifies its callbacks about
 * these tables.
 */
// We create an in memory table with (version, table_id) where version is an auto-increment primary
// key and a table_id (hardcoded int from initialization).
// ObservedTableTracker tracks list of tables we should be watching (e.g. adding triggers for).
// Before each beginTransaction, RoomDatabase invokes InvalidationTracker to sync trigger states.
// After each endTransaction, RoomDatabase invokes InvalidationTracker to refresh invalidated
// tables.
// Each update on one of the observed tables triggers an insertion into this table, hence a
// new version.
// Unfortunately, we cannot override the previous row because sqlite uses the conflict resolution
// of the outer query (the thing that triggered us) so we do a cleanup as we sync instead of letting
// SQLite override the rows.
// https://sqlite.org/lang_createtrigger.html:  An ON CONFLICT clause may be specified as part of an
// UPDATE or INSERT action within the body of the trigger. However if an ON CONFLICT clause is
// specified as part of the statement causing the trigger to fire, then conflict handling policy of
// the outer statement is used instead.
public class InvalidationTracker {

    private static final String[] TRIGGERS = new String[] { "UPDATE", "DELETE", "INSERT" };

    private static final String UPDATE_TABLE_NAME = "room_table_modification_log";

    private static final String VERSION_COLUMN_NAME = "version";
    @VisibleForTesting
    // We always clean before selecting so it is unlikely to have the same row twice and if we
    // do, it is not a big deal, just more data in the cursor.
    static final String SELECT_UPDATED_TABLES_SQL = "SELECT * FROM " + UPDATE_TABLE_NAME
        + " WHERE " + VERSION_COLUMN_NAME
        + "  > ? ORDER BY " + VERSION_COLUMN_NAME + " ASC;";
    private static final String TABLE_ID_COLUMN_NAME = "table_id";
    @VisibleForTesting
    static final String CLEANUP_SQL = "DELETE FROM " + UPDATE_TABLE_NAME
        + " WHERE " + VERSION_COLUMN_NAME + " NOT IN( SELECT MAX("
        + VERSION_COLUMN_NAME + ") FROM " + UPDATE_TABLE_NAME
        + " GROUP BY " + TABLE_ID_COLUMN_NAME + ")";
    private static final String CREATE_VERSION_TABLE_SQL = "CREATE TEMP TABLE " + UPDATE_TABLE_NAME
        + "(" + VERSION_COLUMN_NAME
        + " INTEGER PRIMARY KEY AUTOINCREMENT, "
        + TABLE_ID_COLUMN_NAME
        + " INTEGER)";
    // should be accessed with synchronization only.
    @VisibleForTesting
    final SafeIterableMap<Observer, ObserverWrapper> mObserverMap = new SafeIterableMap<>();
    private final RoomDatabase mDatabase;
    @NonNull
    @VisibleForTesting
    ArrayMap<String, Integer> mTableIdLookup;
    @NonNull
    @VisibleForTesting
    long[] mTableVersions;
    AtomicBoolean mPendingRefresh = new AtomicBoolean(false);
    private String[] mTableNames;
    private String[] mQueryArgs = new String[1];
    // max id in the last syc
    private long mMaxVersion = -1;
    private volatile boolean mInitialized = false;
    private volatile SupportSQLiteStatement mCleanupStatement;
    @VisibleForTesting
    Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!ensureInitialization()) {
                return;
            }
            if (mDatabase.inTransaction()
                || !mPendingRefresh.compareAndSet(true, false)) {
                // no pending refresh
                return;
            }
            boolean hasUpdatedTable = false;
            try {
                mCleanupStatement.executeUpdateDelete();
                mQueryArgs[0] = Long.toString(mMaxVersion);
                Cursor cursor = mDatabase.query(SELECT_UPDATED_TABLES_SQL, mQueryArgs);
                //noinspection TryFinallyCanBeTryWithResources
                try {
                    while (cursor.moveToNext()) {
                        final long version = cursor.getLong(0);
                        final int tableId = cursor.getInt(1);

                        mTableVersions[tableId] = version;
                        hasUpdatedTable = true;
                        // result is ordered so we can safely do this assignment
                        mMaxVersion = version;
                    }
                } finally {
                    cursor.close();
                }
            } catch (IllegalStateException | SQLiteException exception) {
                // may happen if db is closed. just log.
                Log.e(Room.LOG_TAG, "Cannot run invalidation tracker. Is the db closed?",
                    exception);
            }
            if (hasUpdatedTable) {
                synchronized (mObserverMap) {
                    for (Map.Entry<Observer, ObserverWrapper> entry : mObserverMap) {
                        entry.getValue().checkForInvalidation(mTableVersions);
                    }
                }
            }
        }
    };
    private ObservedTableTracker mObservedTableTracker;
    private Runnable mSyncTriggers = new Runnable() {
        @Override
        public void run() {
            if (mDatabase.inTransaction()) {
                // we won't run this inside another transaction.
                return;
            }
            if (!ensureInitialization()) {
                return;
            }
            try {
                // This method runs in a while loop because while changes are synced to db, another
                // runnable may be skipped. If we cause it to skip, we need to do its work.
                while (true) {
                    // there is a potential race condition where another mSyncTriggers runnable
                    // can start running right after we get the tables list to sync.
                    final int[] tablesToSync = mObservedTableTracker.getTablesToSync();
                    if (tablesToSync == null) {
                        return;
                    }
                    final int limit = tablesToSync.length;
                    final SupportSQLiteDatabase writableDatabase = mDatabase.getOpenHelper()
                        .getWritableDatabase();
                    try {
                        writableDatabase.beginTransaction();
                        for (int tableId = 0; tableId < limit; tableId++) {
                            switch (tablesToSync[tableId]) {
                                case ObservedTableTracker.ADD:
                                    startTrackingTable(writableDatabase, tableId);
                                    break;
                                case ObservedTableTracker.REMOVE:
                                    stopTrackingTable(writableDatabase, tableId);
                                    break;
                            }
                        }
                        writableDatabase.setTransactionSuccessful();
                    } finally {
                        writableDatabase.endTransaction();
                    }
                    mObservedTableTracker.onSyncCompleted();
                }
            } catch (IllegalStateException | SQLiteException exception) {
                // may happen if db is closed. just log.
                Log.e(Room.LOG_TAG, "Cannot run invalidation tracker. Is the db closed?",
                    exception);
            }
        }
    };


    /**
     * Used by the generated code.
     *
     * @hide
     */
    @SuppressWarnings("WeakerAccess")
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public InvalidationTracker(RoomDatabase database, String... tableNames) {
        mDatabase = database;
        mObservedTableTracker = new ObservedTableTracker(tableNames.length);
        mTableIdLookup = new ArrayMap<>();
        final int size = tableNames.length;
        mTableNames = new String[size];
        for (int id = 0; id < size; id++) {
            final String tableName = tableNames[id].toLowerCase(Locale.US);
            mTableIdLookup.put(tableName, id);
            mTableNames[id] = tableName;
        }
        mTableVersions = new long[tableNames.length];
        Arrays.fill(mTableVersions, 0);
    }


    private static void appendTriggerName(StringBuilder builder, String tableName,
                                          String triggerType) {
        builder.append("room_table_modification_trigger_")
            .append(tableName)
            .append("_")
            .append(triggerType);
    }


    /**
     * Internal method to initialize table tracking.
     * <p>
     * You should never call this method, it is called by the generated code.
     */
    void internalInit(SupportSQLiteDatabase database) {
        synchronized (this) {
            if (mInitialized) {
                Log.e(Room.LOG_TAG, "Invalidation tracker is initialized twice :/.");
                return;
            }

            database.beginTransaction();
            try {
                database.execSQL("PRAGMA temp_store = MEMORY;");
                database.execSQL("PRAGMA recursive_triggers='ON';");
                database.execSQL(CREATE_VERSION_TABLE_SQL);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            mCleanupStatement = database.compileStatement(CLEANUP_SQL);
            mInitialized = true;
        }
    }


    private void stopTrackingTable(SupportSQLiteDatabase writableDb, int tableId) {
        final String tableName = mTableNames[tableId];
        StringBuilder stringBuilder = new StringBuilder();
        for (String trigger : TRIGGERS) {
            stringBuilder.setLength(0);
            stringBuilder.append("DROP TRIGGER IF EXISTS ");
            appendTriggerName(stringBuilder, tableName, trigger);
            writableDb.execSQL(stringBuilder.toString());
        }
    }


    private void startTrackingTable(SupportSQLiteDatabase writableDb, int tableId) {
        final String tableName = mTableNames[tableId];
        StringBuilder stringBuilder = new StringBuilder();
        for (String trigger : TRIGGERS) {
            stringBuilder.setLength(0);
            stringBuilder.append("CREATE TEMP TRIGGER IF NOT EXISTS ");
            appendTriggerName(stringBuilder, tableName, trigger);
            stringBuilder.append(" AFTER ")
                .append(trigger)
                .append(" ON ")
                .append(tableName)
                .append(" BEGIN INSERT OR REPLACE INTO ")
                .append(UPDATE_TABLE_NAME)
                .append(" VALUES(null, ")
                .append(tableId)
                .append("); END");
            writableDb.execSQL(stringBuilder.toString());
        }
    }


    /**
     * Adds the given observer to the observers list and it will be notified if any table it
     * observes changes.
     * <p>
     * Database changes are pulled on another thread so in some race conditions, the observer might
     * be invoked for changes that were done before it is added.
     * <p>
     * If the observer already exists, this is a no-op call.
     * <p>
     * If one of the tables in the Observer does not exist in the database, this method throws an
     * {@link IllegalArgumentException}.
     *
     * @param observer The observer which listens the database for changes.
     */
    public void addObserver(Observer observer) {
        final String[] tableNames = observer.mTables;
        int[] tableIds = new int[tableNames.length];
        final int size = tableNames.length;
        long[] versions = new long[tableNames.length];

        // TODO sync versions ?
        for (int i = 0; i < size; i++) {
            Integer tableId = mTableIdLookup.get(tableNames[i].toLowerCase(Locale.US));
            if (tableId == null) {
                throw new IllegalArgumentException("There is no table with name " + tableNames[i]);
            }
            tableIds[i] = tableId;
            versions[i] = mMaxVersion;
        }
        ObserverWrapper wrapper = new ObserverWrapper(observer, tableIds, versions);
        ObserverWrapper currentObserver;
        synchronized (mObserverMap) {
            currentObserver = mObserverMap.putIfAbsent(observer, wrapper);
        }
        if (currentObserver == null && mObservedTableTracker.onAdded(tableIds)) {
            AppToolkitTaskExecutor.getInstance().executeOnDiskIO(mSyncTriggers);
        }
    }


    /**
     * Adds an observer but keeps a weak reference back to it.
     * <p>
     * Note that you cannot remove this observer once added. It will be automatically removed
     * when the observer is GC'ed.
     *
     * @param observer The observer to which InvalidationTracker will keep a weak reference.
     * @hide
     */
    @SuppressWarnings("unused")
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void addWeakObserver(Observer observer) {
        addObserver(new WeakObserver(this, observer));
    }


    /**
     * Removes the observer from the observers list.
     *
     * @param observer The observer to remove.
     */
    @SuppressWarnings("WeakerAccess")
    public void removeObserver(final Observer observer) {
        ObserverWrapper wrapper;
        synchronized (mObserverMap) {
            wrapper = mObserverMap.remove(observer);
        }
        if (wrapper != null && mObservedTableTracker.onRemoved(wrapper.mTableIds)) {
            AppToolkitTaskExecutor.getInstance().executeOnDiskIO(mSyncTriggers);
        }
    }


    private boolean ensureInitialization() {
        if (!mDatabase.isOpen()) {
            return false;
        }
        if (!mInitialized) {
            // trigger initialization
            mDatabase.getOpenHelper().getWritableDatabase();
        }
        if (!mInitialized) {
            Log.e(Room.LOG_TAG, "database is not initialized even though it is open");
            return false;
        }
        return true;
    }


    /**
     * Enqueues a task to refresh the list of updated tables.
     * <p>
     * This method is automatically called when {@link RoomDatabase#endTransaction()} is called but
     * if you have another connection to the database or directly use {@link
     * SupportSQLiteDatabase}, you may need to call this manually.
     */
    @SuppressWarnings("WeakerAccess")
    public void refreshVersionsAsync() {
        // TODO we should consider doing this sync instead of async.
        if (mPendingRefresh.compareAndSet(false, true)) {
            AppToolkitTaskExecutor.getInstance().executeOnDiskIO(mRefreshRunnable);
        }
    }


    /**
     * Called by RoomDatabase before each beginTransaction call.
     * <p>
     * It is important that pending trigger changes are applied to the database before any query
     * runs. Otherwise, we may miss some changes.
     * <p>
     * This api should eventually be public.
     */
    void syncTriggers() {
        mSyncTriggers.run();
    }


    /**
     * Wraps an observer and keeps the table information.
     * <p>
     * Internally table ids are used which may change from database to database so the table
     * related information is kept here rather than in the Observer.
     */
    @SuppressWarnings("WeakerAccess")
    static class ObserverWrapper {
        final int[] mTableIds;
        final Observer mObserver;
        private final long[] mVersions;


        ObserverWrapper(Observer observer, int[] tableIds, long[] versions) {
            mObserver = observer;
            mTableIds = tableIds;
            mVersions = versions;
        }


        void checkForInvalidation(long[] versions) {
            boolean invalid = false;
            final int size = mTableIds.length;
            for (int index = 0; index < size; index++) {
                final int tableId = mTableIds[index];
                final long newVersion = versions[tableId];
                final long currentVersion = mVersions[index];
                if (currentVersion < newVersion) {
                    mVersions[index] = newVersion;
                    invalid = true;
                }
            }
            if (invalid) {
                mObserver.onInvalidated();
            }
        }
    }


    /**
     * An observer that can listen for changes in the database.
     */
    public abstract static class Observer {
        final String[] mTables;


        /**
         * Observes the given list of tables.
         *
         * @param firstTable The table name
         * @param rest More table names
         */
        @SuppressWarnings("unused")
        protected Observer(@NonNull String firstTable, String... rest) {
            mTables = Arrays.copyOf(rest, rest.length + 1);
            mTables[rest.length] = firstTable;
        }


        /**
         * Observes the given list of tables.
         *
         * @param tables The list of tables to observe for changes.
         */
        public Observer(@NonNull String[] tables) {
            // copy tables in case user modifies them afterwards
            mTables = Arrays.copyOf(tables, tables.length);
        }


        /**
         * Called when one of the observed tables is invalidated in the database.
         */
        public abstract void onInvalidated();
    }


    /**
     * Keeps a list of tables we should observe. Invalidation tracker lazily syncs this list w/
     * triggers in the database.
     * <p>
     * This class is thread safe
     */
    static class ObservedTableTracker {
        static final int NO_OP = 0; // don't change trigger state for this table
        static final int ADD = 1; // add triggers for this table
        static final int REMOVE = 2; // remove triggers for this table

        // number of observers per table
        final long[] mTableObservers;
        // trigger state for each table at last sync
        // this field is updated when syncAndGet is called.
        final boolean[] mTriggerStates;
        // when sync is called, this field is returned. It includes actions as ADD, REMOVE, NO_OP
        final int[] mTriggerStateChanges;

        boolean mNeedsSync;

        /**
         * After we return non-null value from getTablesToSync, we expect a onSyncCompleted before
         * returning any non-null value from getTablesToSync.
         * This allows us to workaround any multi-threaded state syncing issues.
         */
        boolean mPendingSync;


        ObservedTableTracker(int tableCount) {
            mTableObservers = new long[tableCount];
            mTriggerStates = new boolean[tableCount];
            mTriggerStateChanges = new int[tableCount];
            Arrays.fill(mTableObservers, 0);
            Arrays.fill(mTriggerStates, false);
        }


        /**
         * @return true if # of triggers is affected.
         */
        boolean onAdded(int... tableIds) {
            boolean needTriggerSync = false;
            synchronized (this) {
                for (int tableId : tableIds) {
                    final long prevObserverCount = mTableObservers[tableId];
                    mTableObservers[tableId] = prevObserverCount + 1;
                    if (prevObserverCount == 0) {
                        mNeedsSync = true;
                        needTriggerSync = true;
                    }
                }
            }
            return needTriggerSync;
        }


        /**
         * @return true if # of triggers is affected.
         */
        boolean onRemoved(int... tableIds) {
            boolean needTriggerSync = false;
            synchronized (this) {
                for (int tableId : tableIds) {
                    final long prevObserverCount = mTableObservers[tableId];
                    mTableObservers[tableId] = prevObserverCount - 1;
                    if (prevObserverCount == 1) {
                        mNeedsSync = true;
                        needTriggerSync = true;
                    }
                }
            }
            return needTriggerSync;
        }


        /**
         * If this returns non-null, you must call onSyncCompleted.
         *
         * @return int[] An int array where the index for each tableId has the action for that
         * table.
         */
        @Nullable
        int[] getTablesToSync() {
            synchronized (this) {
                if (!mNeedsSync || mPendingSync) {
                    return null;
                }
                final int tableCount = mTableObservers.length;
                for (int i = 0; i < tableCount; i++) {
                    final boolean newState = mTableObservers[i] > 0;
                    if (newState != mTriggerStates[i]) {
                        mTriggerStateChanges[i] = newState ? ADD : REMOVE;
                    } else {
                        mTriggerStateChanges[i] = NO_OP;
                    }
                    mTriggerStates[i] = newState;
                }
                mPendingSync = true;
                mNeedsSync = false;
                return mTriggerStateChanges;
            }
        }


        /**
         * if getTablesToSync returned non-null, the called should call onSyncCompleted once it
         * is done.
         */
        void onSyncCompleted() {
            synchronized (this) {
                mPendingSync = false;
            }
        }
    }


    /**
     * An Observer wrapper that keeps a weak reference to the given object.
     * <p>
     * This class with automatically unsubscribe when the wrapped observer goes out of memory.
     */
    static class WeakObserver extends Observer {
        final InvalidationTracker mTracker;
        final WeakReference<Observer> mDelegateRef;


        WeakObserver(InvalidationTracker tracker, Observer delegate) {
            super(delegate.mTables);
            mTracker = tracker;
            mDelegateRef = new WeakReference<>(delegate);
        }


        @Override
        public void onInvalidated() {
            final Observer observer = mDelegateRef.get();
            if (observer == null) {
                mTracker.removeObserver(this);
            } else {
                observer.onInvalidated();
            }
        }
    }
}
