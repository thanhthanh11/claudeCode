package com.bank.notification.repository;

import com.bank.notification.entity.NotificationTemplate;
import com.bank.notification.enums.ChannelEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByCodeAndChannelAndActiveTrue(String code, ChannelEnum channel);

    List<NotificationTemplate> findAllByOrderByCodeAscChannelAsc();
}
