package com.bank.common.util;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;

import java.util.Arrays;
import java.util.List;

/**
 * Tạo cấu hình Hazelcast embedded cơ bản. Mỗi service có cluster riêng (tên = tên service);
 * khi chạy nhiều instance, khai báo danh sách member qua {@code bank.hazelcast.members}
 * (vd. "10.0.0.1:5701,10.0.0.2:5701"). Để trống → chạy một node độc lập (môi trường dev).
 */
public final class HazelcastConfigFactory {

    private HazelcastConfigFactory() {
    }

    public static Config create(String clusterName, String members) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.setInstanceName(clusterName + "-cache");
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.logging.type", "slf4j");

        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        List<String> memberList = members == null ? List.of() : Arrays.stream(members.split(","))
                .map(String::trim)
                .filter(member -> !member.isEmpty())
                .toList();
        join.getTcpIpConfig().setEnabled(!memberList.isEmpty()).setMembers(memberList);
        return config;
    }
}
