package com.bank.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Mã hóa dữ liệu cũ (lưu trước khi bật mã hóa cột) và tính blind index, theo lô 100 dòng mỗi transaction.
 * Đọc bằng {@code ORDER BY ID FOR UPDATE SKIP LOCKED} và chỉ lấy dòng có ID lớn hơn dòng cuối đã xử lý → idempotent,
 * nhiều instance chạy cùng lúc không xử lý trùng, luôn tiến về phía trước. Dùng JDBC thay vì entity vì Hibernate thấy
 * giá trị sau giải mã không đổi nên không phát sinh UPDATE.
 * <p>
 * Giá trị đã có tiền tố {@code enc:v1:} được giữ nguyên (chỉ tính lại hash), giá trị plaintext được mã hóa.
 */
public class ColumnEncryptionBackfill {

    private static final Logger log = LoggerFactory.getLogger(ColumnEncryptionBackfill.class);
    private static final int BATCH_SIZE = 100;

    /**
     * @param table             tên bảng (hằng trong code, không lấy từ input)
     * @param pendingCondition  điều kiện SQL chọn dòng cần xử lý, vd. {@code EMAIL_HASH IS NULL}
     * @param encryptedColumns  cột cần mã hóa
     * @param readOnlyColumns   cột chỉ đọc để tính hash (vd. ID_TYPE)
     * @param hashColumns       cột hash → hàm tính từ giá trị plaintext của dòng (key = tên cột)
     */
    public record Spec(String table, String pendingCondition, List<String> encryptedColumns,
                       List<String> readOnlyColumns, Map<String, Function<Map<String, String>, String>> hashColumns) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final AesGcmCipher cipher;

    public ColumnEncryptionBackfill(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                    EncryptionKeyring keyring) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.cipher = keyring.db();
    }

    /** @return số dòng đã xử lý */
    public int run(Spec spec) {
        int total = 0;
        long lastId = 0;
        while (true) {
            long from = lastId;
            List<Long> processed = transactionTemplate.execute(status -> runBatch(spec, from));
            if (processed == null || processed.isEmpty()) {
                break;
            }
            total += processed.size();
            lastId = processed.getLast();
        }
        if (total > 0) {
            log.info("Đã mã hóa dữ liệu cũ của bảng {}: {} dòng", spec.table(), total);
        }
        return total;
    }

    private List<Long> runBatch(Spec spec, long afterId) {
        List<String> selected = new ArrayList<>(spec.encryptedColumns());
        selected.addAll(spec.readOnlyColumns());
        String sql = "SELECT ID, " + String.join(", ", selected) + " FROM " + spec.table()
                + " WHERE ID > ? AND (" + spec.pendingCondition() + ") ORDER BY ID FOR UPDATE SKIP LOCKED";
        ResultSetExtractor<List<Map<String, String>>> extractor = rs -> readBatch(rs, selected);
        List<Map<String, String>> rows = jdbcTemplate.query(sql, extractor, afterId);
        List<Long> ids = new ArrayList<>();
        for (Map<String, String> row : rows) {
            update(spec, row);
            ids.add(Long.parseLong(row.get("ID")));
        }
        return ids;
    }

    private static List<Map<String, String>> readBatch(ResultSet rs, List<String> columns) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        while (rows.size() < BATCH_SIZE && rs.next()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("ID", String.valueOf(rs.getLong("ID")));
            for (String column : columns) {
                row.put(column, rs.getString(column));
            }
            rows.add(row);
        }
        return rows;
    }

    private void update(Spec spec, Map<String, String> row) {
        Map<String, String> plain = new LinkedHashMap<>(row);
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : spec.encryptedColumns()) {
            String current = row.get(column);
            plain.put(column, cipher.decrypt(current));
            values.put(column, AesGcmCipher.isEncrypted(current) ? current : cipher.encrypt(current));
        }
        spec.hashColumns().forEach((column, hash) -> values.put(column, hash.apply(plain)));
        String assignments = String.join(", ", values.keySet().stream().map(column -> column + " = ?").toList());
        List<Object> args = new ArrayList<>(values.values());
        args.add(Long.parseLong(row.get("ID")));
        jdbcTemplate.update("UPDATE " + spec.table() + " SET " + assignments + " WHERE ID = ?", args.toArray());
    }
}
