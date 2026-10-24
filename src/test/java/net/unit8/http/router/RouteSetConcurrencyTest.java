package net.unit8.http.router;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * 設定の再読み込みとリクエスト処理が並行しても、旧い表か新しい表のどちらかで必ず処理されること。
 * 読み込み途中の状態(空の表、組み立て中の木)が見えてはならない。
 */
public class RouteSetConcurrencyTest {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int FILLERS = 100;

    /** 同じ意味で、ルートの並び順だけが違う2つの設定 */
    private static byte[] config(boolean usersFirst) {
        StringBuilder sb = new StringBuilder("<routes>\n");
        if (usersFirst) sb.append("<match path=\"/users/:id\" to=\"User#show\"/>\n");
        for (int i = 0; i < FILLERS; i++) {
            sb.append("<match path=\"/static").append(i).append("/:id\" to=\"Filler").append(i).append("#show\"/>\n");
        }
        if (!usersFirst) sb.append("<match path=\"/users/:id\" to=\"User#show\"/>\n");
        sb.append("<match path=\"/*any\" to=\"Fallback#index\"/>\n");
        sb.append("</routes>");
        return sb.toString().getBytes(UTF8);
    }

    private static void load(RouteSet routeSet, byte[] config) {
        routeSet.loadStream(new ByteArrayInputStream(config));
    }

    private static Options userShow() {
        return new Options().$("controller", "User").$("action", "show").$("id", "1");
    }

    private static void awaitAll(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
    }

    @Test
    public void recognizeAndGenerateStayCorrectWhileReloading() throws Exception {
        final RouteSet routeSet = new RouteSet();
        final byte[] a = config(false);
        final byte[] b = config(true);
        load(routeSet, a);

        final AtomicBoolean stop = new AtomicBoolean();
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());
        ExecutorService pool = Executors.newFixedThreadPool(7);
        try {
            List<Future<?>> readers = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                readers.add(pool.submit(new Callable<Void>() {
                    public Void call() {
                        while (!stop.get()) {
                            try {
                                Options o = routeSet.recognizePath("/users/1", "GET");
                                assertEquals("User", o.get("controller"));
                                assertEquals("show", o.get("action"));
                            } catch (Throwable t) {
                                failures.add(t);
                                return null;
                            }
                        }
                        return null;
                    }
                }));
            }
            for (int i = 0; i < 2; i++) {
                readers.add(pool.submit(new Callable<Void>() {
                    public Void call() {
                        while (!stop.get()) {
                            try {
                                assertEquals("/users/1", routeSet.generate(userShow()));
                            } catch (Throwable t) {
                                failures.add(t);
                                return null;
                            }
                        }
                        return null;
                    }
                }));
            }
            Future<?> reloader = pool.submit(new Callable<Void>() {
                public Void call() {
                    boolean flip = false;
                    while (!stop.get()) {
                        load(routeSet, flip ? a : b);
                        flip = !flip;
                    }
                    return null;
                }
            });

