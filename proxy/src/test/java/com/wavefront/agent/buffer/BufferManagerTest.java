package com.wavefront.agent.buffer;

import static com.wavefront.data.ReportableEntityType.POINT;
import static org.junit.Assert.*;

import com.wavefront.agent.TestUtils;
import com.wavefront.agent.handlers.HandlerKey;
import com.yammer.metrics.core.Gauge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.activemq.artemis.api.core.ActiveMQAddressFullException;
import org.junit.Test;

public class BufferManagerTest {

  @Test
  public void ratedBridgeTest()
      throws IOException, InterruptedException, ActiveMQAddressFullException {
    HandlerKey points = new HandlerKey(POINT, "2878");

    BuffersManagerConfig cfg = new BuffersManagerConfig();
    cfg.buffer = Files.createTempDirectory("wfproxy").toFile().getAbsolutePath();
    cfg.l2 = true;
    cfg.msgExpirationTime = -1;
    cfg.msgRetry = -1;
    BuffersManager.init(cfg, null, null);
    BuffersManager.registerNewQueueIfNeedIt(points);

    Gauge<Object> memory = BuffersManager.l1GetMcGauge(points);
    Gauge<Object> disk = BuffersManager.l2GetMcGauge(points);

    assertEquals("MessageCount", 0L, memory.value());
    assertEquals("MessageCount", 0L, disk.value());

    List<String> msgs = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      msgs.add("turur");
    }
    //    BuffersManager.getLeve2().sendMsg(points, msgs);

    int ticks = 0;
    while ((Long) memory.value() != 100L) {
      ticks++;
      Thread.sleep(1000);
    }
    assertTrue("ticks is " + ticks, ((ticks > 9) && (ticks < 13)));
  }

  @Test
  public void expirationTest() throws IOException, InterruptedException {
    Path buffer = Files.createTempDirectory("wfproxy");
    System.out.println("buffer: " + buffer);

    HandlerKey points = new HandlerKey(POINT, "2878");

    BuffersManagerConfig cfg = new BuffersManagerConfig();
    cfg.buffer = buffer.toFile().getAbsolutePath();
    cfg.msgExpirationTime = 500;
    cfg.msgRetry = -1;
    BuffersManager.init(cfg, null, null);
    BuffersManager.registerNewQueueIfNeedIt(points);

    Gauge mc2878 = BuffersManager.l1GetMcGauge(points);
    assertEquals("MessageCount", 0l, mc2878.value());
    BuffersManager.sendMsg(points, "tururu");
    assertEquals("MessageCount", 1l, mc2878.value());
    Thread.sleep(1_000);
    assertEquals("MessageCount", 0l, mc2878.value());
  }

  @Test
  public void expiration_L2_Test() throws IOException, InterruptedException {
    Path buffer = Files.createTempDirectory("wfproxy");
    System.out.println("buffer: " + buffer);

    HandlerKey points = new HandlerKey(POINT, "2878");

    BuffersManagerConfig cfg = new BuffersManagerConfig();
    cfg.buffer = buffer.toFile().getAbsolutePath();
    cfg.l2 = true;
    cfg.msgExpirationTime = 100;
    cfg.msgRetry = -1;
    BuffersManager.init(cfg, null, null);
    BuffersManager.registerNewQueueIfNeedIt(points);

    Gauge<Object> memory = BuffersManager.l1GetMcGauge(points);
    Gauge<Object> disk = BuffersManager.l2GetMcGauge(points);

    assertEquals("MessageCount", 0l, memory.value());
    BuffersManager.sendMsg(points, "tururu");
    assertEquals("MessageCount", 1l, memory.value());
    Thread.sleep(1_000);
    assertEquals("MessageCount", 0l, memory.value());
    assertEquals("MessageCount", 1l, disk.value());
    Thread.sleep(1_000);
    assertEquals("MessageCount", 1l, disk.value());
  }

  @Test
  public void MemoryQueueFull() throws IOException, InterruptedException {
    HandlerKey points_2878 = new HandlerKey(POINT, "2878");
    HandlerKey points_2879 = new HandlerKey(POINT, "2879");

    Path buffer = Files.createTempDirectory("wfproxy");
    BuffersManagerConfig cfg = new BuffersManagerConfig();
    cfg.l2 = true;
    cfg.msgRetry = -1;
    cfg.msgExpirationTime = -1;
    cfg.buffer = buffer.toFile().getAbsolutePath();
    BuffersManager.init(cfg, null, null);

    BuffersManager.registerNewQueueIfNeedIt(points_2878);
    BuffersManager.registerNewQueueIfNeedIt(points_2879);

    BuffersManager.getLeve1().setQueueSize(points_2878, 500);

    Gauge mc2878_memory = BuffersManager.l1GetMcGauge(points_2878);
    Gauge mc2878_disk = BuffersManager.l2GetMcGauge(points_2878);
    Gauge mc2879 = BuffersManager.l1GetMcGauge(points_2879);

    for (int i = 0; i < 10; i++) {
      BuffersManager.sendMsg(points_2878, "tururu");
      BuffersManager.sendMsg(points_2879, "tururu");
    }

    assertNotEquals("MessageCount", 0l, mc2878_memory.value());
    assertNotEquals("MessageCount", 0l, mc2878_disk.value());
    assertEquals("MessageCount", 10l, mc2879.value());
  }

  @Test
  public void failDeliverTest() throws InterruptedException, IOException {
    Path buffer = Files.createTempDirectory("wfproxy");
    System.out.println("buffer: " + buffer);

    String msg = "tururu";

    HandlerKey points_2878 = new HandlerKey(POINT, "2878");

    BuffersManagerConfig cfg = new BuffersManagerConfig();
    cfg.buffer = buffer.toFile().getAbsolutePath();
    cfg.msgExpirationTime = -1;
    cfg.msgRetry = 3;
    BuffersManager.init(cfg, null, null);
    BuffersManager.registerNewQueueIfNeedIt(points_2878);

    Gauge mc2878_memory = BuffersManager.l1GetMcGauge(points_2878);
    Gauge mc2878_disk = BuffersManager.l2GetMcGauge(points_2878);

    assertEquals("MessageCount", 0l, mc2878_memory.value());
    assertEquals("MessageCount", 0l, mc2878_disk.value());

    BuffersManager.sendMsg(points_2878, msg);

    assertEquals("MessageCount", 1l, mc2878_memory.value());
    assertEquals("MessageCount", 0l, mc2878_disk.value());

    // force MSG to DL
    for (int i = 0; i < 3; i++) {
      assertEquals("MessageCount", 1l, mc2878_memory.value());
      BuffersManager.onMsgBatch(
          points_2878,
          1,
          new TestUtils.RateLimiter(),
          msgs -> {
            assertEquals("MessageCount", msg, msgs);
            throw new Exception("error");
          });
    }

    Thread.sleep(1000); // wait some time to allow the msg to flight from l0 to l1

    assertEquals("MessageCount", 0l, mc2878_memory.value());
    assertEquals("MessageCount", 1l, mc2878_disk.value());
  }
}