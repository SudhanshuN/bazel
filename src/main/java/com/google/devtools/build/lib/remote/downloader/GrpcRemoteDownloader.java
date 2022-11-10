// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.remote.downloader;

import build.bazel.remote.asset.v1.FetchBlobRequest;
import build.bazel.remote.asset.v1.FetchBlobResponse;
import build.bazel.remote.asset.v1.FetchGrpc;
import build.bazel.remote.asset.v1.FetchGrpc.FetchBlockingStub;
import build.bazel.remote.asset.v1.Qualifier;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.RequestMetadata;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum;
import com.google.devtools.build.lib.bazel.repository.downloader.Downloader;
import com.google.devtools.build.lib.bazel.repository.downloader.HashOutputStream;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.remote.ReferenceCountedChannel;
import com.google.devtools.build.lib.remote.RemoteRetrier;
import com.google.devtools.build.lib.remote.common.RemoteActionExecutionContext;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.TracingMetadataUtils;
import com.google.devtools.build.lib.remote.util.Utils;
import com.google.devtools.build.lib.vfs.Path;
import io.grpc.CallCredentials;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A Downloader implementation that uses Bazel's Remote Execution APIs to delegate downloads of
 * external files to a remote service.
 *
 * <p>See https://github.com/bazelbuild/remote-apis for more details on the exact capabilities and
 * semantics of the Remote Execution API.
 */
public class GrpcRemoteDownloader implements AutoCloseable, Downloader {

  private final String buildRequestId;
  private final String commandId;
  private final ReferenceCountedChannel channel;
  private final Optional<CallCredentials> credentials;
  private final RemoteRetrier retrier;
  private final RemoteCacheClient cacheClient;
  private final RemoteOptions options;
  private final boolean verboseFailures;
  @Nullable private final Downloader fallbackDownloader;

  private final AtomicBoolean closed = new AtomicBoolean();

  // The `Qualifier::name` field uses well-known string keys to attach arbitrary
  // key-value metadata to download requests. These are the qualifier names
  // supported by Bazel.
  private static final String QUALIFIER_CHECKSUM_SRI = "checksum.sri";
  private static final String QUALIFIER_CANONICAL_ID = "bazel.canonical_id";

  public GrpcRemoteDownloader(
      String buildRequestId,
      String commandId,
      ReferenceCountedChannel channel,
      Optional<CallCredentials> credentials,
      RemoteRetrier retrier,
      RemoteCacheClient cacheClient,
      RemoteOptions options,
      boolean verboseFailures,
      @Nullable Downloader fallbackDownloader) {
    this.buildRequestId = buildRequestId;
    this.commandId = commandId;
    this.channel = channel;
    this.credentials = credentials;
    this.retrier = retrier;
    this.cacheClient = cacheClient;
    this.options = options;
    this.verboseFailures = verboseFailures;
    this.fallbackDownloader = fallbackDownloader;
  }

  @Override
  public void close() {
    if (closed.getAndSet(true)) {
      return;
    }
    cacheClient.close();
    channel.release();
  }

  @Override
  public void download(
      List<URL> urls,
      Map<URI, Map<String, List<String>>> authHeaders,
      com.google.common.base.Optional<Checksum> checksum,
      String canonicalId,
      Path destination,
      ExtendedEventHandler eventHandler,
      Map<String, String> clientEnv,
      com.google.common.base.Optional<String> type)
      throws IOException, InterruptedException {
    RequestMetadata metadata =
        TracingMetadataUtils.buildMetadata(buildRequestId, commandId, "remote_downloader", null);
    RemoteActionExecutionContext remoteActionExecutionContext =
        RemoteActionExecutionContext.create(metadata);

    final FetchBlobRequest request =
        newFetchBlobRequest(options.remoteInstanceName, urls, checksum, canonicalId);
    try {
      FetchBlobResponse response =
          retrier.execute(
              () ->
                  channel.withChannelBlocking(
                      channel ->
                          fetchBlockingStub(remoteActionExecutionContext, channel)
                              .fetchBlob(request)));
      final Digest blobDigest = response.getBlobDigest();

      retrier.execute(
          () -> {
            try (OutputStream out = newOutputStream(destination, checksum)) {
              Utils.getFromFuture(
                  cacheClient.downloadBlob(remoteActionExecutionContext, blobDigest, out));
            }
            return null;
          });

    } catch (StatusRuntimeException | IOException e) {
      if (fallbackDownloader == null) {
        if (e instanceof StatusRuntimeException) {
          throw new IOException(e);
        }
        throw e;
      }
      eventHandler.handle(
          Event.warn("Remote Cache: " + Utils.grpcAwareErrorMessage(e, verboseFailures)));
      fallbackDownloader.download(
          urls, authHeaders, checksum, canonicalId, destination, eventHandler, clientEnv, type);
    }
  }

  @VisibleForTesting
  static FetchBlobRequest newFetchBlobRequest(
      String instanceName,
      List<URL> urls,
      com.google.common.base.Optional<Checksum> checksum,
      String canonicalId) {
    FetchBlobRequest.Builder requestBuilder =
        FetchBlobRequest.newBuilder().setInstanceName(instanceName);
    for (URL url : urls) {
      requestBuilder.addUris(url.toString());
    }
    if (checksum.isPresent()) {
      requestBuilder.addQualifiers(
          Qualifier.newBuilder()
              .setName(QUALIFIER_CHECKSUM_SRI)
              .setValue(checksum.get().toSubresourceIntegrity())
              .build());
    }
    if (!Strings.isNullOrEmpty(canonicalId)) {
      requestBuilder.addQualifiers(
          Qualifier.newBuilder().setName(QUALIFIER_CANONICAL_ID).setValue(canonicalId).build());
    }

    return requestBuilder.build();
  }

  private FetchBlockingStub fetchBlockingStub(
      RemoteActionExecutionContext context, Channel channel) {
    return FetchGrpc.newBlockingStub(channel)
        .withInterceptors(
            TracingMetadataUtils.attachMetadataInterceptor(context.getRequestMetadata()))
        .withInterceptors(TracingMetadataUtils.newDownloaderHeadersInterceptor(options))
        .withCallCredentials(credentials.orElse(null))
        .withDeadlineAfter(options.remoteTimeout.getSeconds(), TimeUnit.SECONDS);
  }

  private OutputStream newOutputStream(
      Path destination, com.google.common.base.Optional<Checksum> checksum) throws IOException {
    OutputStream out = destination.getOutputStream();
    if (checksum.isPresent()) {
      out = new HashOutputStream(out, checksum.get());
    }
    return out;
  }
}
