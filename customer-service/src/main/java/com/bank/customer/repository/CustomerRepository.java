package com.bank.customer.repository;

import com.bank.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    /** Số giấy tờ đã mã hóa → so bằng blind index ({@code BlindIndexer.hashIdNumber}, gồm cả loại giấy tờ). */
    boolean existsByIdNumberHash(String idNumberHash);

    boolean existsByCustomerCode(String customerCode);

    /**
     * Tìm theo tên (LIKE), mã khách hàng, hoặc SĐT / số giấy tờ (cột mã hóa → so blind index). {@code idNumberHashes}
     * là hash của từ khóa với mọi loại giấy tờ, không được rỗng.
     */
    @Query("""
            select c from Customer c
            where :keyword is null
               or lower(c.fullName) like lower(concat('%', :keyword, '%'))
               or c.customerCode = :keyword
               or c.phoneHash = :phoneHash
               or c.idNumberHash in :idNumberHashes
            """)
    Page<Customer> search(@Param("keyword") String keyword, @Param("phoneHash") String phoneHash,
                          @Param("idNumberHashes") Collection<String> idNumberHashes, Pageable pageable);
}
