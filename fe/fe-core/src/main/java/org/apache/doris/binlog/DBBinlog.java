// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.binlog;

import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.Pair;
import org.apache.doris.thrift.TBinlog;
import org.apache.doris.thrift.TBinlogType;
import org.apache.doris.thrift.TStatus;
import org.apache.doris.thrift.TStatusCode;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DBBinlog {
    private static final Logger LOG = LogManager.getLogger(BinlogManager.class);

    private long dbId;
    // guard for allBinlogs && tableBinlogMap
    private ReentrantReadWriteLock lock;
    // all binlogs contain table binlogs && create table binlog etc ...
    private TreeSet<TBinlog> allBinlogs;
    // table binlogs
    private Map<Long, TableBinlog> tableBinlogMap;

    // Pair(commitSeq, timestamp), used for gc
    // need UpsertRecord to add timestamps for gc
    private List<Pair<Long, Long>> timestamps;

    private List<TBinlog> tableDummyBinlogs;

    public DBBinlog(TBinlog binlog) {
        lock = new ReentrantReadWriteLock();
        this.dbId = binlog.getDbId();

        // allBinlogs treeset order by commitSeq
        allBinlogs = Sets.newTreeSet(Comparator.comparingLong(TBinlog::getCommitSeq));
        tableDummyBinlogs = Lists.newArrayList();
        tableBinlogMap = Maps.newHashMap();
        timestamps = Lists.newArrayList();

        TBinlog dummy;
        if (binlog.getType() == TBinlogType.DUMMY) {
            dummy = binlog;
        } else {
            dummy = BinlogUtils.newDummyBinlog(dbId, -1);
        }
        allBinlogs.add(dummy);
    }

    public static DBBinlog recoverDbBinlog(TBinlog dbDummy, List<TBinlog> tableDummies, boolean dbBinlogEnable) {
        DBBinlog dbBinlog = new DBBinlog(dbDummy);
        for (TBinlog tableDummy : tableDummies) {
            long tableId = tableDummy.getBelong();
            if (!dbBinlogEnable && !BinlogUtils.tableEnabledBinlog(dbBinlog.getDbId(), tableId)) {
                continue;
            }
            dbBinlog.tableBinlogMap.put(tableId, new TableBinlog(tableDummy, tableId));
            dbBinlog.tableDummyBinlogs.add(tableDummy);
        }

        return dbBinlog;
    }

    // not thread safety, do this without lock
    public void recoverBinlog(TBinlog binlog, boolean dbBinlogEnable) {
        List<Long> tableIds = binlog.getTableIds();

        if (binlog.getTimestamp() > 0 && dbBinlogEnable) {
            timestamps.add(Pair.of(binlog.getCommitSeq(), binlog.getTimestamp()));
        }

        allBinlogs.add(binlog);

        if (tableIds == null) {
            return;
        }

        for (long tableId : tableIds) {
            TableBinlog tableBinlog = getTableBinlog(binlog, tableId, dbBinlogEnable);
            if (tableBinlog == null) {
                continue;
            }
            tableBinlog.recoverBinlog(binlog);
        }
    }

    private TableBinlog getTableBinlog(TBinlog binlog, long tableId, boolean dbBinlogEnable) {
        TableBinlog tableBinlog = tableBinlogMap.get(tableId);
        if (tableBinlog == null) {
            if (dbBinlogEnable || BinlogUtils.tableEnabledBinlog(dbId, tableId)) {
                tableBinlog = new TableBinlog(binlog, tableId);
                tableBinlogMap.put(tableId, tableBinlog);
                tableDummyBinlogs.add(tableBinlog.getDummyBinlog());
            }
        }
        return tableBinlog;
    }

    public void addBinlog(TBinlog binlog, boolean dbBinlogEnable) {
        List<Long> tableIds = binlog.getTableIds();
        lock.writeLock().lock();
        try {
            if (binlog.getTimestamp() > 0 && dbBinlogEnable) {
                timestamps.add(Pair.of(binlog.getCommitSeq(), binlog.getTimestamp()));
            }

            allBinlogs.add(binlog);

            if (tableIds == null) {
                return;
            }

            // HACK: for metadata fix
            if (!binlog.isSetType()) {
                return;
            }
            switch (binlog.getType()) {
                case CREATE_TABLE:
                    return;
                case DROP_TABLE:
                    return;
                default:
                    break;
            }

            for (long tableId : tableIds) {
                TableBinlog tableBinlog = getTableBinlog(binlog, tableId, dbBinlogEnable);
                if (tableBinlog != null) {
                    tableBinlog.addBinlog(binlog);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        lock.readLock().lock();
        try {
            LOG.info("[deadlinefen] after add, db {} binlogs: {}, dummys: {}", dbId, allBinlogs, tableDummyBinlogs);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getDbId() {
        return dbId;
    }

    public Pair<TStatus, TBinlog> getBinlog(long tableId, long prevCommitSeq) {
        TStatus status = new TStatus(TStatusCode.OK);
        lock.readLock().lock();
        try {
            if (tableId >= 0) {
                TableBinlog tableBinlog = tableBinlogMap.get(tableId);
                if (tableBinlog == null) {
                    status.setStatusCode(TStatusCode.BINLOG_NOT_FOUND_TABLE);
                    return Pair.of(status, null);
                }
                return tableBinlog.getBinlog(prevCommitSeq);
            }

            return BinlogUtils.getBinlog(allBinlogs, prevCommitSeq);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Pair<TStatus, Long> getBinlogLag(long tableId, long prevCommitSeq) {
        TStatus status = new TStatus(TStatusCode.OK);
        lock.readLock().lock();
        try {
            if (tableId >= 0) {
                TableBinlog tableBinlog = tableBinlogMap.get(tableId);
                if (tableBinlog == null) {
                    status.setStatusCode(TStatusCode.BINLOG_NOT_FOUND_TABLE);
                    return Pair.of(status, null);
                }
                return tableBinlog.getBinlogLag(prevCommitSeq);
            }

            return BinlogUtils.getBinlogLag(allBinlogs, prevCommitSeq);
        } finally {
            lock.readLock().unlock();
        }
    }

    public BinlogTombstone gc() {
        // check db
        Database db = Env.getCurrentInternalCatalog().getDbNullable(dbId);
        if (db == null) {
            LOG.error("db not found. dbId: {}", dbId);
            return null;
        }

        boolean dbBinlogEnable = db.getBinlogConfig().isEnable();
        BinlogTombstone tombstone;
        if (dbBinlogEnable) {
            // db binlog is enabled, only one binlogTombstones
            long ttlSeconds = db.getBinlogConfig().getTtlSeconds();
            long expiredMs = BinlogUtils.getExpiredMs(ttlSeconds);

            tombstone = dbBinlogEnableGc(expiredMs);
        } else {
            tombstone = dbBinlogDisableGc(db);
        }

        return tombstone;
    }

    private BinlogTombstone collectTableTombstone(List<BinlogTombstone> tableTombstones) {
        if (tableTombstones.isEmpty()) {
            return null;
        }

        List<Long> tableIds = Lists.newArrayList();
        long largestExpiredCommitSeq = -1;
        BinlogTombstone dbTombstone = new BinlogTombstone(dbId, tableIds, -1);
        for (BinlogTombstone tableTombstone : tableTombstones) {
            long commitSeq = tableTombstone.getCommitSeq();
            if (largestExpiredCommitSeq < commitSeq) {
                largestExpiredCommitSeq = commitSeq;
            }
            Map<Long, UpsertRecord.TableRecord> tableVersionMap = tableTombstone.getTableVersionMap();
            if (tableVersionMap.size() > 1) {
                LOG.warn("tableVersionMap size is greater than 1. tableVersionMap: {}", tableVersionMap);
            }
            tableIds.addAll(tableTombstone.getTableIds());
            dbTombstone.addTableRecord(tableVersionMap);
        }

        dbTombstone.setCommitSeq(largestExpiredCommitSeq);

        return dbTombstone;
    }

    private BinlogTombstone dbBinlogDisableGc(Database db) {
        List<BinlogTombstone> tombstones = Lists.newArrayList();
        List<TableBinlog> tableBinlogs;

        lock.readLock().lock();
        try {
            tableBinlogs = Lists.newArrayList(tableBinlogMap.values());
        } finally {
            lock.readLock().unlock();
        }

        for (TableBinlog tableBinlog : tableBinlogs) {
            BinlogTombstone tombstone = tableBinlog.gc(db);
            if (tombstone != null) {
                tombstones.add(tombstone);
            }
        }
        BinlogTombstone tombstone = collectTableTombstone(tombstones);
        if (tombstone != null) {
            removeExpiredMetaData(tombstone.getCommitSeq());

            lock.readLock().lock();
            try {
                LOG.info("[deadlinefen] after gc, db {} binlogs: {}, tombstone.seq: {}, dummys: {}",
                        dbId, allBinlogs, tombstone.getCommitSeq(), tableDummyBinlogs);
            } finally {
                lock.readLock().unlock();
            }
        }

        return tombstone;
    }

    private void removeExpiredMetaData(long largestExpiredCommitSeq) {
        lock.writeLock().lock();
        try {
            Iterator<TBinlog> binlogIter = allBinlogs.iterator();
            TBinlog dummy = binlogIter.next();
            boolean foundFirstUsingBinlog = false;
            long lastCommitSeq = -1;

            while (binlogIter.hasNext()) {
                TBinlog binlog = binlogIter.next();
                long commitSeq = binlog.getCommitSeq();
                if (commitSeq <= largestExpiredCommitSeq) {
                    if (binlog.table_ref <= 0) {
                        binlogIter.remove();
                        if (!foundFirstUsingBinlog) {
                            lastCommitSeq = commitSeq;
                        }
                    } else {
                        foundFirstUsingBinlog = true;
                    }
                } else {
                    break;
                }
            }

            if (lastCommitSeq != -1) {
                dummy.setCommitSeq(lastCommitSeq);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private BinlogTombstone dbBinlogEnableGc(long expiredMs) {
        // step 1: get current tableBinlog info and expiredCommitSeq
        long expiredCommitSeq = -1;
        lock.readLock().lock();
        try {
            Iterator<Pair<Long, Long>> timeIter = timestamps.iterator();
            while (timeIter.hasNext()) {
                Pair<Long, Long> pair = timeIter.next();
                if (pair.second <= expiredMs) {
                    expiredCommitSeq = pair.first;
                    timeIter.remove();
                } else {
                    break;
                }
            }

            Iterator<TBinlog> binlogIter = allBinlogs.iterator();
            TBinlog dummy = binlogIter.next();
            dummy.setCommitSeq(expiredCommitSeq);

            while (binlogIter.hasNext()) {
                TBinlog binlog = binlogIter.next();
                if (binlog.getCommitSeq() <= expiredCommitSeq) {
                    binlogIter.remove();
                } else {
                    break;
                }
            }
        } finally {
            lock.readLock().unlock();
        }

        if (expiredCommitSeq == -1) {
            return null;
        }

        // step 2: gc every tableBinlog in dbBinlog, get table tombstone to complete db tombstone
        List<BinlogTombstone> tableTombstones = Lists.newArrayList();
        for (TableBinlog tableBinlog : tableBinlogMap.values()) {
            // step 2.1: gc tableBinlog，and get table tombstone
            BinlogTombstone tableTombstone = tableBinlog.gc(expiredCommitSeq);
            if (tableTombstone != null) {
                tableTombstones.add(tableTombstone);
            }
        }

        lock.readLock().lock();
        try {
            LOG.info("[deadlinefen] after gc, db {} binlogs: {}, tombstone.seq: {}, dummys: {}",
                    dbId, allBinlogs, expiredCommitSeq, tableDummyBinlogs);
        } finally {
            lock.readLock().unlock();
        }

        return collectTableTombstone(tableTombstones);
    }

    public void replayGc(BinlogTombstone tombstone) {
        if (tombstone.isDbBinlogTomstone()) {
            dbBinlogEnableReplayGc(tombstone);
        } else {
            dbBinlogDisableReplayGc(tombstone);
            removeExpiredMetaData(tombstone.getCommitSeq());
        }
    }

    public void dbBinlogEnableReplayGc(BinlogTombstone tombstone) {
        long largestExpiredCommitSeq = tombstone.getCommitSeq();

        lock.writeLock().lock();
        try {
            Iterator<Pair<Long, Long>> timeIter = timestamps.iterator();
            while (timeIter.hasNext()) {
                long commitSeq = timeIter.next().first;
                if (commitSeq <= largestExpiredCommitSeq) {
                    timeIter.remove();
                } else {
                    break;
                }
            }

            Iterator<TBinlog> binlogIterator = allBinlogs.iterator();
            while (binlogIterator.hasNext()) {
                TBinlog binlog = binlogIterator.next();
                if (binlog.getCommitSeq() <= largestExpiredCommitSeq) {
                    binlogIterator.remove();
                } else {
                    break;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        dbBinlogDisableReplayGc(tombstone);
    }

    public void dbBinlogDisableReplayGc(BinlogTombstone tombstone) {
        List<TableBinlog> tableBinlogs;

        lock.writeLock().lock();
        try {
            tableBinlogs = Lists.newArrayList(tableBinlogMap.values());
        } finally {
            lock.writeLock().unlock();
        }

        if (tableBinlogs.isEmpty()) {
            return;
        }

        Set<Long> tableIds = Sets.newHashSet(tombstone.getTableIds());
        long largestExpiredCommitSeq = tombstone.getCommitSeq();
        for (TableBinlog tableBinlog : tableBinlogs) {
            if (tableIds.contains(tableBinlog.getTableId())) {
                tableBinlog.replayGc(largestExpiredCommitSeq);
            }
        }
    }

    // not thread safety, do this without lock
    public void getAllBinlogs(List<TBinlog> binlogs) {
        binlogs.addAll(tableDummyBinlogs);
        binlogs.addAll(allBinlogs);
    }

    public void removeTable(long tableId) {
        lock.writeLock().lock();
        try {
            tableBinlogMap.remove(tableId);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
