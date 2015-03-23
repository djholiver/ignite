/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.igfs;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.igfs.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.*;
import static org.apache.ignite.events.EventType.*;
import static org.apache.ignite.testframework.GridTestUtils.*;

/**
 * Tests events, generated by {@link org.apache.ignite.IgniteFileSystem} implementation.
 */
public abstract class IgfsEventsAbstractSelfTest extends GridCommonAbstractTest {
    /** IGFS. */
    private static IgfsImpl igfs;

    /** Event listener. */
    private IgnitePredicate<Event> lsnr;

    /**
     * Gets cache configuration.
     *
     * @param gridName Grid name.
     * @return Cache configuration.
     */
    @SuppressWarnings("deprecation")
    protected CacheConfiguration[] getCacheConfiguration(String gridName) {
        CacheConfiguration cacheCfg = defaultCacheConfiguration();

        cacheCfg.setName("dataCache");
        cacheCfg.setCacheMode(PARTITIONED);
        cacheCfg.setNearConfiguration(null);
        cacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        cacheCfg.setEvictionPolicy(null);
        cacheCfg.setAffinityMapper(new IgfsGroupDataBlocksKeyMapper(128));
        cacheCfg.setBackups(0);
        cacheCfg.setAtomicityMode(TRANSACTIONAL);

        CacheConfiguration metaCacheCfg = defaultCacheConfiguration();

        metaCacheCfg.setName("metaCache");
        metaCacheCfg.setCacheMode(REPLICATED);
        metaCacheCfg.setWriteSynchronizationMode(FULL_SYNC);
        metaCacheCfg.setEvictionPolicy(null);
        metaCacheCfg.setAtomicityMode(TRANSACTIONAL);

        return new CacheConfiguration[] {cacheCfg, metaCacheCfg};
    }

    /**
     * @return IGFS configuration for this test.
     */
    protected FileSystemConfiguration getIgfsConfiguration() throws IgniteCheckedException {
        FileSystemConfiguration igfsCfg = new FileSystemConfiguration();

        igfsCfg.setDataCacheName("dataCache");
        igfsCfg.setMetaCacheName("metaCache");
        igfsCfg.setName("igfs");

        igfsCfg.setBlockSize(512 * 1024); // Together with group blocks mapper will yield 64M per node groups.

        return igfsCfg;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        return getConfiguration(gridName, getIgfsConfiguration());
    }

    /**
     * The same as getConfiguration(String gridName) but it sets custom IGFS configuration
     *
     * @param gridName Grid name.
     * @param igfsCfg IGFS configuration.
     * @return Grid configuration.
     * @throws Exception If failed.
     */
    protected IgniteConfiguration getConfiguration(String gridName, FileSystemConfiguration igfsCfg) throws Exception {
        IgniteConfiguration cfg = IgnitionEx.loadConfiguration("config/hadoop/default-config.xml").get1();

        assert cfg != null;

        cfg.setGridName(gridName);

        cfg.setIncludeEventTypes(concat(EVTS_IGFS, EVT_TASK_FAILED, EVT_TASK_FINISHED, EVT_JOB_MAPPED));

        cfg.setFileSystemConfiguration(igfsCfg);

        cfg.setCacheConfiguration(getCacheConfiguration(gridName));

        cfg.setHadoopConfiguration(null);

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(new TcpDiscoveryVmIpFinder(true));

        cfg.setDiscoverySpi(discoSpi);

        return cfg;
    }

    /**
     * Concatenates elements to an int array.
     *
     * @param arr Array.
     * @param obj One or more elements to concatenate.
     * @return Concatenated array.
     */
    protected static int[] concat(@Nullable int[] arr, int... obj) {
        int[] newArr;

        if (arr == null || arr.length == 0)
            newArr = obj;
        else {
            newArr = Arrays.copyOf(arr, arr.length + obj.length);

            System.arraycopy(obj, 0, newArr, arr.length, obj.length);
        }

        return newArr;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        Ignite ignite = startGrid(1);

        igfs = (IgfsImpl) ignite.fileSystems().iterator().next();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        if (lsnr != null) {
            grid(1).events().stopLocalListen(lsnr, EVTS_IGFS);

            lsnr = null;
        }

        // Clean up file system.
        if (igfs != null)
            igfs.format();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        stopGrid(1);
    }

