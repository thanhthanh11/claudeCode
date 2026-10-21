package com.bank.notification.service;

import com.bank.notification.enums.ChannelEnum;

/**
 * Tích hợp với một nhà cung cấp gửi thông báo cho một kênh. Mỗi kênh có tối đa một bean (chọn bằng
 * {@code bank.notification.<kênh>.provider}); {@link NotificationSenderService} định tuyến theo kênh.
 */
public interface NotificationGatewayService {

    ChannelEnum channel();

    /**
     * Gửi đồng bộ; trả về bình thường nghĩa là nhà cung cấp đã nhận.
     *
     * @throws com.bank.notification.exception.NotificationSendException khi gửi lỗi
     */
    void send(String recipient, String subject, String content);
}
