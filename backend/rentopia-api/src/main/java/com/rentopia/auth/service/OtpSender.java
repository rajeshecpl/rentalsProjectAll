package com.rentopia.auth.service;

import com.rentopia.auth.entity.OtpChannel;

/**
 * Delivers an OTP. Implementations are adapters for an SMS/email provider
 * (MSG91, Twilio, AWS SNS/SES, ...). Only {@link LoggingOtpSender} exists today.
 */
public interface OtpSender {

    void send(OtpChannel channel, String destination, String code);
}
