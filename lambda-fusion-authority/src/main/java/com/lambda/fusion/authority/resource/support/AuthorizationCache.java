package com.lambda.fusion.authority.resource.support;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 权限写入提交后刷新既有缓存，避免在事务提交前清除后又回填旧授权。 */
@Component
@RequiredArgsConstructor
public class AuthorizationCache {
    private final ObjectProvider<CacheManager> managers;

    public void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict();
                }
            });
        } else {
            evict();
        }
    }

    private void evict() {
        managers.orderedStream().forEach(manager -> {
            for (String name : List.of("ResourceOwners", "auth:role", "auth:permission")) {
                var cache = manager.getCache(name);
                if (cache != null) cache.clear();
            }
        });
    }
}
