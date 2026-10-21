package com.bank.auth.repository;

import com.bank.auth.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByUsername(String username);

    /**
     * SELECT ... FOR UPDATE khi đăng nhập: các lần nhập sai song song được tuần tự hóa nên không mất cập nhật
     * FAILED_LOGIN_COUNT (nếu không, gửi nhiều request cùng lúc sẽ thử được quá số lần cho phép). Không fetch join
     * roles để tránh FOR UPDATE trên bảng nối; roles nạp lazy trong cùng transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.username = :username")
    Optional<User> findByUsernameForUpdate(@Param("username") String username);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithRolesById(@Param("id") Long id);

    boolean existsByUsername(String username);

    /** Email đã mã hóa → kiểm tra trùng bằng blind index ({@code BlindIndexer.hashEmail}). */
    boolean existsByEmailHash(String emailHash);

    @Query("select case when count(u) > 0 then true else false end from User u join u.roles r where r.code = :roleCode")
    boolean existsByRoleCode(@Param("roleCode") String roleCode);
}
