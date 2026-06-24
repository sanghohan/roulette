package net.donjiral.pingpong.aicheck

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class EmailAlertService(
    private val mailProvider: ObjectProvider<JavaMailSender>,
    @Value("\${app.alert.to:}") private val to: String,
    @Value("\${spring.mail.username:}") private val from: String,
    @Value("\${app.alert.cooldown-minutes:60}") private val cooldownMinutes: Long
) {
    private val log = LoggerFactory.getLogger(EmailAlertService::class.java)
    @Volatile private var lastSent = 0L

    /** 크레딧 부족 등 결제 문제 발생 시 메일 알림 (쿨다운으로 스팸 방지) */
    fun creditExhausted(detail: String) {
        val sender = mailProvider.ifAvailable ?: run {
            log.warn("[alert] 메일 미설정 — 크레딧 부족 알림 생략"); return
        }
        if (to.isBlank() || from.isBlank()) {
            log.warn("[alert] 발신/수신 주소 미설정 — 알림 생략"); return
        }
        val now = System.currentTimeMillis()
        if (now - lastSent < cooldownMinutes * 60_000) return  // 쿨다운 중
        lastSent = now
        try {
            val msg = SimpleMailMessage()
            msg.from = from
            msg.setTo(to)
            msg.subject = "[donjiral] ⚠️ AI 판별 크레딧 부족 경고"
            msg.text = """
                Replicate 크레딧 부족으로 AI 판별 요청이 실패했습니다.

                시각: ${LocalDateTime.now()}
                상세: $detail

                → Replicate(https://replicate.com/account/billing)에서 크레딧을 충전하세요.
                (이 알림은 ${cooldownMinutes}분에 한 번만 발송됩니다.)
            """.trimIndent()
            sender.send(msg)
            log.info("[alert] 크레딧 부족 메일 발송 완료 to={}", to)
        } catch (e: Exception) {
            log.warn("[alert] 메일 발송 실패: {}", e.message)
        }
    }
}