    /**
     * Checks events on CRUD operations on a single file in nested directories.
     *
     * @throws Exception If failed.
     */
    public void testSingleFileNestedDirs() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 6 + 1 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1/dir2/dir3");

        IgfsPath file = new IgfsPath(dir, "file1");

        // Will generate 3 EVT_IGFS_DIR_CREATED + EVT_IGFS_FILE_CREATED + EVT_IGFS_FILE_OPENED_WRITE +
        // EVT_IGFS_FILE_CLOSED and a number of EVT_IGFS_META_UPDATED.
        igfs.create(file, true).close();

        IgfsPath mvFile = new IgfsPath(dir, "mvFile1");

        igfs.rename(file, mvFile); // Will generate EVT_IGFS_FILE_RENAMED.

        // Will generate EVT_IGFS_DIR_DELETED event.
        assertTrue(igfs.delete(dir.parent(), true));

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
        assertTrue(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/dir3"), evt.path());

        evt = (IgfsEvent)evtList.get(3);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/dir3/file1"), evt.path());
        assertFalse(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(4);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/dir3/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(5);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/dir3/file1"), evt.path());
        assertEquals(0, evt.dataSize());

        evt = (IgfsEvent)evtList.get(6);
        assertEquals(EVT_IGFS_FILE_RENAMED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/dir3/file1"), evt.path());
        assertEquals(new IgfsPath("/dir1/dir2/dir3/mvFile1"), evt.newPath());

        evt = (IgfsEvent)evtList.get(7);
        assertEquals(EVT_IGFS_DIR_DELETED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt.path());
    }

    /**
     * Checks events on CRUD operations on a single directory
     * with some files.
     *
     * @throws Exception If failed.
     */
    public void testDirWithFiles() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 4 + 3 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1");

        IgfsPath file1 = new IgfsPath(dir, "file1");
        IgfsPath file2 = new IgfsPath(dir, "file2");

        // Will generate EVT_IGFS_DIR_CREATED + EVT_IGFS_FILE_CREATED + EVT_IGFS_FILE_OPENED_WRITE +
        // EVT_IGFS_FILE_CLOSED_WRITE.
        igfs.create(file1, true).close();

        // Will generate EVT_IGFS_FILE_CREATED + EVT_IGFS_FILE_OPENED_WRITE +
        // EVT_IGFS_FILE_CLOSED.
        igfs.create(file2, true).close();

        // Will generate EVT_IGFS_DIR_DELETED event.
        assertTrue(igfs.delete(dir, true));

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
        assertTrue(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());
        assertFalse(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(3);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(4);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());
        assertFalse(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(5);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());

        evt = (IgfsEvent)evtList.get(6);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());

        evt = (IgfsEvent)evtList.get(7);
        assertEquals(EVT_IGFS_DIR_DELETED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
    }

    /**
     * Checks events on CRUD operations on a single empty
     * directory.
     *
     * @throws Exception If failed.
     */
    public void testSingleEmptyDir() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 1 + 1 + 0 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1");

        igfs.mkdirs(dir); // Will generate EVT_IGFS_DIR_CREATED.

        IgfsPath mvDir = new IgfsPath("/mvDir1");

        igfs.rename(dir, mvDir); // Will generate EVT_IGFS_DIR_RENAMED.

        assertFalse(igfs.delete(dir, true)); // Will generate no event.

        assertTrue(igfs.delete(mvDir, true)); // Will generate EVT_IGFS_DIR_DELETED events.

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
        assertTrue(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_DIR_RENAMED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
        assertEquals(new IgfsPath("/mvDir1"), evt.newPath());
        assertTrue(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_DIR_DELETED, evt.type());
        assertEquals(new IgfsPath("/mvDir1"), evt.path());
        assertTrue(evt.isDirectory());
    }

    /**
     * Checks events on CRUD operations on 2 files.
     *
     * @throws Exception If failed.
     */
    public void testTwoFiles() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 4 + 3 + 2 + 2;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1");

        IgfsPath file1 = new IgfsPath(dir, "file1");

        // Will generate EVT_IGFS_FILE_CREATED event + EVT_IGFS_DIR_CREATED event + OPEN + CLOSE.
        igfs.create(file1, true).close();

        IgfsPath file2 = new IgfsPath(dir, "file2");

        igfs.create(file2, true).close(); // Will generate 1 EVT_IGFS_FILE_CREATED event + OPEN + CLOSE.

        assertTrue(igfs.exists(dir));
        assertTrue(igfs.exists(file1));
        assertTrue(igfs.exists(file2));

        assertTrue(igfs.delete(file1, false)); // Will generate 1 EVT_IGFS_FILE_DELETED and 1 EVT_IGFS_FILE_PURGED.
        assertTrue(igfs.delete(file2, false)); // Same.

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
        assertTrue(evt.isDirectory());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(3);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file1"), evt.path());
        assertEquals(0, evt.dataSize());

        evt = (IgfsEvent)evtList.get(4);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());

