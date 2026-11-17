package com.customercompass.comparisons.memory.handwritten;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the tests move by hand, so a 24-hour time limit takes no time. */
final class MutableClock extends Clock {

  private volatile Instant now = Instant.parse("2026-11-18T10:00:00Z");

  void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
