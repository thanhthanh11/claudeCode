package com.bank.notification.service;

import com.bank.common.resilience.RemoteCallGuard;
import com.bank.notification.exception.NotificationSendException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSmsGatewayServiceTest {

    private static final String URL = "https://sms.example.com/send";

    private MockRestServiceServer server;
    private HttpSmsGatewayService gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("remoteCallGuard", RemoteCallGuard.NOOP);
        gateway = new HttpSmsGatewayService(builder, beans.getBeanProvider(RemoteCallGuard.class), URL, "secret-key",
                "BANK", true);
    }

    @Test
    void shouldPostMessageWithoutDiacritics() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret-key"))
                .andExpect(content().json("""
                        {"to":"0912345678","message":"Ma OTP cua ban la 123456. Dung chia se","sender":"BANK"}
                        """))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        gateway.send("0912345678", null, "Mã OTP của bạn là 123456. Đừng chia sẻ");

        server.verify();
    }

    @Test
    void shouldRetryOnServerError() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.send("0912345678", null, "noi dung"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void shouldRetryOnTooManyRequests() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> gateway.send("0912345678", null, "noi dung"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void shouldNotRetryRejectedRequestNorLeakResponseBody() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("invalid phone, message=Ma OTP 123456"));

        assertThatThrownBy(() -> gateway.send("09", null, "Ma OTP 123456"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).doesNotContain("123456");
                });
    }

    @Test
    void shouldRetryOnConnectionError() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("Connection refused")));

        assertThatThrownBy(() -> gateway.send("0912345678", null, "noi dung"))
                .isInstanceOfSatisfying(NotificationSendException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void shouldFailFastWhenUrlOrApiKeyMissing() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        assertThatThrownBy(() -> new HttpSmsGatewayService(RestClient.builder(),
                beans.getBeanProvider(RemoteCallGuard.class), URL, "", "BANK", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRemoveVietnameseDiacritics() {
        assertThat(HttpSmsGatewayService.removeDiacritics("Đặng Thị Hương — số dư"))
                .isEqualTo("Dang Thi Huong — so du");
    }
}
