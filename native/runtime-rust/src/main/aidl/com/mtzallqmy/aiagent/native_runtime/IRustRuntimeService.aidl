package com.mtzallqmy.aiagent.native_runtime;

import android.os.ParcelFileDescriptor;

interface IRustRuntimeService {
    String start(String requestJson, in ParcelFileDescriptor workingDirectory);
    String awaitResult(String executionId, long waitTimeoutMs);
    boolean cancel(String executionId);
    boolean release(String executionId);
    String capabilities();
}
