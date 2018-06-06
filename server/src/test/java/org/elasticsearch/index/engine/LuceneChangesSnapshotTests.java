/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.engine;

import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.index.translog.SnapshotMatchers;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.test.IndexSettingsModule;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class LuceneChangesSnapshotTests extends EngineTestCase {
    private MapperService mapperService;

    @Before
    public void createMapper() throws Exception {
        mapperService = createMapperService("test");
    }

    public void testBasics() throws Exception {
        long fromSeqNo = randomNonNegativeLong();
        long toSeqNo = randomLongBetween(fromSeqNo, Long.MAX_VALUE);
        // Empty engine
        try (Translog.Snapshot snapshot = engine.newLuceneChangesSnapshot("test", mapperService, fromSeqNo, toSeqNo, true)) {
            IllegalStateException error = expectThrows(IllegalStateException.class, () -> drainAll(snapshot));
            assertThat(error.getMessage(),
                containsString("Not all operations between min_seqno [" + fromSeqNo + "] and max_seqno [" + toSeqNo + "] found"));
        }
        try (Translog.Snapshot snapshot = engine.newLuceneChangesSnapshot("test", mapperService, fromSeqNo, toSeqNo, false)) {
            assertThat(snapshot, SnapshotMatchers.size(0));
        }
        int numOps = between(1, 100);
        int refreshedSeqNo = -1;
        for (int i = 0; i < numOps; i++) {
            String id = Integer.toString(randomIntBetween(i, i + 5));
            ParsedDocument doc = createParsedDoc(id, null);
            if (randomBoolean()) {
                engine.index(indexForDoc(doc));
            } else {
                engine.delete(new Engine.Delete(doc.type(), doc.id(), newUid(doc.id()), primaryTerm.get()));
            }
            if (rarely()) {
                if (randomBoolean()) {
                    engine.flush();
                } else {
                    engine.refresh("test");
                }
                refreshedSeqNo = i;
            }
        }
        if (refreshedSeqNo == -1) {
            fromSeqNo = between(0, numOps);
            toSeqNo = randomLongBetween(fromSeqNo, numOps * 2);

            Engine.Searcher searcher = engine.acquireSearcher("test", Engine.SearcherScope.INTERNAL);
            try (Translog.Snapshot snapshot = new LuceneChangesSnapshot(
                searcher, mapperService, between(1, LuceneChangesSnapshot.DEFAULT_BATCH_SIZE), fromSeqNo, toSeqNo, false)) {
                searcher = null;
                assertThat(snapshot, SnapshotMatchers.size(0));
            } finally {
                IOUtils.close(searcher);
            }

            searcher = engine.acquireSearcher("test", Engine.SearcherScope.INTERNAL);
            try (Translog.Snapshot snapshot = new LuceneChangesSnapshot(
                    searcher, mapperService, between(1, LuceneChangesSnapshot.DEFAULT_BATCH_SIZE), fromSeqNo, toSeqNo, true)) {
                searcher = null;
                IllegalStateException error = expectThrows(IllegalStateException.class, () -> drainAll(snapshot));
                assertThat(error.getMessage(),
                    containsString("Not all operations between min_seqno [" + fromSeqNo + "] and max_seqno [" + toSeqNo + "] found"));
            }finally {
                IOUtils.close(searcher);
            }
        }else {
            fromSeqNo = randomLongBetween(0, refreshedSeqNo);
            toSeqNo = randomLongBetween(refreshedSeqNo + 1, numOps * 2);
            Engine.Searcher searcher = engine.acquireSearcher("test", Engine.SearcherScope.INTERNAL);
            try (Translog.Snapshot snapshot = new LuceneChangesSnapshot(
                searcher, mapperService, between(1, LuceneChangesSnapshot.DEFAULT_BATCH_SIZE), fromSeqNo, toSeqNo, false)) {
                searcher = null;
                assertThat(snapshot, SnapshotMatchers.containsSeqNoRange(fromSeqNo, refreshedSeqNo));
            }finally {
                IOUtils.close(searcher);
            }
            searcher = engine.acquireSearcher("test", Engine.SearcherScope.INTERNAL);
            try (Translog.Snapshot snapshot = new LuceneChangesSnapshot(
                    searcher, mapperService, between(1, LuceneChangesSnapshot.DEFAULT_BATCH_SIZE), fromSeqNo, toSeqNo, true)) {
                searcher = null;
                IllegalStateException error = expectThrows(IllegalStateException.class, () -> drainAll(snapshot));
                assertThat(error.getMessage(),
                    containsString("Not all operations between min_seqno [" + fromSeqNo + "] and max_seqno [" + toSeqNo + "] found"));
            }finally {
                IOUtils.close(searcher);
            }
            toSeqNo = randomLongBetween(fromSeqNo, refreshedSeqNo);
            searcher = engine.acquireSearcher("test", Engine.SearcherScope.INTERNAL);
            try (Translog.Snapshot snapshot = new LuceneChangesSnapshot(
                searcher, mapperService, between(1, LuceneChangesSnapshot.DEFAULT_BATCH_SIZE), fromSeqNo, toSeqNo, true)) {
                searcher = null;
                assertThat(snapshot, SnapshotMatchers.containsSeqNoRange(fromSeqNo, toSeqNo));
            }finally {
                IOUtils.close(searcher);
            }
        }
        // Get snapshot via engine will auto refresh
        fromSeqNo = randomLongBetween(0, numOps - 1);
        toSeqNo = randomLongBetween(fromSeqNo, numOps - 1);
        try (Translog.Snapshot snapshot = engine.newLuceneChangesSnapshot("test", mapperService, fromSeqNo, toSeqNo, randomBoolean())) {
            assertThat(snapshot, SnapshotMatchers.containsSeqNoRange(fromSeqNo, toSeqNo));
        }
    }

    public void testDedupByPrimaryTerm() throws Exception {
        Map<Long, Long> latestOperations = new HashMap<>();
        List<Integer> terms = Arrays.asList(between(1, 1000), between(1000, 2000));
        int totalOps = 0;
        for (long term : terms) {
            final List<Engine.Operation> ops = generateSingleDocHistory(true,
                randomFrom(VersionType.INTERNAL, VersionType.EXTERNAL, VersionType.EXTERNAL_GTE), false, term, 2, 20, "1");
            primaryTerm.set(Math.max(primaryTerm.get(), term));
            engine.rollTranslogGeneration();
            for (Engine.Operation op : ops) {
                // We need to simulate a rollback here as only ops after local checkpoint get into the engine
                if (op.seqNo() <= engine.getLocalCheckpointTracker().getCheckpoint()) {
                    engine.getLocalCheckpointTracker().resetCheckpoint(randomLongBetween(-1, op.seqNo() - 1));
                    engine.rollTranslogGeneration();
                }
                if (op instanceof Engine.Index) {
                    engine.index((Engine.Index) op);
                } else if (op instanceof Engine.Delete) {
                    engine.delete((Engine.Delete) op);
                }
                latestOperations.put(op.seqNo(), op.primaryTerm());
                if (rarely()) {
                    engine.refresh("test");
                }
                if (rarely()) {
                    engine.flush();
                }
                totalOps++;
            }
        }
        long maxSeqNo = engine.getLocalCheckpointTracker().getMaxSeqNo();
        try (Translog.Snapshot snapshot = engine.newLuceneChangesSnapshot("test", mapperService, 0, maxSeqNo, false)) {
            Translog.Operation op;
            while ((op = snapshot.next()) != null) {
                assertThat(op.toString(), op.primaryTerm(), equalTo(latestOperations.get(op.seqNo())));
            }
            assertThat(snapshot.overriddenOperations(), equalTo(totalOps - latestOperations.size()));
        }
    }

    public void testUpdateAndReadChangesConcurrently() throws Exception {
        Follower[] followers = new Follower[between(1, 3)];
        CountDownLatch readyLatch = new CountDownLatch(followers.length + 1);
        AtomicBoolean isDone = new AtomicBoolean();
        for (int i = 0; i < followers.length; i++) {
            followers[i] = new Follower(engine, isDone, readyLatch);
            followers[i].start();
        }
        boolean onPrimary = randomBoolean();
        List<Engine.Operation> operations = new ArrayList<>();
        int numOps = scaledRandomIntBetween(1, 1000);
        for (int i = 0; i < numOps; i++) {
            String id = Integer.toString(randomIntBetween(1, 10));
            ParsedDocument doc = createParsedDoc(id, randomAlphaOfLengthBetween(1, 5));
            final Engine.Operation op;
            if (onPrimary) {
                if (randomBoolean()) {
                    op = new Engine.Index(newUid(doc), primaryTerm.get(), doc);
                } else {
                    op = new Engine.Delete(doc.type(), doc.id(), newUid(doc.id()), primaryTerm.get());
                }
            } else {
                if (randomBoolean()) {
                    op = replicaIndexForDoc(doc, randomNonNegativeLong(), i, randomBoolean());
                } else {
                    op = replicaDeleteForDoc(doc.id(), randomNonNegativeLong(), i, randomNonNegativeLong());
                }
            }
            operations.add(op);
        }
        readyLatch.countDown();
        concurrentlyApplyOps(operations, engine);
        assertThat(engine.getLocalCheckpointTracker().getCheckpoint(), equalTo(operations.size() - 1L));
        isDone.set(true);
        for (Follower follower : followers) {
            follower.join();
        }
    }

    class Follower extends Thread {
        private final Engine leader;
        private final TranslogHandler translogHandler;
        private final AtomicBoolean isDone;
        private final CountDownLatch readLatch;

        Follower(Engine leader, AtomicBoolean isDone, CountDownLatch readLatch) {
            this.leader = leader;
            this.isDone = isDone;
            this.readLatch = readLatch;
            this.translogHandler = new TranslogHandler(xContentRegistry(), IndexSettingsModule.newIndexSettings(shardId.getIndexName(),
                engine.engineConfig.getIndexSettings().getSettings()));
        }

        void pullOperations(Engine follower) throws IOException {
            long leaderCheckpoint = leader.getLocalCheckpointTracker().getCheckpoint();
            long followerCheckpoint = follower.getLocalCheckpointTracker().getCheckpoint();
            if (followerCheckpoint < leaderCheckpoint) {
                long fromSeqNo = followerCheckpoint + 1;
                long batchSize = randomLongBetween(0, 100);
                long toSeqNo = Math.min(fromSeqNo + batchSize, leaderCheckpoint);
                try (Translog.Snapshot snapshot = leader.newLuceneChangesSnapshot("test", mapperService, fromSeqNo, toSeqNo, true)) {
                    translogHandler.run(follower, snapshot);
                }
            }
        }

        @Override
        public void run() {
            try (Store store = createStore();
                 InternalEngine follower = createEngine(store, createTempDir())) {
                readLatch.countDown();
                readLatch.await();
                while (isDone.get() == false ||
                    follower.getLocalCheckpointTracker().getCheckpoint() < leader.getLocalCheckpointTracker().getCheckpoint()) {
                    pullOperations(follower);
                }
                assertConsistentHistoryBetweenTranslogAndLuceneIndex(follower, mapperService);
                assertThat(getDocIds(follower, true), equalTo(getDocIds(leader, true)));
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }
    }

    private List<Translog.Operation> drainAll(Translog.Snapshot snapshot) throws IOException {
        List<Translog.Operation> operations = new ArrayList<>();
        Translog.Operation op;
        while ((op = snapshot.next()) != null) {
            final Translog.Operation newOp = op;
            logger.error("Reading [{}]", op);
            assert operations.stream().allMatch(o -> o.seqNo() < newOp.seqNo()) : "Operations [" + operations + "], op [" + op + "]";
            operations.add(newOp);
        }
        return operations;
    }
}