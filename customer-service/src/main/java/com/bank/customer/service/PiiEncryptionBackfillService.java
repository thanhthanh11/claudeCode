package com.bank.customer.service;

import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.ColumnEncryptionBackfill;
import com.bank.common.crypto.EncryptionKeyring;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Mã hóa PII của khách hàng lưu trước migration 13 (ID_NUMBER, PHONE, EMAIL, ADDRESS) và tính blind index.
 * Idempotent, an toàn khi nhiều instance cùng khởi động (xem {@link ColumnEncryptionBackfill}).
 */
@Service
public class PiiEncryptionBackfillService implements ApplicationRunner {

    private final ColumnEncryptionBackfill backfill;
    private final EncryptionKeyring keyring;

    public PiiEncryptionBackfillService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                                        EncryptionKeyring keyring) {
        this.backfill = new ColumnEncryptionBackfill(jdbcTemplate, transactionTemplate, keyring);
        this.keyring = keyring;
    }

    @Override
    public void run(ApplicationArguments args) {
        BlindIndexer indexer = keyring.blindIndexer();
        backfill.run(new ColumnEncryptionBackfill.Spec("CUSTOMERS",
                "ID_NUMBER_HASH IS NULL OR PHONE NOT LIKE 'enc:v1:%'",
                List.of("ID_NUMBER", "PHONE", "EMAIL", "ADDRESS"), List.of("ID_TYPE"),
                Map.of("ID_NUMBER_HASH", row -> indexer.hashIdNumber(row.get("ID_TYPE"), row.get("ID_NUMBER")),
                        "PHONE_HASH", row -> indexer.hashPhone(row.get("PHONE")))));
    }
}
