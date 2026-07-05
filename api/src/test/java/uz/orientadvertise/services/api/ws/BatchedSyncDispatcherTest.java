package uz.orientadvertise.services.api.ws;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.api.ws.BatchedSyncDispatcher;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler.PushResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchedSyncDispatcherTest {

    private DeviceWebSocketHandler handler;
    private BatchedSyncDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        handler = mock(DeviceWebSocketHandler.class);
        dispatcher = new BatchedSyncDispatcher(handler);
        setField("batchSize", 50);
        setField("batchStaggerMs", 10L);
        when(handler.pushToDevices(any(), any())).thenReturn(new PushResult(0, 0, 0));
    }

    @AfterEach
    void tearDown() {
        dispatcher.destroy();
    }

    @Test
    void dispatch_emptyList_zeroBatches() {
        var result = dispatcher.dispatchSyncToDevices(List.of(), "test");
        assertEquals(0, result.totalDevices());
        assertEquals(0, result.batches());
    }

    @Test
    void dispatch_under50Devices_singleBatch() {
        var ids = IntStream.range(1, 31).mapToObj(i -> (long) i).toList();
        var result = dispatcher.dispatchSyncToDevices(ids, "test");
        assertEquals(30, result.totalDevices());
        assertEquals(1, result.batches());
    }

    @Test
    void dispatch_1000Devices_chunksInto20Batches() {
        var ids = IntStream.range(1, 1001).mapToObj(i -> (long) i).toList();
        var result = dispatcher.dispatchSyncToDevices(ids, "bulk-assignment-change");
        assertEquals(1000, result.totalDevices());
        assertEquals(20, result.batches(), "1000 devices / 50 per batch = 20 batches");
    }

    @Test
    void dispatch_eventuallyCallsHandlerForEachBatch() throws Exception {
        var counter = new AtomicInteger();
        when(handler.pushToDevices(any(), any())).thenAnswer(inv -> {
            counter.incrementAndGet();
            return new PushResult(0, 0, 0);
        });

        var ids = IntStream.range(1, 201).mapToObj(i -> (long) i).toList();
        dispatcher.dispatchSyncToDevices(ids, "test");

        // 200 / 50 = 4 batches; with 10ms stagger they fire within ~50ms
        Thread.sleep(150);
        assertEquals(4, counter.get(), "All 4 batches should have fired");
    }

    @Test
    void dispatch_does_not_block_caller() {
        // 1000 devices with 10ms stagger = ~10 seconds total scheduled work,
        // but the call must return immediately.
        var ids = IntStream.range(1, 1001).mapToObj(i -> (long) i).toList();
        long start = System.nanoTime();
        dispatcher.dispatchSyncToDevices(ids, "test");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 500, "dispatch returned in " + elapsedMs + "ms — should be near-instant");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = BatchedSyncDispatcher.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(dispatcher, value);
    }
}
