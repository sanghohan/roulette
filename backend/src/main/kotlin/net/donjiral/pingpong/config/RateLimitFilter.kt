package net.donjiral.pingpong.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * 간단한 인메모리 속도 제한 (단일 인스턴스 기준).
 * - 모든 /api: IP당 분당 제한 (스팸/플러딩 완화)
 * - /api/aicheck: IP당 분/일 제한 + 전체 일일 상한 (비용 폭탄 방지)
 * Caddy 뒤에 있으므로 X-Forwarded-For 로 실제 IP를 봅니다.
 */
@Component
class RateLimitFilter(
    @Value("\${app.ratelimit.api-per-min:120}") private val apiPerMin: Int,
    @Value("\${app.ratelimit.aicheck-per-min:6}") private val aicheckPerMin: Int,
    @Value("\${app.ratelimit.aicheck-per-day:50}") private val aicheckPerDay: Int,
    @Value("\${app.ratelimit.aicheck-global-per-day:1000}") private val aicheckGlobalPerDay: Int
) : OncePerRequestFilter() {

    // key -> [windowId, count]
    private val minuteApi = ConcurrentHashMap<String, LongArray>()
    private val minuteAi = ConcurrentHashMap<String, LongArray>()
    private val dayAi = ConcurrentHashMap<String, LongArray>()
    private val globalAi = LongArray(2) // [dayId, count]

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val path = req.requestURI
        if (!path.startsWith("/api") || req.method == "OPTIONS") {
            chain.doFilter(req, res); return
        }
        val ip = clientIp(req)
        val nowSec = System.currentTimeMillis() / 1000
        val minWin = nowSec / 60
        val dayWin = nowSec / 86400

        if (!allow(minuteApi, ip, minWin, apiPerMin)) {
            reject(res, "요청이 너무 많아요. 잠시 후 다시 시도해주세요."); return
        }

        if (path.startsWith("/api/aicheck")) {
            if (!allowGlobal(dayWin, aicheckGlobalPerDay)) {
                reject(res, "오늘 분석 요청이 한도에 도달했어요. 내일 다시 이용해주세요."); return
            }
            if (!allow(minuteAi, ip, minWin, aicheckPerMin)) {
                reject(res, "분석 요청이 너무 빨라요. 잠시 후 다시 시도해주세요."); return
            }
            if (!allow(dayAi, ip, dayWin, aicheckPerDay)) {
                reject(res, "오늘 분석 가능 횟수를 모두 사용했어요. 내일 다시 이용해주세요."); return
            }
        }
        // 맵 과다 증가 방지
        if (minuteApi.size > 50000) minuteApi.clear()
        if (minuteAi.size > 50000) minuteAi.clear()
        if (dayAi.size > 100000) dayAi.clear()

        chain.doFilter(req, res)
    }

    private fun allow(map: ConcurrentHashMap<String, LongArray>, key: String, window: Long, limit: Int): Boolean {
        val c = map.computeIfAbsent(key) { longArrayOf(window, 0) }
        synchronized(c) {
            if (c[0] != window) { c[0] = window; c[1] = 0 }
            c[1]++
            return c[1] <= limit
        }
    }

    private fun allowGlobal(window: Long, limit: Int): Boolean {
        synchronized(globalAi) {
            if (globalAi[0] != window) { globalAi[0] = window; globalAi[1] = 0 }
            globalAi[1]++
            return globalAi[1] <= limit
        }
    }

    private fun clientIp(req: HttpServletRequest): String {
        val xff = req.getHeader("X-Forwarded-For")
        if (!xff.isNullOrBlank()) return xff.split(",")[0].trim()
        return req.remoteAddr ?: "unknown"
    }

    private fun reject(res: HttpServletResponse, msg: String) {
        res.status = 429
        res.contentType = "application/json;charset=UTF-8"
        res.writer.write("""{"message":"$msg"}""")
    }
}
