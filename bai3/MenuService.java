package com.grabfood.service;

import com.grabfood.model.Menu;
import com.grabfood.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Bài 3: Đảm bảo nhất quán dữ liệu với @CacheEvict (Cache-Aside Pattern).
 *
 * ─── Tại sao dùng @CacheEvict thay vì @CachePut? ────────────────────────────
 *
 *  @CachePut ghi đè cache ngay sau khi update DB. Trong môi trường đa luồng,
 *  có thể xảy ra Race Condition như sau:
 *
 *      T1: updateMenu(price=75000) → save DB → CachePut (price=75000)  ✔
 *      T2: updateMenu(price=80000) → save DB → CachePut (price=80000)  ✔ (sau T1)
 *      T3: updateMenu(price=70000) → save DB chậm hơn …
 *          … nhưng CachePut(price=70000) chạy SAU T2                  ✗ stale!
 *
 *  Kết quả: DB = 80000 (đúng) nhưng Cache = 70000 (sai) → dữ liệu không nhất quán.
 *
 *  @CacheEvict xóa cache sau update → lần đọc sau sẽ luôn lấy từ DB (đúng) rồi
 *  nạp lại cache, đảm bảo nhất quán dù thứ tự các luồng có thay đổi.
 *
 * ─── Chiến lược Cache-Aside ──────────────────────────────────────────────────
 *  1. Ứng dụng update DB trước  (menuRepository.save)
 *  2. Ứng dụng xóa cache sau    (@CacheEvict)
 *  3. Lần đọc tiếp theo: Cache Miss → lấy từ DB → nạp lại cache (tự động qua @Cacheable)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuRepository menuRepository;

    // ── READ: lấy menu theo id, kết quả được cache vào "menuCache::{id}" ─────
    @Cacheable(value = "menuCache", key = "#id")
    public Menu getMenuById(Long id) {
        log.info(">>> [Cache Miss] Truy vấn DB lấy menu id={}", id);
        return menuRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Menu not found: " + id));
    }

    // ── UPDATE + EVICT: cập nhật DB trước, sau đó xóa cache ─────────────────
    /**
     * Cập nhật thông tin món ăn và xóa cache tương ứng.
     *
     * Request body mẫu:
     * {
     *   "id": 1,
     *   "dish_name": "Phở Bò Đặc Biệt",
     *   "price": 75000
     * }
     *
     * @CacheEvict:
     *  value = "menuCache"   – tên cache (khác video dùng "menus")
     *  key   = "#menu.id"   – xóa đúng key tương ứng với id của menu được update
     *
     * Thứ tự thực hiện (Cache-Aside):
     *  1. menuRepository.save(menu) – ghi DB trước
     *  2. @CacheEvict xóa key "menuCache::1" – cache bị xóa sau khi method return
     *
     * Kiểm tra Redis sau update:
     *  redis-cli> GET "menuCache::1"  →  (nil)  ✔ cache đã bị xóa
     * Gọi GET /menu/1 sau update     →  75000   ✔ lấy từ DB, nạp lại cache
     */
    @CacheEvict(value = "menuCache", key = "#menu.id")
    public Menu updateMenu(Menu menu) {
        log.info(">>> Cập nhật DB: menu id={}, price={}", menu.getId(), menu.getPrice());

        // Bước 1: Cập nhật database TRƯỚC
        Menu saved = menuRepository.save(menu);

        // Bước 2: @CacheEvict sẽ xóa cache SAU KHI method return
        log.info(">>> Cache 'menuCache::{}' sẽ bị xóa sau khi method hoàn tất.", menu.getId());

        return saved;
    }
}
