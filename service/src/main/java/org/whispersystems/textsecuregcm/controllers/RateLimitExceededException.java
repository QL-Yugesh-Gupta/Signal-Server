/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import io.grpc.Metadata;
import io.grpc.Status;
import java.time.Duration;
import java.util.Optional;
import javax.annotation.Nullable;
import org.whispersystems.textsecuregcm.grpc.ConvertibleToGrpcStatus;

public class RateLimitExceededException extends Exception implements ConvertibleToGrpcStatus {

  public static final Metadata.Key<Duration> RETRY_AFTER_DURATION_KEY =
      Metadata.Key.of("retry-after", new Metadata.AsciiMarshaller<>() {
        @Override
        public String toAsciiString(final Duration value) {
          return value.toString();
        }

        @Override
        public Duration parseAsciiString(final String serialized) {
          return Duration.parse(serialized);
        }
      });

  @Nullable
  private final Duration retryDuration;
  private final boolean legacy;

  /**
   * Constructs a new exception indicating when it may become safe to retry
   *
   * @param retryDuration A duration to wait before retrying, null if no duration can be indicated
   * @param legacy        whether to use a legacy status code when mapping the exception to an HTTP response
   */
  public RateLimitExceededException(@Nullable final Duration retryDuration, final boolean legacy) {
    super(null, null, true, false);
    this.retryDuration = retryDuration;
    this.legacy = legacy;
  }

  public Optional<Duration> getRetryDuration() {
    return Optional.ofNullable(retryDuration);
  }

  public boolean isLegacy() {
    return legacy;
  }

  @Override
  public Status grpcStatus() {
    return Status.RESOURCE_EXHAUSTED;
  }

  @Override
  public Optional<Metadata> grpcMetadata() {
    return getRetryDuration()
        .map(duration -> {
          final Metadata metadata = new Metadata();
          metadata.put(RETRY_AFTER_DURATION_KEY, duration);
          return metadata;
        });
  }
}
