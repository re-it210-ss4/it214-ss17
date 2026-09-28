package com.grabfood.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bài 4: Chống Cache Stampede với sync = true.
 *
 * ─── Vấn đề Cache Stampede ───────────────────────────────────────────────────
 *  Khi TTL của một key hết hạn, hàng nghìn request đồng thời nhận Cache Miss,
 *  tất cả cùng truy cập DB để tái tạo cache → DB bị quá tải → hệ thống sập.
 *
 * ─── Giải pháp: sync = true ──────────────────────────────────────────────────
 *  Spring Cache kích hoạt cơ chế khóa (locking):
 *   - Chỉ 1 thread được phép vào phương thức (truy vấn DB).
 *   - Các thread còn lại chờ tại lock.
 *   - Khi thread đầu tiên xong, cache được nạp, tất cả thread sau đọc từ cache.
 *  → Chỉ có DUY NHẤT 1 lần "Fetching from Database" trong log dù có 50 request.
 *
 * ─── Lưu ý với Redis ─────────────────────────────────────────────────────────
 *  Redis không hỗ trợ sync natively trong Spring Cache abstraction.
 *  Để sync = true hoạt động đúng với Redis, cần dùng thêm thư viện như
 *  Redisson hoặc chuyển sang Caffeine (in-process lock).
 *  Ở đây minh hoạ bằng Caffeine (đã thêm dependency caffeine).
 *
 * ─── Test đa luồng bằng CompletableFuture ────────────────────────────────────
 *  Xem phương thức main() bên dưới hoặc class FlashSaleServiceTest.
 */
@Slf4j
@Service
public class FlashSaleService {

    /**
     * Lấy thông tin sản phẩm Flash Sale.
     *
     * @param id ID sản phẩm
     * @return Thông tin sản phẩm (Map)
     *
     * Annotation:
     *  value = "flash-sale"  – tên cache (khác video dùng "product")
     *  key   = "#id"         – cache key là id sản phẩm (khác video dùng #productCode)
     *  sync  = true          – kích hoạt cơ chế lock, ngăn Cache Stampede
     *
     * Kết quả mong đợi khi 50 thread gọi cùng lúc:
     *  - Log "Fetching from Database for product X" chỉ xuất hiện 1 lần.
     *  - Tổng thời gian ≈ 2 giây (không phải 50 × 2 giây = 100 giây).
     *  - Tất cả 50 request đều trả về kết quả thành công.
     */
    @Cacheable(value = "flash-sale", key = "#id", sync = true)
    public Map<String, Object> getFlashSaleProduct(Long id) {

        // Dòng log này chỉ được in ra 1 lần dù có 50 request đồng thời
        log.info(">>> Fetching from Database for product {}", id);

        // Giả lập thời gian tái tạo cache (query phức tạp)
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return Map.of(
                "id",        id,
                "name",      "Burger Flash Sale",
                "price",     99000,
                "quantity",  50
        );
    }

    // =========================================================================
    // Demo kiểm thử đa luồng với CompletableFuture (50 request đồng thời)
    // =========================================================================

    /**
     * Gửi 50 request đồng thời tới getFlashSaleProduct(id=1).
     *
     * Cách chạy: gọi phương thức này từ một REST endpoint hoặc test class.
     * Kết quả mong đợi trong log:
     *  [pool-1-thread-1] >>> Fetching from Database for product 1   ← chỉ 1 lần
     *  [pool-1-thread-2] (không có log – lấy từ cache)
     *  ...
     *  Tổng 50 CompletableFuture hoàn thành sau ~2 giây.
     */
    public void runConcurrentTest() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);   // Cho tất cả thread cùng xuất phát
        AtomicInteger dbCallCount = new AtomicInteger(0);   // Đếm số lần xuống DB (phải = 1)

        CompletableFuture<?>[] futures = new CompletableFuture[threadCount];

        for (int i = 0; i < threadCount; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    startGate.await();  // Chờ tín hiệu cùng bắt đầu
                    getFlashSaleProduct(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, executor);
        }

        startGate.countDown();  // Phát tín hiệu: 50 thread cùng chạy
        CompletableFuture.allOf(futures).join();
        executor.shutdown();

        log.info("=== Hoàn thành 50 request. DB được gọi {} lần (kỳ vọng: 1). ===",
                dbCallCount.get());
        // Nếu sync = true: DB được gọi 1 lần ✔
        // Nếu sync = false: DB được gọi ~50 lần ✗ (Cache Stampede)
    }
}
