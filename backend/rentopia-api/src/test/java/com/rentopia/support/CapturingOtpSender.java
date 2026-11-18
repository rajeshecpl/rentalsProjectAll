package com.rentopia.support;

import com.rentopia.auth.entity.OtpChannel;
import com.rentopia.auth.service.OtpSender;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double that remembers the last code sent to each destination. */
public class CapturingOtpSender implements OtpSender {

    private final Map<String, String> lastCodes = new ConcurrentHashMap<>();

    @Override
    public void send(OtpChannel channel, String destination, String code) {
        lastCodes.put(destination, code);
    }

    public String lastCodeFor(String destination) {
        return lastCodes.get(destination);
    }
}
