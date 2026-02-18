package com.gbujak.kanalarz;

import org.jspecify.annotations.NullMarked;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

@NullMarked
class ForkExecutor {

    private final ExecutorService executor;
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    public ForkExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        var future = CompletableFuture.supplyAsync(supplier, this.executor);
        inFlight.add(future);
        future.whenComplete((result, exception) -> inFlight.remove(future));
        return future;
    }

    public void join() {
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
    }
}
