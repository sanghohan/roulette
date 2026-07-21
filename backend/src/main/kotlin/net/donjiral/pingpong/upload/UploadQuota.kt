package net.donjiral.pingpong.upload

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * 하루 총 업로드 "용량" 상한 (단일 인스턴스 인메모리).
 *
 * RateLimitFilter 는 요청 "횟수"만 막습니다. 횟수가 남아 있어도
 * 큰 파일이 계속 들어오면 S3 저장 비용이 늘 수 있으므로
 * 실제 바이트 총량으로 한 번 더 막습니다.
 *
 * 재시작하면 초기화되지만, 이 방어의 목적은 "폭주를 하루 단위로 묶어두는 것"이라
 * 정확한 회계보다 상한이 존재한다는 점이 중요합니다.
 * 최종 방어선은 AWS Budgets 알림 + S3 라이프사이클 규칙입니다(배포 가이드 참고).
 */
@Component
class UploadQuota(
    @Value("\${app.upload.global-bytes-per-day:2147483648}") private val bytesPerDay: Long
) {
    private val day = AtomicLong(-1)
    private val used = AtomicLong(0)

    /** 이번 업로드가 하루 총량 안에 들어오면 예약하고 true. */
    @Synchronized
    fun tryReserve(bytes: Long): Boolean {
        val today = System.currentTimeMillis() / 1000 / 86400
        if (day.get() != today) {
            day.set(today)
            used.set(0)
        }
        if (used.get() + bytes > bytesPerDay) return false
        used.addAndGet(bytes)
        return true
    }

    /** 업로드가 실패했을 때 예약분을 되돌립니다. */
    @Synchronized
    fun release(bytes: Long) {
        used.addAndGet(-bytes).let { if (it < 0) used.set(0) }
    }
}
