package com.rentopia.auth.service;

import com.rentopia.auth.entity.OtpChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DEVELOPMENT ONLY: writes the OTP to the application log instead of sending it.
 * Replace with a real provider adapter (and remove or profile-restrict this
 * class) before any public deployment.
 */
@Component
public class LoggingOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(OtpChannel channel, String destination, String code) {
        log.warn("[DEV OTP] {} to {}: {}", channel, destination, code);
    }
}