        evt = (IgfsEvent)evtList.get(5);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());

        evt = (IgfsEvent)evtList.get(6);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/file2"), evt.path());
        assertEquals(0, evt.dataSize());

        assertOneToOne(
            evtList.subList(7, 11),
            new EventPredicate(EVT_IGFS_FILE_DELETED, new IgfsPath("/dir1/file1")),
            new EventPredicate(EVT_IGFS_FILE_PURGED, new IgfsPath("/dir1/file1")),
            new EventPredicate(EVT_IGFS_FILE_DELETED, new IgfsPath("/dir1/file2")),
            new EventPredicate(EVT_IGFS_FILE_PURGED, new IgfsPath("/dir1/file2"))
        );
    }

    /**
     * Checks events on CRUD operations with non-recursive
     * directory deletion.
     *
     * @throws Exception If failed.
     */
    public void testDeleteNonRecursive() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 2 + 0 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1/dir2");

        igfs.mkdirs(dir); // Will generate 2 EVT_IGFS_DIR_CREATED events.

        try {
            igfs.delete(dir.parent(), false); // Will generate no events.
        }
        catch (IgniteException ignore) {
            // No-op.
        }

        assertTrue(igfs.delete(dir, false)); // Will generate 1 EVT_IGFS_DIR_DELETED event.

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt.path());

        IgfsEvent evt3 = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_DIR_DELETED, evt3.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt3.path());
    }

    /**
     * Checks events on CRUD operations on file move.
     *
     * @throws Exception If failed.
     */
    public void testMoveFile() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 5 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1/dir2");

        IgfsPath file = new IgfsPath(dir, "file1");

        // Will generate 2 EVT_IGFS_DIR_CREATED events + EVT_IGFS_FILE_CREATED_EVENT + OPEN + CLOSE.
        igfs.create(file, true).close();

        igfs.rename(file, dir.parent()); // Will generate 1 EVT_IGFS_FILE_RENAMED.

        assertTrue(igfs.exists(new IgfsPath(dir.parent(), file.name())));

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(3);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(4);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2/file1"), evt.path());
        assertEquals(0, evt.dataSize());

        IgfsEvent evt4 = (IgfsEvent)evtList.get(5);
        assertEquals(EVT_IGFS_FILE_RENAMED, evt4.type());
        assertEquals(new IgfsPath("/dir1/dir2/file1"), evt4.path());
        assertEquals(new IgfsPath("/dir1/file1"), evt4.newPath());
    }

    /**
     * Checks events on CRUD operations with multiple
     * empty directories.
     *
     * @throws Exception If failed.
     */
    public void testNestedEmptyDirs() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 2 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        IgfsPath dir = new IgfsPath("/dir1/dir2");

        assertFalse(igfs.exists(dir.parent()));

        igfs.mkdirs(dir); // Will generate 2 EVT_IGFS_DIR_RENAMED events.

        assertTrue(igfs.delete(dir.parent(), true)); // Will generate EVT_IGFS_DIR_DELETED event.

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_DIR_CREATED, evt.type());
        assertEquals(new IgfsPath("/dir1/dir2"), evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_DIR_DELETED, evt.type());
        assertEquals(new IgfsPath("/dir1"), evt.path());
    }

    /**
     * Checks events on CRUD operations with single
     * file overwrite.
     *
     * @throws Exception If failed.
     */
    public void testSingleFileOverwrite() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 3 + 4 + 1;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        final IgfsPath file = new IgfsPath("/file1");

        igfs.create(file, false).close(); // Will generate create, open and close events.

        igfs.create(file, true).close(); // Will generate same event set + delete and purge events.

        try {
            igfs.create(file, false).close(); // Won't generate any event.
        }
        catch (Exception ignore) {
            // No-op.
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        final IgfsPath file1 = new IgfsPath("/file1");

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(file1, evt.path());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(file1, evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(file1, evt.path());
        assertEquals(0, evt.dataSize());

        assertOneToOne(
            evtList.subList(3, 8),
            new P1<Event>() {
                @Override public boolean apply(Event e) {
                    IgfsEvent e0 = (IgfsEvent)e;

                    return e0.type() == EVT_IGFS_FILE_DELETED && e0.path().equals(file1);
                }
            },
            new P1<Event>() {
                @Override public boolean apply(Event e) {
                    IgfsEvent e0 = (IgfsEvent)e;

                    return e0.type() == EVT_IGFS_FILE_PURGED && e0.path().equals(file1);
                }
            },
            new P1<Event>() {
                @Override public boolean apply(Event e) {
                    IgfsEvent e0 = (IgfsEvent)e;

                    return e0.type() == EVT_IGFS_FILE_CREATED && e0.path().equals(file1);
                }
            },
            new P1<Event>() {
                @Override public boolean apply(Event e) {
                    IgfsEvent e0 = (IgfsEvent)e;

                    return e0.type() == EVT_IGFS_FILE_OPENED_WRITE && e0.path().equals(file1);
                }
            },
            new P1<Event>() {
                @Override public boolean apply(Event e) {
                    IgfsEvent e0 = (IgfsEvent)e;

                    return e0.type() == EVT_IGFS_FILE_CLOSED_WRITE && e0.path().equals(file1);
                }
            }
        );
    }

    /**
     * Checks events on file data transfer operations.
     *
     * @throws Exception If failed.
     */
    public void testFileDataEvents() throws Exception {
        final List<Event> evtList = new ArrayList<>();

        final int evtsCnt = 5;

        final CountDownLatch latch = new CountDownLatch(evtsCnt);

        grid(1).events().localListen(lsnr = new IgnitePredicate<Event>() {
            @Override public boolean apply(Event evt) {
                log.info("Received event [evt=" + evt + ']');

                evtList.add(evt);

                latch.countDown();

                return true;
            }
        }, EVTS_IGFS);

        final IgfsPath file = new IgfsPath("/file1");

        final int dataSize = 1024;

        byte[] buf = new byte[dataSize];

        // Will generate IGFS_FILE_CREATED, IGFS_FILE_OPENED_WRITE, IGFS_FILE_CLOSED_WRITE.
        try (IgfsOutputStream os = igfs.create(file, false)) {
            os.write(buf); // Will generate no events.
        }

        // Will generate EVT_IGFS_FILE_OPENED_READ, IGFS_FILE_CLOSED_READ.
        try (IgfsInputStream is = igfs.open(file, 256)) {
            is.readFully(0, buf); // Will generate no events.
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));

        assertEquals(evtsCnt, evtList.size());

        IgfsEvent evt = (IgfsEvent)evtList.get(0);
        assertEquals(EVT_IGFS_FILE_CREATED, evt.type());
        assertEquals(new IgfsPath("/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(1);
        assertEquals(EVT_IGFS_FILE_OPENED_WRITE, evt.type());
        assertEquals(new IgfsPath("/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(2);
        assertEquals(EVT_IGFS_FILE_CLOSED_WRITE, evt.type());
        assertEquals(new IgfsPath("/file1"), evt.path());
        assertEquals((long)dataSize, evt.dataSize());

        evt = (IgfsEvent)evtList.get(3);
        assertEquals(EVT_IGFS_FILE_OPENED_READ, evt.type());
        assertEquals(new IgfsPath("/file1"), evt.path());

        evt = (IgfsEvent)evtList.get(4);
        assertEquals(EVT_IGFS_FILE_CLOSED_READ, evt.type());
        assertEquals(new IgfsPath("/file1"), evt.path());
        assertEquals((long)dataSize, evt.dataSize());
    }

    /**
     * Predicate for matching {@link org.apache.ignite.events.IgfsEvent}.
     */
    private static class EventPredicate implements IgnitePredicate<Event> {
        /** */
        private final int evt;

        /** */
        private final IgfsPath path;

        /**
         * @param evt Event type.
         * @param path IGFS path.
         */
        EventPredicate(int evt, IgfsPath path) {

            this.evt = evt;
            this.path = path;
        }

        /** {@inheritDoc} */
        @Override public boolean apply(Event e) {
            IgfsEvent e0 = (IgfsEvent)e;

            return e0.type() == evt && e0.path().equals(path);
        }
    }
}
