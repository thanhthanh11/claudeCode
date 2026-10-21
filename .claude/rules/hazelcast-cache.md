---
paths:
  - "**/config/Hazelcast*.java"
  - "**/*Cache*.java"
  - "**/service/**/*.java"
---

# Hazelcast — cache

- Embedded, mỗi service một cluster (tên = tên service), cấu hình `HazelcastConfigFactory` + `HazelcastConfig`; join TCP/IP theo `bank.hazelcast.members` (env `HAZELCAST_MEMBERS`, trống = một node). Bean `Config` đặt tên `hazelcastInstanceConfig` (tránh trùng tên bean với class `HazelcastConfig`).
- Spring Cache (`spring.cache.type=hazelcast`); giá trị phải `Serializable`; mỗi cache khai báo `MapConfig` + TTL.
- Cache: `roles` (auth); `customer-info`, `customer-info-by-user` (customer); `customer-id-by-user` (account, transaction, notification); `fee-schedules`, `transaction-limits` (transaction, chỉ cấu hình — "không có" dùng hằng `NONE` vì không cache null); `notification-templates`, `customer-contact` (notification).
- **Không cache số dư/tài khoản/giao dịch.** Xóa cache **sau commit** (vd. `CustomerInfoService.evict`, `CacheEvictionUtils.clearAfterCommit`).
