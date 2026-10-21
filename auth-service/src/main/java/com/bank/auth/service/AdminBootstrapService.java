package com.bank.auth.service;

import com.bank.auth.entity.Role;
import com.bank.auth.entity.User;
import com.bank.auth.repository.RoleRepository;
import com.bank.auth.repository.UserRepository;
import com.bank.common.crypto.BlindIndexer;
import com.bank.common.crypto.EncryptionKeyring;
import com.bank.common.security.RoleCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo tài khoản ADMIN đầu tiên khi hệ thống chưa có ADMIN nào (cấu hình bank.auth.bootstrap-admin.*).
 */
@Slf4j
@Service
public class AdminBootstrapService implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final BlindIndexer blindIndexer;
    private final String username;
    private final String password;
    private final String email;

    public AdminBootstrapService(UserRepository userRepository, RoleRepository roleRepository,
                                 PasswordEncoder passwordEncoder, EncryptionKeyring keyring,
                                 @Value("${bank.auth.bootstrap-admin.username:}") String username,
                                 @Value("${bank.auth.bootstrap-admin.password:}") String password,
                                 @Value("${bank.auth.bootstrap-admin.email:}") String email) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.blindIndexer = keyring.blindIndexer();
        this.username = username;
        this.password = password;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRoleCode(RoleCodes.ADMIN)) {
            return;
        }
        if (username.isBlank() || password.isBlank() || email.isBlank()) {
            log.warn("Chưa có tài khoản ADMIN và chưa cấu hình bank.auth.bootstrap-admin.* — bỏ qua tạo ADMIN");
            return;
        }
        if (userRepository.existsByUsername(username)) {
            log.warn("Username '{}' đã tồn tại nhưng không có role ADMIN — bỏ qua tạo ADMIN", username);
            return;
        }
        Role adminRole = roleRepository.findByCode(RoleCodes.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Thiếu role ADMIN trong BANK_AUTH.ROLES"));
        User admin = new User();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setEmail(email);
        admin.setEmailHash(blindIndexer.hashEmail(email));
        admin.getRoles().add(adminRole);
        userRepository.save(admin);
        log.warn("Đã tạo tài khoản ADMIN đầu tiên '{}'. Hãy đổi mật khẩu mặc định!", username);
    }
}