            Thread.sleep(1500);
            stop.set(true);
            reloader.get(60, TimeUnit.SECONDS);
            awaitAll(readers);
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
        if (!failures.isEmpty()) {
            fail("リロード中に " + failures.size() + " 件の失敗。最初の例: " + failures.get(0));
        }
    }

    @Test
    public void generationWorksAfterReloadCompletes() throws Exception {
        // 生成キャッシュは表ごとに持つので、リロード中の生成が結果をキャッシュに残しても、
        // リロード完了後には新しい表のキャッシュが使われる
        final byte[] a = config(false);
        final byte[] b = config(true);
        for (int trial = 0; trial < 20; trial++) {
            final RouteSet routeSet = new RouteSet();
            load(routeSet, a);
            final AtomicBoolean stop = new AtomicBoolean();
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<Future<?>> generators = new ArrayList<Future<?>>();
                for (int i = 0; i < 3; i++) {
                    generators.add(pool.submit(new Callable<Void>() {
                        public Void call() {
                            while (!stop.get()) {
                                try { routeSet.generate(userShow()); } catch (RoutingException ignore) { }
                            }
                            return null;
                        }
                    }));
                }
                Thread.sleep(10);
                load(routeSet, b);
                stop.set(true);
                awaitAll(generators);
            } finally {
                stop.set(true);
                pool.shutdownNow();
            }
            assertEquals("リロード完了後は必ず生成できる(trial " + trial + ")", "/users/1", routeSet.generate(userShow()));
        }
    }

    @Test
    public void firstRecognitionFromManyThreadsIsSafe() throws Exception {
        final int threads = 8;
        for (int trial = 0; trial < 50; trial++) {
            final RouteSet routeSet = new RouteSet();
            for (int i = 0; i < 200; i++) {
                routeSet.addRoute("/s" + i + "/:id", new Options().$("controller", "S" + i).$("action", "show"));
            }
            final CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<Future<?>>();
                for (int t = 0; t < threads; t++) {
                    final int seed = t;
                    futures.add(pool.submit(new Callable<Void>() {
                        public Void call() throws Exception {
                            start.await();
                            for (int n = 0; n < 50; n++) {
                                int i = (seed * 31 + n * 7) % 200;
                                Options o = routeSet.recognizePath("/s" + i + "/1", "GET");
                                assertEquals("S" + i, o.get("controller"));
                            }
                            return null;
                        }
                    }));
                }
                start.countDown();
                awaitAll(futures);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    public void concurrentGenerationFillsCacheConsistently() throws Exception {
        final int controllers = 500;
        final int threads = 8;
        final RouteSet routeSet = new RouteSet();
        for (int i = 0; i < controllers; i++) {
            routeSet.addRoute("/c" + i + "/:id", new Options().$("controller", "C" + i).$("action", "show"));
        }
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                final int offset = t * 37;
                futures.add(pool.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        start.await();
                        for (int n = 0; n < controllers; n++) {
                            int i = (n + offset) % controllers;
                            Options o = new Options().$("controller", "C" + i).$("action", "show").$("id", "1");
                            assertEquals("/c" + i + "/1", routeSet.generate(o));
                        }
                        return null;
                    }
                }));
            }
            start.countDown();
            awaitAll(futures);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void routeAddedAfterFirstRecognitionIsRecognizedAndGenerated() {
        RouteSet routeSet = new RouteSet();
        routeSet.addRoute("/a", new Options().$("controller", "A").$("action", "index"));
        assertEquals("A", routeSet.recognizePath("/a", "GET").get("controller"));
        try {
            routeSet.generate(new Options().$("controller", "B").$("action", "index"));
            fail("まだ B のルートはない");
        } catch (RoutingException expected) {
        }

        routeSet.addRoute("/b", new Options().$("controller", "B").$("action", "index"));

        assertEquals("B", routeSet.recognizePath("/b", "GET").get("controller"));
        assertEquals("/b", routeSet.generate(new Options().$("controller", "B").$("action", "index")));
    }

    @Test
    public void clearEmptiesTheTable() {
        RouteSet routeSet = new RouteSet();
        routeSet.addRoute("/a", new Options().$("controller", "A").$("action", "index"));
        routeSet.clear();
        assertNull(routeSet.tryRecognizePath("/a", "GET"));
    }

    @Test
    public void tryRecognizePathReturnsNullInsteadOfThrowing() {
        RouteSet routeSet = new RouteSet();
        routeSet.addRoute("/users/:id", new Options().$("controller", "User").$("action", "show"));

        assertNull(routeSet.tryRecognizePath("/nothing/here", "GET"));
        try {
            routeSet.recognizePath("/nothing/here", "GET");
            fail("recognizePath は従来どおり RoutingException を投げる");
        } catch (RoutingException e) {
            assertTrue(e.getMessage().contains("No route matches"));
        }
    }

    @Test
    public void malformedPercentEncodingIsNoMatchNotAnException() {
        RouteSet routeSet = new RouteSet();
        routeSet.addRoute("/users/:id", new Options().$("controller", "User").$("action", "show"));

        assertNull(routeSet.tryRecognizePath("/users/%zz", "GET"));
        try {
            routeSet.recognizePath("/users/%zz", "GET");
            fail("RoutingException になるはず");
        } catch (RoutingException expected) {
            // IllegalArgumentException ではなく RoutingException(= 一致なし)
        }
        assertEquals("a b", routeSet.recognizePath("/users/a%20b", "GET").get("id"));
    }

    @Test
    public void malformedEncodingFallsThroughToLaterRoute() {
        RouteSet routeSet = new RouteSet();
        routeSet.addRoute("/users/:id", new Options().$("controller", "User").$("action", "show"));
        routeSet.addRoute("/users/*rest", new Options().$("controller", "Raw").$("action", "index"));

        Options o = routeSet.tryRecognizePath("/users/%zz", "GET");
        // 1つ目のルートはデコードできず不一致 → 後ろのルートで処理される
        assertNotNull(o);
        assertEquals("Raw", o.get("controller"));
    }
}
