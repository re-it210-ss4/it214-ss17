package com.grabfood.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Bài 2: Triển khai Lazy Loading với @Cacheable.
 *
 * Chiến lược:
 *  - Lần gọi đầu (Cache Miss): phương thức thực thi, Thread.sleep(3000) mô phỏng
 *    query DB phức tạp, kết quả được lưu vào Redis với key = restaurantId.
 *  - Lần gọi sau (Cache Hit): Spring AOP chặn trước khi vào phương thức,
 *    trả về dữ liệu từ Redis → thời gian < 20ms, KHÔNG in log "Truy vấn DB".
 *
 * Kiểm tra bằng curl:
 *   curl -w "\nThời gian: %{time_total}s\n" http://localhost:8080/restaurants/101/menu
 */
@Slf4j
@Service
public class RestaurantService {

    /**
     * Lấy danh sách menu theo restaurantId với Lazy Loading.
     *
     * @param id ID của nhà hàng (ví dụ: 101)
     * @return Danh sách món ăn (List<Map>) – trong thực tế trả về List<MenuItem>
     *
     * Annotation:
     *  value = "restaurantMenu" – tên cache (khác video dùng "menuCache")
     *  key   = "#id"           – cache key là giá trị tham số id (ví dụ: 101)
     *
     * Key Redis thực tế sẽ là: "restaurantMenu::101"
     */
    @Cacheable(value = "restaurantMenu", key = "#id")
    public List<Map<String, Object>> getMenuByRestaurantId(Long id) {

        // ── Cache Miss: phương thức được thực thi ────────────────────
        log.info(">>> [Cache Miss] Truy vấn DB để lấy menu của nhà hàng id={}", id);

        // Giả lập độ trễ database (JOIN phức tạp mất ~3 giây)
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Hard-code dữ liệu mẫu từ bảng menu_items (tương đương kết quả DB)
        // INSERT INTO menu_items VALUES (1, 101, 'Phở Bò', 55000), (2, 101, 'Bún Chả', 60000)
        List<Map<String, Object>> menu = List.of(
                Map.of("id", 1, "restaurant_id", 101, "dish_name", "Phở Bò",  "price", 55000),
                Map.of("id", 2, "restaurant_id", 101, "dish_name", "Bún Chả", "price", 60000)
        );

        log.info(">>> Trả về {} món ăn cho nhà hàng id={}, đã lưu vào Redis.", menu.size(), id);
        return menu;
        // ── Sau khi return: Spring lưu kết quả vào Redis ─────────────
        // redis-cli> GET "restaurantMenu::101"
        // → [{"id":1,"dish_name":"Phở Bò","price":55000}, ...]
    }
}
